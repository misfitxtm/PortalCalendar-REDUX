//! Runs before any route handler sees a request. Pure, so it is unit-testable off-device.
use std::net::IpAddr;
use std::sync::RwLock;
use subtle::ConstantTimeEq;

pub struct Req<'a> {
    pub method: &'a str,
    pub path: &'a str,
    pub query: &'a str,
    pub host: Option<&'a str>,
    pub origin: Option<&'a str>,
    pub cookie: Option<&'a str>,
    pub authorization: Option<&'a str>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    Allow,
    /// Valid token in the QR link: swap it for a cookie and redirect.
    Bootstrap,
    Reject(u16, &'static str),
}

pub struct Gate {
    token: RwLock<String>,
}

impl Gate {
    /// Refuses short tokens so a bad caller can't silently run an open server.
    pub fn new(token: &str) -> Result<Self, &'static str> {
        if token.len() < 32 {
            return Err("token must be at least 32 chars");
        }
        Ok(Self { token: RwLock::new(token.to_owned()) })
    }

    pub fn token(&self) -> String {
        self.token.read().unwrap_or_else(|p| p.into_inner()).clone()
    }

    /// Swaps the token at once: every cookie and QR link made from the old one stops working.
    pub fn set_token(&self, token: &str) -> Result<(), &'static str> {
        if token.len() < 32 {
            return Err("token must be at least 32 chars");
        }
        *self.token.write().unwrap_or_else(|p| p.into_inner()) = token.to_owned();
        Ok(())
    }

    pub fn check(&self, r: &Req) -> Verdict {
        let Some(host) = r.host else { return Verdict::Reject(400, "missing host") };
        // A DNS-rebinding page must use a hostname; legit clients use the Portal's IP.
        if !host_is_ip_or_localhost(host) {
            return Verdict::Reject(403, "bad host");
        }
        // Same-origin only: blocks cross-site and other-port pages on the same IP.
        if let Some(o) = r.origin {
            if o.strip_prefix("http://") != Some(host) {
                return Verdict::Reject(403, "bad origin");
            }
        }
        if r.method == "GET" && (r.path == "/manifest.json" || r.path == "/icon.svg") {
            return Verdict::Allow;
        }
        // A computer signing in with a pairing code. The handler checks the code (single use, few tries);
        // the same-origin check above already stops other websites from posting to it.
        if r.method == "POST" && r.path == "/pair" {
            return Verdict::Allow;
        }
        // One-off file links (the shopping list QR). The handler checks the short-lived code in the query,
        // so this lets nothing through by itself; ".." is refused here so the prefix can't be used to climb out.
        if r.method == "GET" && r.path.starts_with("/dl/") && !r.path.contains("..") {
            return Verdict::Allow;
        }
        if r.method == "GET" && r.path == "/" && query_param(r.query, "t").is_some_and(|t| self.eq(t)) {
            return Verdict::Bootstrap;
        }
        let presented = r
            .cookie
            .and_then(|c| cookie_value(c, "pt"))
            .or_else(|| r.authorization.and_then(|a| a.strip_prefix("Bearer ")));
        match presented {
            Some(t) if self.eq(t) => Verdict::Allow,
            _ => Verdict::Reject(401, "unauthorized"),
        }
    }

    fn eq(&self, t: &str) -> bool {
        let current = self.token.read().unwrap_or_else(|p| p.into_inner());
        current.as_bytes().ct_eq(t.as_bytes()).into()
    }
}

fn host_is_ip_or_localhost(host: &str) -> bool {
    let name = match host.strip_prefix('[') {
        Some(rest) => rest.split_once(']').map(|(ip, _)| ip).unwrap_or(""),
        None => host.rsplit_once(':').map_or(host, |(h, _)| h),
    };
    name == "localhost" || name.parse::<IpAddr>().is_ok()
}

fn query_param<'a>(q: &'a str, key: &str) -> Option<&'a str> {
    q.split('&').find_map(|kv| kv.split_once('=').filter(|(k, _)| *k == key).map(|(_, v)| v))
}

fn cookie_value<'a>(c: &'a str, name: &str) -> Option<&'a str> {
    c.split(';').find_map(|p| p.trim().split_once('=').filter(|(k, _)| *k == name).map(|(_, v)| v))
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: &str = "0123456789abcdef0123456789abcdef";

    fn req<'a>() -> Req<'a> {
        Req { method: "GET", path: "/api/export", query: "", host: Some("192.168.1.9:8090"),
              origin: None, cookie: Some("pt=0123456789abcdef0123456789abcdef"), authorization: None }
    }

    fn gate() -> Gate { Gate::new(T).unwrap() }

    #[test]
    fn short_token_refused() {
        assert!(Gate::new("abc").is_err());
    }

    #[test]
    fn valid_cookie_allowed() {
        assert_eq!(gate().check(&req()), Verdict::Allow);
    }

    #[test]
    fn bearer_allowed() {
        let r = Req { cookie: None, authorization: Some("Bearer 0123456789abcdef0123456789abcdef"), ..req() };
        assert_eq!(gate().check(&r), Verdict::Allow);
    }

    #[test]
    fn a_new_token_signs_out_the_old_one() {
        let g = gate();
        let fresh = "fedcba9876543210fedcba9876543210";
        assert_eq!(g.check(&req()), Verdict::Allow);
        g.set_token(fresh).unwrap();
        // the old cookie, the old bearer token and the old QR link all stop working
        assert_eq!(g.check(&req()), Verdict::Reject(401, "unauthorized"));
        let old_qr = Req { path: "/", query: "t=0123456789abcdef0123456789abcdef", cookie: None, ..req() };
        assert_eq!(g.check(&old_qr), Verdict::Reject(401, "unauthorized"));
        let new_cookie = Req { cookie: Some("pt=fedcba9876543210fedcba9876543210"), ..req() };
        assert_eq!(g.check(&new_cookie), Verdict::Allow);
        let new_qr = Req { path: "/", query: "t=fedcba9876543210fedcba9876543210", cookie: None, ..req() };
        assert_eq!(g.check(&new_qr), Verdict::Bootstrap);
        assert_eq!(g.token(), fresh);
    }

    #[test]
    fn a_short_replacement_is_refused_and_the_old_token_stays() {
        let g = gate();
        assert!(g.set_token("short").is_err());
        assert_eq!(g.check(&req()), Verdict::Allow);
    }

    #[test]
    fn missing_or_wrong_token_rejected() {
        let none = Req { cookie: None, ..req() };
        let wrong = Req { cookie: Some("pt=nope"), ..req() };
        assert_eq!(gate().check(&none), Verdict::Reject(401, "unauthorized"));
        assert_eq!(gate().check(&wrong), Verdict::Reject(401, "unauthorized"));
    }

    #[test]
    fn rebinding_hostname_rejected() {
        let r = Req { host: Some("evil.example.com:8090"), ..req() };
        assert_eq!(gate().check(&r), Verdict::Reject(403, "bad host"));
    }

    #[test]
    fn ip_literals_and_localhost_ok() {
        for h in ["10.0.0.2:8090", "[fe80::1]:8090", "localhost:8090", "127.0.0.1"] {
            assert!(host_is_ip_or_localhost(h), "{h}");
        }
        assert!(!host_is_ip_or_localhost("192.168.1.9.evil.com:8090"));
    }

    #[test]
    fn cross_origin_rejected_even_with_cookie() {
        for o in ["http://evil.example", "null", "http://192.168.1.9:8080"] {
            let r = Req { method: "POST", origin: Some(o), ..req() };
            assert_eq!(gate().check(&r), Verdict::Reject(403, "bad origin"), "{o}");
        }
        let same = Req { method: "POST", origin: Some("http://192.168.1.9:8090"), ..req() };
        assert_eq!(gate().check(&same), Verdict::Allow);
    }

    #[test]
    fn qr_link_bootstraps_only_on_root() {
        let ok = Req { path: "/", query: "t=0123456789abcdef0123456789abcdef", cookie: None, ..req() };
        assert_eq!(gate().check(&ok), Verdict::Bootstrap);
        let api = Req { path: "/api/export", ..ok };
        assert_eq!(gate().check(&api), Verdict::Reject(401, "unauthorized"));
    }

    #[test]
    fn oauth_callback_needs_auth() {
        let r = Req { path: "/oauth", query: "code=x", cookie: None, ..req() };
        assert_eq!(gate().check(&r), Verdict::Reject(401, "unauthorized"));
    }

    #[test]
    fn download_links_reach_the_handler_without_a_cookie_but_only_by_get() {
        let get = Req { path: "/dl/shopping-list.md", query: "c=x", cookie: None, ..req() };
        assert_eq!(gate().check(&get), Verdict::Allow);
        let post = Req { method: "POST", ..get };
        assert_eq!(gate().check(&post), Verdict::Reject(401, "unauthorized"));
    }

    #[test]
    fn pairing_endpoint_is_open_to_post_only_and_still_same_origin() {
        let post = Req { method: "POST", path: "/pair", cookie: None, ..req() };
        assert_eq!(gate().check(&post), Verdict::Allow);
        let get = Req { method: "GET", path: "/pair", cookie: None, ..req() };
        assert_eq!(gate().check(&get), Verdict::Reject(401, "unauthorized"));
        let cross = Req { method: "POST", path: "/pair", cookie: None, origin: Some("http://evil.example"), ..req() };
        assert_eq!(gate().check(&cross), Verdict::Reject(403, "bad origin"));
        let other = Req { method: "POST", path: "/pair/new", cookie: None, ..req() };
        assert_eq!(gate().check(&other), Verdict::Reject(401, "unauthorized"));
    }

    #[test]
    fn download_prefix_cannot_be_used_to_reach_other_routes() {
        for p in ["/dl/../api/export", "/download/x", "/dl", "/api/dl/x"] {
            let r = Req { path: p, cookie: None, ..req() };
            assert_eq!(gate().check(&r), Verdict::Reject(401, "unauthorized"), "{p}");
        }
    }

    #[test]
    fn static_assets_public() {
        let r = Req { path: "/manifest.json", cookie: None, ..req() };
        assert_eq!(gate().check(&r), Verdict::Allow);
    }
}
