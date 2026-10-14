//! HTTP front door: every request passes the gate before the handler sees it.
use crate::gate::{Gate, Req, Verdict};
use std::io::Read;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;
use tiny_http::{Header, Request};

// Same ceiling as the old NanoHTTPD server: fits base64 photos, small next to the heap.
pub const MAX_BODY: usize = 16 * 1024 * 1024;
const WORKERS: usize = 4;

const SIGNING_IN: &str = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">\
<meta http-equiv=\"refresh\" content=\"0;url=/\"><title>Signing in</title>\
<body style=\"font-family:sans-serif;padding:40px\"><p>Signing in…</p>\
<script>location.replace(\"/\")</script><noscript><p><a href=\"/\">Continue</a></p></noscript>";

const SIGN_IN_HELP: &str = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">\
<title>Sign in to your Portal</title><body style=\"font-family:sans-serif;max-width:32em;margin:40px auto;padding:0 20px;line-height:1.5\">\
<h2>Sign in to your Portal</h2>\
<p>This page needs a sign-in link from the Portal itself.</p>\
<ol><li>On the Portal, tap <b>⚙ Settings</b> and enter the parent PIN.</li>\
<li>Scan the QR code shown there with this phone's camera, and open the link in your browser.</li></ol>\
<p>On a computer? The same link is also written as text under the QR code, so you can copy or type it into this browser.</p>\
<p>Already did that? Your link may be old: the code changes if the app was reinstalled, so scan the current one. \
Open it in a normal browser, not inside another app.</p>";

pub struct Response {
    pub status: u16,
    pub content_type: String,
    pub body: Vec<u8>,
}

pub trait Handler: Send + Sync + 'static {
    fn handle(&self, method: &str, path: &str, query: &str, body: Vec<u8>) -> Response;
}

pub struct Server {
    inner: Arc<tiny_http::Server>,
    stop: Arc<AtomicBool>,
    workers: Vec<JoinHandle<()>>,
}

impl Server {
    // shortcut: tiny_http has no read timeouts, so a slow-loris client can hold a worker; swap the HTTP layer if this ever faces more than a home LAN.
    pub fn start(addr: &str, token: &str, handler: Arc<dyn Handler>) -> Result<Self, String> {
        let gate = Arc::new(Gate::new(token)?);
        let inner = Arc::new(tiny_http::Server::http(addr).map_err(|e| e.to_string())?);
        let stop = Arc::new(AtomicBool::new(false));
        let workers = (0..WORKERS)
            .map(|_| {
                let (srv, gate, h, stop) = (inner.clone(), gate.clone(), handler.clone(), stop.clone());
                std::thread::spawn(move || {
                    while !stop.load(Ordering::Relaxed) {
                        match srv.recv_timeout(Duration::from_millis(250)) {
                            Ok(Some(req)) => serve(req, &gate, h.as_ref()),
                            Ok(None) => {}
                            Err(_) => break,
                        }
                    }
                })
            })
            .collect();
        Ok(Self { inner, stop, workers })
    }

    pub fn port(&self) -> u16 {
        self.inner.server_addr().to_ip().map_or(0, |a| a.port())
    }

    pub fn stop(self) {
        self.stop.store(true, Ordering::Relaxed);
        for w in self.workers {
            let _ = w.join();
        }
    }
}

fn header<'a>(req: &'a Request, name: &str) -> Option<&'a str> {
    req.headers().iter().find(|h| h.field.as_str().as_str().eq_ignore_ascii_case(name)).map(|h| h.value.as_str())
}

fn serve(mut req: Request, gate: &Gate, handler: &dyn Handler) {
    let method = req.method().as_str().to_owned();
    let url = req.url().to_owned();
    let (path, query) = url.split_once('?').unwrap_or((url.as_str(), ""));

    let verdict = gate.check(&Req {
        method: &method,
        path,
        query,
        host: header(&req, "Host"),
        origin: header(&req, "Origin"),
        cookie: header(&req, "Cookie"),
        authorization: header(&req, "Authorization"),
    });
    match verdict {
        Verdict::Reject(401, _) if method == "GET" && path == "/" => {
            // Someone opened the bare address: tell them how to sign in instead of a bare "unauthorized".
            return reply(req, 401, "text/html; charset=utf-8", SIGN_IN_HELP.as_bytes().to_vec(), &[]);
        }
        Verdict::Reject(code, msg) => return reply(req, code, "text/plain", msg.as_bytes().to_vec(), &[]),
        Verdict::Bootstrap => {
            let cookie = format!("pt={}; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000", gate.token());
            // 200 plus a hop made by the page itself, not a 303: a redirect that begins in another app
            // (a QR scanner, a chat link) counts as cross-site, and the browser would then withhold the
            // SameSite=Strict cookie from the redirected request.
            return reply(req, 200, "text/html; charset=utf-8", SIGNING_IN.as_bytes().to_vec(), &[("Set-Cookie", &cookie)]);
        }
        Verdict::Allow => {}
    }

    if req.body_length().is_some_and(|n| n > MAX_BODY) {
        // The unread body must not be reused by keep-alive.
        return reply(req, 413, "application/json", br#"{"error":"request too large"}"#.to_vec(), &[("Connection", "close")]);
    }
    let mut body = Vec::new();
    // Cap the read too: a chunked body has no Content-Length to check up front.
    if req.as_reader().take(MAX_BODY as u64 + 1).read_to_end(&mut body).is_err() || body.len() > MAX_BODY {
        return reply(req, 413, "application/json", br#"{"error":"request too large"}"#.to_vec(), &[("Connection", "close")]);
    }

    let res = catch_unwind(AssertUnwindSafe(|| handler.handle(&method, path, query, body)))
        .unwrap_or_else(|_| Response {
            status: 500,
            content_type: "application/json".into(),
            body: br#"{"error":"internal error"}"#.to_vec(),
        });
    // A /dl/ link is a file to save, not a page to show: name it from the URL (safe characters only).
    let name = path.strip_prefix("/dl/").filter(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b)));
    let disposition = name.map(|n| format!("attachment; filename=\"{n}\""));
    let extra: Vec<(&str, &str)> = disposition.iter().map(|d| ("Content-Disposition", d.as_str())).collect();
    let extra = if res.status == 200 { extra } else { Vec::new() };
    reply(req, res.status, &res.content_type, res.body, &extra);
}

fn reply(req: Request, status: u16, content_type: &str, body: Vec<u8>, extra: &[(&str, &str)]) {
    let mut r = tiny_http::Response::from_data(body).with_status_code(status);
    let fixed = [
        ("Content-Type", content_type),
        ("Cache-Control", "no-store"),
        ("X-Content-Type-Options", "nosniff"),
        ("X-Frame-Options", "DENY"),
        ("Referrer-Policy", "no-referrer"),
    ];
    for (k, v) in fixed.iter().chain(extra) {
        if let Ok(h) = Header::from_bytes(k.as_bytes(), v.as_bytes()) {
            r.add_header(h);
        }
    }
    let _ = req.respond(r);
}
