//! Deterministic mutation fuzzing of the ICS parser. Any panic, or any input that takes more than
//! 3 seconds, fails the test and saves the offending bytes under target/fuzz-failures/ to replay.
//! `cargo test --release` runs a quick pass; `ICS_FUZZ_ITERS=300000 cargo test --release -- --ignored` runs a long one.
use chrono::TimeZone;
use portal_core::ics::{parse, Window};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::{Duration, Instant};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 { self.0 ^= self.0 << 13; self.0 ^= self.0 >> 7; self.0 ^= self.0 << 17; self.0 }
    fn below(&mut self, n: usize) -> usize { (self.next() % n.max(1) as u64) as usize }
}

const TOKENS: &[&str] = &[
    "BEGIN:VEVENT\r\n", "END:VEVENT\r\n", "BEGIN:VALARM\r\n", "END:VALARM\r\n", "BEGIN:VCALENDAR\r\n",
    "RRULE:FREQ=SECONDLY;COUNT=999999999\r\n", "RRULE:FREQ=DAILY;INTERVAL=0\r\n", "RRULE:FREQ=YEARLY;BYDAY=-53MO;BYMONTH=13\r\n",
    "RRULE:FREQ=MONTHLY;BYMONTHDAY=32;BYSETPOS=999\r\n", "RRULE:FREQ=WEEKLY;UNTIL=00000000T000000Z\r\n", "RRULE:\r\n", "RRULE:FREQ=\r\n",
    "DTSTART:00010101T000000Z\r\n", "DTSTART:99991231T235959Z\r\n", "DTSTART;TZID=\"\r\n", "DTSTART;VALUE=DATE:2026022\r\n",
    "DTSTART;TZID=America/Chicago:20260230T250000\r\n", "DTEND:20250101T000000Z\r\n", "DURATION:P99999999W\r\n", "DURATION:PT\r\n",
    "EXDATE:", "RDATE;VALUE=PERIOD:20260101T000000Z/", "RECURRENCE-ID:", "UID:", "STATUS:CANCELLED\r\n",
    "\r\n ", "\r\n\t", "\"", ";", ":", ",", "\\", "\\n", "\u{0}", "\u{feff}", "\u{1F4A5}", "TZID=", "VALUE=DATE",
];

fn mutate(seed: &[u8], other: &[u8], rng: &mut Rng) -> Vec<u8> {
    let mut b = seed.to_vec();
    for _ in 0..1 + rng.below(6) {
        if b.is_empty() { b.extend_from_slice(b"BEGIN:VEVENT\r\n") }
        let at = rng.below(b.len());
        match rng.below(9) {
            0 => { let i = rng.below(b.len()); b[i] ^= 1 << rng.below(8) }
            1 => { let end = (at + 1 + rng.below(64)).min(b.len()); b.drain(at..end); }
            2 => { let t = TOKENS[rng.below(TOKENS.len())].as_bytes(); b.splice(at..at, t.iter().copied()); }
            3 => { let end = (at + 1 + rng.below(200)).min(b.len()); let chunk = b[at..end].to_vec(); for _ in 0..1 + rng.below(40) { b.splice(at..at, chunk.iter().copied()); } }
            4 => b.truncate(at),
            5 => { let o = rng.below(other.len().max(1)); let end = (o + rng.below(400)).min(other.len()); b.splice(at..at, other[o..end].iter().copied()); }
            6 => { let i = rng.below(b.len()); b[i] = rng.next() as u8 }
            7 => { let n = rng.below(2000); b.splice(at..at, std::iter::repeat(b'A').take(n)); }
            _ => { let t = TOKENS[rng.below(TOKENS.len())].as_bytes(); let end = (at + t.len()).min(b.len()); b.splice(at..end, t.iter().copied()); }
        }
        if b.len() > 400_000 { b.truncate(400_000) }
    }
    b
}

fn seeds() -> Vec<Vec<u8>> {
    let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/data");
    let mut v: Vec<Vec<u8>> = std::fs::read_dir(dir).unwrap().filter_map(|e| std::fs::read(e.unwrap().path()).ok()).collect();
    v.sort();
    assert!(v.len() >= 5, "seed corpus missing");
    v
}

fn fuzz(iterations: usize, seed: u64) {
    let seeds = seeds();
    let win = Window {
        start_ms: chrono::Utc.with_ymd_and_hms(2026, 9, 1, 0, 0, 0).unwrap().timestamp_millis(),
        end_ms: chrono::Utc.with_ymd_and_hms(2027, 5, 1, 0, 0, 0).unwrap().timestamp_millis(),
    };
    let mut rng = Rng(seed);
    let mut slowest = Duration::ZERO;
    for i in 0..iterations {
        let a = &seeds[rng.below(seeds.len())];
        let b = &seeds[rng.below(seeds.len())];
        let input = mutate(a, b, &mut rng);
        let t = Instant::now();
        let ok = catch_unwind(AssertUnwindSafe(|| { parse(&input, win, chrono_tz::America::Chicago); })).is_ok();
        let took = t.elapsed();
        slowest = slowest.max(took);
        if took > Duration::from_millis(500) { // worth a look even though it is under the failure limit
            let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/target/fuzz-slow");
            std::fs::create_dir_all(dir).ok();
            std::fs::write(format!("{dir}/slow-{seed}-{i}.ics"), &input).ok();
        }
        if !ok || took > Duration::from_secs(3) {
            let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/target/fuzz-failures");
            std::fs::create_dir_all(dir).ok();
            let path = format!("{dir}/case-{seed}-{i}.ics");
            std::fs::write(&path, &input).ok();
            panic!("{} on iteration {i} ({} bytes, {took:?}); input saved to {path}", if ok { "TOO SLOW" } else { "PANIC" }, input.len());
        }
    }
    eprintln!("{iterations} inputs, slowest {slowest:?}");
}

#[test]
fn quick_mutation_pass() { fuzz(1_500, 0x5eed_0001) }

#[test]
#[ignore = "long run: ICS_FUZZ_ITERS=300000 cargo test --release -- --ignored"]
fn long_mutation_pass() {
    let n = std::env::var("ICS_FUZZ_ITERS").ok().and_then(|s| s.parse().ok()).unwrap_or(100_000);
    fuzz(n, 0xfeed_beef_cafe)
}
