use portal_core::server::{Handler, Response, Server, MAX_BODY};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Arc;

const T: &str = "0123456789abcdef0123456789abcdef";

struct Echo;
impl Handler for Echo {
    fn handle(&self, _m: &str, path: &str, _q: &str, body: Vec<u8>) -> Response {
        if path == "/boom" {
            panic!("handler bug");
        }
        Response { status: 200, content_type: "text/plain".into(), body }
    }
}

fn start() -> (Server, u16) {
    let s = Server::start("127.0.0.1:0", T, Arc::new(Echo)).unwrap();
    let p = s.port();
    (s, p)
}

// Raw TCP so the test can set Host/Origin freely, like an attacker would.
fn http(port: u16, head: &str, body: &str) -> String {
    let mut c = TcpStream::connect(("127.0.0.1", port)).unwrap();
    write!(c, "{head}\r\nConnection: close\r\nContent-Length: {}\r\n\r\n{body}", body.len()).unwrap();
    let mut out = String::new();
    c.read_to_string(&mut out).unwrap();
    out
}

#[test]
fn bare_address_without_sign_in_explains_how_to_sign_in() {
    let (s, p) = start();
    let r = http(p, &format!("GET / HTTP/1.1\r\nHost: 127.0.0.1:{p}"), "");
    assert!(r.starts_with("HTTP/1.1 401") && r.contains("QR code") && r.contains("written as text under the QR") && r.contains("text/html"), "{r}");
    let api = http(p, &format!("GET /api/export HTTP/1.1\r\nHost: 127.0.0.1:{p}"), "");
    assert!(api.ends_with("unauthorized"), "{api}"); // APIs stay terse
    s.stop();
}

#[test]
fn no_cookie_is_401() {
    let (s, p) = start();
    let r = http(p, &format!("GET /api/export HTTP/1.1\r\nHost: 127.0.0.1:{p}"), "");
    assert!(r.starts_with("HTTP/1.1 401"), "{r}");
    s.stop();
}

#[test]
fn qr_link_sets_cookie_then_api_works() {
    let (s, p) = start();
    let r = http(p, &format!("GET /?t={T} HTTP/1.1\r\nHost: 127.0.0.1:{p}"), "");
    assert!(r.starts_with("HTTP/1.1 200"), "{r}"); // not a redirect: see server.rs
    assert!(r.contains("SameSite=Strict") && r.contains("HttpOnly"), "{r}");
    assert!(r.contains("location.replace") && !r.to_lowercase().contains("\r\nlocation:"), "{r}");
    let r = http(p, &format!("POST /api/x HTTP/1.1\r\nHost: 127.0.0.1:{p}\r\nCookie: pt={T}"), "hello");
    assert!(r.starts_with("HTTP/1.1 200") && r.ends_with("hello"), "{r}");
    assert!(r.to_lowercase().contains("x-content-type-options: nosniff"), "{r}");
    s.stop();
}

#[test]
fn a_new_token_signs_out_every_old_cookie_on_the_live_server() {
    let (s, p) = start();
    let call = |cookie: &str| http(p, &format!("GET /api/export HTTP/1.1\r\nHost: 127.0.0.1:{p}\r\nCookie: pt={cookie}"), "");
    let fresh = "fedcba9876543210fedcba9876543210";
    assert!(call(T).starts_with("HTTP/1.1 200"));
    s.set_token(fresh).unwrap();
    assert!(call(T).starts_with("HTTP/1.1 401"), "old cookie must stop working");
    assert!(call(fresh).starts_with("HTTP/1.1 200"));
    // the sign-in link now sets the new token as the cookie, never the old one
    let qr = http(p, &format!("GET /?t={fresh} HTTP/1.1\r\nHost: 127.0.0.1:{p}"), "");
    assert!(qr.contains(&format!("pt={fresh};")) && !qr.contains(&format!("pt={T}")), "{qr}");
    assert!(s.set_token("short").is_err());
    assert!(call(fresh).starts_with("HTTP/1.1 200"), "a refused swap keeps the current token");
    s.stop();
}

#[test]
fn rebinding_host_is_403() {
    let (s, p) = start();
    let r = http(p, &format!("GET / HTTP/1.1\r\nHost: evil.example.com:{p}\r\nCookie: pt={T}"), "");
    assert!(r.starts_with("HTTP/1.1 403"), "{r}");
    s.stop();
}

#[test]
fn oversized_content_length_is_413_without_reading() {
    let (s, p) = start();
    let mut c = TcpStream::connect(("127.0.0.1", p)).unwrap();
    write!(c, "POST /api/x HTTP/1.1\r\nHost: 127.0.0.1:{p}\r\nCookie: pt={T}\r\nContent-Length: {}\r\n\r\n", MAX_BODY + 1).unwrap();
    let mut buf = [0u8; 64];
    let n = c.read(&mut buf).unwrap();
    assert!(String::from_utf8_lossy(&buf[..n]).starts_with("HTTP/1.1 413"));
    drop(c); // tiny_http waits for the promised body; EOF lets the worker finish
    s.stop();
}

#[test]
fn handler_panic_becomes_500_and_server_survives() {
    let (s, p) = start();
    let r = http(p, &format!("GET /boom HTTP/1.1\r\nHost: 127.0.0.1:{p}\r\nCookie: pt={T}"), "");
    assert!(r.starts_with("HTTP/1.1 500"), "{r}");
    let r = http(p, &format!("GET /ok HTTP/1.1\r\nHost: 127.0.0.1:{p}\r\nCookie: pt={T}"), "");
    assert!(r.starts_with("HTTP/1.1 200"), "{r}");
    s.stop();
}
