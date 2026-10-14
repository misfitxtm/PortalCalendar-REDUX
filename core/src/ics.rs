//! iCalendar (.ics) feed parser. The input is an untrusted download, so every stage is capped:
//! input size, line length, event count, recurrence cost and instances per event.
use chrono::{DateTime, Duration, LocalResult, NaiveDate, NaiveDateTime, TimeZone, Utc};
use chrono_tz::Tz;
use rrule::RRuleSet;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::str::FromStr;

pub const MAX_INPUT: usize = 12 * 1024 * 1024;
const MAX_LINE: usize = 65_536;
const MAX_EVENTS: usize = 20_000;
const MAX_INSTANCES: u16 = 2_000;
const MAX_RECURRENCE_ITERATIONS: i64 = 200_000;
const MAX_COUNT: i64 = 100_000;
/// Total recurrence work for one feed. A normal calendar uses a few thousand; this stops 20,000 events
/// that are each individually affordable from adding up to minutes of CPU.
const MAX_TOTAL_ITERATIONS: i64 = 3_000_000;
/// Wall-clock cap on expanding recurrences for a whole feed. An estimate can't see every slow rule
/// (a monthly BYSETPOS=-11 never matches, and the rrule crate searches ~100 ms for it), so time is the backstop.
const EXPANSION_DEADLINE: std::time::Duration = std::time::Duration::from_secs(2);
const DAY_MS: i64 = 86_400_000;
const MIN_YEAR: i32 = 1900;
const MAX_YEAR: i32 = 2200;

#[derive(Clone, Copy)]
pub struct Window {
    pub start_ms: i64,
    pub end_ms: i64,
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Event {
    pub uid: String,
    pub summary: String,
    pub location: String,
    pub cancelled: bool,
    pub start_ms: i64,
    pub all_day: bool,
    pub duration_ms: i64,
    pub recurrence_id_ms: Option<i64>,
    pub recurring: bool,
    /// Start times of this series' occurrences inside the window (recurring masters only).
    pub instances: Vec<i64>,
    /// For a non-recurring event or an edited instance: does it overlap the window?
    pub in_window: bool,
}

#[derive(Debug, Default)]
pub struct Parsed {
    pub events: Vec<Event>,
    /// VEVENTs dropped as unusable or hostile (no DTSTART, bad date, refused recurrence, over the cap).
    pub skipped: u32,
    /// Events whose TZID we couldn't resolve and read in the device zone instead.
    pub tz_fallbacks: u32,
}

impl Parsed {
    pub fn to_json(&self) -> String {
        let events: Vec<Value> = self.events.iter().map(|e| json!({
            "uid": e.uid, "summary": e.summary, "location": e.location, "cancelled": e.cancelled,
            "startMs": e.start_ms, "allDay": e.all_day, "durationMs": e.duration_ms,
            "recurrenceIdMs": e.recurrence_id_ms, "recurring": e.recurring,
            "instances": e.instances, "inWindow": e.in_window,
        })).collect();
        json!({ "events": events, "skipped": self.skipped, "tzFallbacks": self.tz_fallbacks }).to_string()
    }
}

// ------------------------------------------------------------------ lines

struct Prop {
    name: String,
    params: Vec<(String, String)>,
    value: String,
}

impl Prop {
    fn param(&self, k: &str) -> Option<&str> {
        self.params.iter().find(|(n, _)| n == k).map(|(_, v)| v.as_str())
    }
}

/// Splits on `sep` outside double quotes.
fn split_unquoted(s: &str, sep: char) -> Vec<&str> {
    let (mut out, mut start, mut quoted) = (Vec::new(), 0, false);
    for (i, c) in s.char_indices() {
        if c == '"' { quoted = !quoted }
        else if c == sep && !quoted { out.push(&s[start..i]); start = i + c.len_utf8() }
    }
    out.push(&s[start..]);
    out
}

fn parse_prop(line: &str) -> Option<Prop> {
    let mut quoted = false;
    let colon = line.char_indices().find(|&(_, c)| { if c == '"' { quoted = !quoted } c == ':' && !quoted })?.0;
    let mut head = split_unquoted(&line[..colon], ';').into_iter();
    let name = head.next()?.trim().to_ascii_uppercase();
    if name.is_empty() { return None }
    let params = head.filter_map(|p| p.split_once('=')).map(|(k, v)|
        (k.trim().to_ascii_uppercase(), v.trim().trim_matches('"').to_string())).collect();
    Some(Prop { name, params, value: line[colon + 1..].to_string() })
}

/// Unfolds continuation lines (a leading space or tab) and yields logical lines, each capped.
fn logical_lines(text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for raw in text.split('\n') {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        if (line.starts_with(' ') || line.starts_with('\t')) && !out.is_empty() {
            let last = out.last_mut().unwrap();
            if last.len() < MAX_LINE { last.push_str(&line[1..]) }
        } else {
            out.push(line.chars().take(MAX_LINE).collect());
        }
    }
    out
}

fn unescape(v: &str) -> String {
    let mut out = String::with_capacity(v.len());
    let mut it = v.chars();
    while let Some(c) = it.next() {
        if c != '\\' { out.push(c); continue }
        match it.next() {
            Some('n') | Some('N') => out.push('\n'),
            Some(o) => out.push(o),
            None => {}
        }
    }
    out
}

// ------------------------------------------------------------------ dates

#[derive(Clone, Copy, Debug)]
enum Dt {
    AllDay(NaiveDate),
    Floating(NaiveDateTime),
    Utc(NaiveDateTime),
    Zoned(NaiveDateTime, Tz),
}

fn windows_zone(name: &str) -> Option<&'static str> {
    Some(match name {
        "Eastern Standard Time" => "America/New_York",
        "Central Standard Time" => "America/Chicago",
        "Mountain Standard Time" => "America/Denver",
        "Pacific Standard Time" => "America/Los_Angeles",
        "US Mountain Standard Time" => "America/Phoenix",
        "Alaskan Standard Time" => "America/Anchorage",
        "Hawaiian Standard Time" => "Pacific/Honolulu",
        "Atlantic Standard Time" => "America/Halifax",
        "Newfoundland Standard Time" => "America/St_Johns",
        "Canada Central Standard Time" => "America/Regina",
        "Central America Standard Time" => "America/Guatemala",
        "Mexico Standard Time" | "Central Standard Time (Mexico)" => "America/Mexico_City",
        "SA Pacific Standard Time" => "America/Bogota",
        "E. South America Standard Time" => "America/Sao_Paulo",
        "Argentina Standard Time" => "America/Argentina/Buenos_Aires",
        "GMT Standard Time" => "Europe/London",
        "Greenwich Standard Time" => "Atlantic/Reykjavik",
        "W. Europe Standard Time" => "Europe/Berlin",
        "Central Europe Standard Time" => "Europe/Budapest",
        "Central European Standard Time" => "Europe/Warsaw",
        "Romance Standard Time" => "Europe/Paris",
        "E. Europe Standard Time" => "Europe/Chisinau",
        "GTB Standard Time" => "Europe/Bucharest",
        "FLE Standard Time" => "Europe/Kiev",
        "Russian Standard Time" => "Europe/Moscow",
        "Turkey Standard Time" => "Europe/Istanbul",
        "Israel Standard Time" => "Asia/Jerusalem",
        "Arabian Standard Time" => "Asia/Dubai",
        "India Standard Time" => "Asia/Kolkata",
        "Pakistan Standard Time" => "Asia/Karachi",
        "SE Asia Standard Time" => "Asia/Bangkok",
        "China Standard Time" => "Asia/Shanghai",
        "Tokyo Standard Time" => "Asia/Tokyo",
        "Korea Standard Time" => "Asia/Seoul",
        "Singapore Standard Time" => "Asia/Singapore",
        "W. Australia Standard Time" => "Australia/Perth",
        "AUS Eastern Standard Time" => "Australia/Sydney",
        "New Zealand Standard Time" => "Pacific/Auckland",
        "South Africa Standard Time" => "Africa/Johannesburg",
        "Egypt Standard Time" => "Africa/Cairo",
        "UTC" => "UTC",
        _ => return None,
    })
}

/// Resolves a TZID: an IANA name, a common Windows name, or the tail of a path like
/// "/mozilla.org/20050126_1/Europe/Paris". None means "unknown" (the caller falls back).
fn resolve_tz(tzid: &str) -> Option<Tz> {
    let t = tzid.trim().trim_matches('"');
    if let Ok(z) = Tz::from_str(t) { return Some(z) }
    if let Some(w) = windows_zone(t) { return Tz::from_str(w).ok() }
    let parts: Vec<&str> = t.rsplit('/').take(2).collect();
    if parts.len() == 2 { return Tz::from_str(&format!("{}/{}", parts[1], parts[0])).ok() }
    None
}

fn year_ok(d: NaiveDate) -> bool {
    use chrono::Datelike;
    (MIN_YEAR..=MAX_YEAR).contains(&d.year())
}

/// Parses one DATE or DATE-TIME value using the property's parameters.
fn parse_dt(value: &str, prop: &Prop, fallbacks: &mut u32) -> Option<Dt> {
    let v = value.trim();
    let all_day = prop.param("VALUE").is_some_and(|x| x.eq_ignore_ascii_case("DATE")) || (v.len() == 8 && v.bytes().all(|b| b.is_ascii_digit()));
    if all_day {
        let d = NaiveDate::parse_from_str(v.get(..8)?, "%Y%m%d").ok()?;
        return year_ok(d).then_some(Dt::AllDay(d));
    }
    let utc = v.ends_with('Z') || v.ends_with('z');
    let body = v.trim_end_matches(['Z', 'z']);
    let n = NaiveDateTime::parse_from_str(body.get(..15)?, "%Y%m%dT%H%M%S").ok()?;
    if !year_ok(n.date()) { return None }
    if utc { return Some(Dt::Utc(n)) }
    match prop.param("TZID") {
        Some(id) => match resolve_tz(id) {
            Some(z) => Some(Dt::Zoned(n, z)),
            None => { *fallbacks += 1; Some(Dt::Floating(n)) }
        },
        None => Some(Dt::Floating(n)),
    }
}

fn local_ms(n: NaiveDateTime, tz: Tz) -> Option<i64> {
    match tz.from_local_datetime(&n) {
        LocalResult::Single(d) | LocalResult::Ambiguous(d, _) => Some(d.timestamp_millis()),
        // Spring-forward gap: that wall-clock time doesn't exist; use the hour after.
        LocalResult::None => tz.from_local_datetime(&(n + Duration::hours(1))).earliest().map(|d| d.timestamp_millis()),
    }
}

fn to_ms(dt: Dt, device: Tz) -> Option<i64> {
    match dt {
        Dt::AllDay(d) => local_ms(d.and_hms_opt(0, 0, 0)?, device),
        Dt::Floating(n) => local_ms(n, device),
        Dt::Utc(n) => Some(Utc.from_utc_datetime(&n).timestamp_millis()),
        Dt::Zoned(n, z) => local_ms(n, z),
    }
}

/// ISO-8601 duration ("PT1H30M", "P2D", "P1W") in milliseconds; negative or malformed gives 0.
fn parse_duration_ms(v: &str) -> i64 {
    let v = v.trim();
    if v.starts_with('-') { return 0 }
    let Some(rest) = v.trim_start_matches('+').strip_prefix('P') else { return 0 };
    let (mut total, mut num, mut in_time) = (0i64, String::new(), false);
    for c in rest.chars() {
        match c {
            'T' => in_time = true,
            '0'..='9' => if num.len() < 9 { num.push(c) },
            u => {
                let n: i64 = num.parse().unwrap_or(0);
                num.clear();
                let unit = match (u, in_time) {
                    ('W', _) => 7 * DAY_MS, ('D', _) => DAY_MS,
                    ('H', true) => 3_600_000, ('M', true) => 60_000, ('S', true) => 1_000,
                    _ => return 0,
                };
                total = total.saturating_add(n.saturating_mul(unit));
            }
        }
    }
    total.clamp(0, 3660 * DAY_MS)
}

// ------------------------------------------------------------- recurrence

fn canonical(dt: Dt) -> String {
    let f = |n: NaiveDateTime| n.format("%Y%m%dT%H%M%S").to_string();
    match dt {
        Dt::AllDay(d) => format!(";VALUE=DATE:{}", d.format("%Y%m%d")),
        Dt::Floating(n) => format!(":{}", f(n)),
        Dt::Utc(n) => format!(":{}Z", f(n)),
        Dt::Zoned(n, z) => format!(";TZID={}:{}", z.name(), f(n)),
    }
}

/// Estimated work to expand these rules, or None if they are unreasonable (see MAX_RECURRENCE_ITERATIONS).
fn recurrence_cost(rrules: &[&Prop], start_ms: i64, window_end_ms: i64) -> Option<i64> {
    let mut total = 0i64;
    for r in rrules {
        let kv: HashMap<String, String> = r.value.split(';').filter_map(|p| p.split_once('='))
            .map(|(k, v)| (k.trim().to_ascii_uppercase(), v.trim().to_string())).collect();
        let period_s: i64 = match kv.get("FREQ").map(|s| s.to_ascii_uppercase()).as_deref() {
            Some("HOURLY") => 3_600, Some("DAILY") => 86_400, Some("WEEKLY") => 604_800,
            Some("MONTHLY") => 2_592_000, Some("YEARLY") => 31_536_000,
            _ => return None, // SECONDLY, MINUTELY, or no/unknown frequency
        };
        let interval = kv.get("INTERVAL").and_then(|s| s.parse::<i64>().ok()).unwrap_or(1).clamp(1, 1_000_000);
        let mut iterations = ((window_end_ms - start_ms).max(0) / 1000) / (period_s * interval);
        if let Some(c) = kv.get("COUNT").and_then(|s| s.parse::<i64>().ok()) {
            if c > MAX_COUNT { return None }
            iterations = iterations.min(c);
        }
        if iterations > MAX_RECURRENCE_ITERATIONS { return None }
        total += iterations + 1;
    }
    Some(total)
}

/// Start times (ms) of the series' occurrences overlapping the window. None means "refused".
fn expand(start: Dt, props: &[Prop], duration_ms: i64, start_ms: i64, win: Window, device: Tz, fb: &mut u32, budget: &mut i64) -> Option<Vec<i64>> {
    let rrules: Vec<&Prop> = props.iter().filter(|p| p.name == "RRULE").collect();
    // The grammar is ASCII-only, and the rrule crate slices these strings by byte (a multi-byte
    // character in BYDAY made it panic; found by the fuzzer).
    if rrules.iter().any(|r| !r.value.is_ascii()) { return None }
    if !rrules.is_empty() {
        let cost = recurrence_cost(&rrules, start_ms, win.end_ms)?;
        if cost > *budget { return None }
        *budget -= cost;
    }
    let mut text = format!("DTSTART{}\n", canonical(start));
    for r in &rrules { text.push_str(&format!("RRULE:{}\n", r.value.trim())) }
    for p in props.iter().filter(|p| p.name == "EXDATE" || p.name == "RDATE") {
        for v in p.value.split(',').take(MAX_INSTANCES as usize) {
            // A PERIOD ("start/end") contributes its start.
            if let Some(dt) = parse_dt(v.split('/').next().unwrap_or(""), p, fb) {
                text.push_str(&format!("{}{}\n", p.name, canonical(dt)));
            }
        }
    }
    let set: RRuleSet = text.parse().ok()?;
    let lo = win.start_ms - duration_ms.max(DAY_MS) - 1_000;
    let hi = win.end_ms + 1_000;
    let tz = rrule::Tz::UTC;
    let after = DateTime::from_timestamp_millis(lo)?.with_timezone(&tz);
    let before = DateTime::from_timestamp_millis(hi)?.with_timezone(&tz);
    let mut out = Vec::new();
    for d in set.after(after).before(before).all(MAX_INSTANCES).dates {
        let ms = match start {
            // Wall-clock results of floating / all-day series are read in the device zone.
            Dt::AllDay(_) | Dt::Floating(_) => local_ms(d.naive_local(), device)?,
            _ => d.timestamp_millis(),
        };
        out.push(ms);
    }
    Some(out)
}

// ------------------------------------------------------------------ parse

pub fn parse(input: &[u8], win: Window, device: Tz) -> Parsed {
    let mut parsed = Parsed::default();
    if input.len() > MAX_INPUT { parsed.skipped = 1; return parsed }
    let text = String::from_utf8_lossy(input);
    let mut raw_events: Vec<Vec<Prop>> = Vec::new();
    let mut current: Option<Vec<Prop>> = None;
    let mut nested = 0u32; // depth inside the VEVENT (VALARM and friends)
    for line in logical_lines(&text) {
        let Some(p) = parse_prop(&line) else { continue };
        match (p.name.as_str(), current.is_some()) {
            ("BEGIN", false) if p.value.eq_ignore_ascii_case("VEVENT") => { current = Some(Vec::new()); nested = 0 }
            ("BEGIN", true) => nested += 1,
            ("END", true) if nested > 0 => nested -= 1,
            ("END", true) if p.value.eq_ignore_ascii_case("VEVENT") => {
                if raw_events.len() >= MAX_EVENTS { parsed.skipped += 1 } else { raw_events.push(current.take().unwrap()) }
                current = None;
            }
            (_, true) if nested == 0 => current.as_mut().unwrap().push(p),
            _ => {}
        }
    }

    // Pass 1: parse every event, and note which occurrences of a series were edited individually.
    let mut overridden: HashMap<String, HashSet<i64>> = HashMap::new();
    let mut staged: Vec<(Event, Dt, Vec<Prop>)> = Vec::new();
    for props in raw_events {
        let get = |n: &str| props.iter().find(|p| p.name == n);
        let Some(ds) = get("DTSTART") else { parsed.skipped += 1; continue };
        let Some(start) = parse_dt(&ds.value, ds, &mut parsed.tz_fallbacks) else { parsed.skipped += 1; continue };
        let Some(start_ms) = to_ms(start, device) else { parsed.skipped += 1; continue };
        let all_day = matches!(start, Dt::AllDay(_));
        let duration_ms = if let Some(e) = get("DTEND") {
            parse_dt(&e.value, e, &mut parsed.tz_fallbacks).and_then(|d| to_ms(d, device)).map_or(0, |end| (end - start_ms).max(0))
        } else if let Some(d) = get("DURATION") {
            parse_duration_ms(&d.value)
        } else if all_day {
            // One calendar day, not a fixed 24 h, so DST can't spill it onto the next day.
            match start { Dt::AllDay(d) => d.succ_opt().and_then(|n| to_ms(Dt::AllDay(n), device)).map_or(DAY_MS, |n| n - start_ms), _ => DAY_MS }
        } else { 0 };
        let rid = get("RECURRENCE-ID").and_then(|r| parse_dt(&r.value, r, &mut parsed.tz_fallbacks)).and_then(|d| to_ms(d, device));
        let uid = get("UID").map(|p| p.value.trim().to_string()).unwrap_or_default();
        if let (Some(r), false) = (rid, uid.is_empty()) { overridden.entry(uid.clone()).or_default().insert(r); }
        let ev = Event {
            uid,
            summary: get("SUMMARY").map(|p| unescape(&p.value)).unwrap_or_default(),
            location: get("LOCATION").map(|p| unescape(&p.value)).unwrap_or_default(),
            cancelled: get("STATUS").is_some_and(|p| p.value.trim().eq_ignore_ascii_case("CANCELLED")),
            start_ms, all_day, duration_ms,
            recurrence_id_ms: rid,
            recurring: rid.is_some() || props.iter().any(|p| p.name == "RRULE" || p.name == "RDATE"),
            in_window: start_ms + duration_ms >= win.start_ms && start_ms <= win.end_ms,
            instances: Vec::new(),
        };
        staged.push((ev, start, props));
    }

    // Pass 2: expand each series master inside the window, skipping edited occurrences.
    let mut budget = MAX_TOTAL_ITERATIONS;
    let started = std::time::Instant::now();
    for (mut ev, start, props) in staged {
        let master = ev.recurrence_id_ms.is_none() && props.iter().any(|p| p.name == "RRULE" || p.name == "RDATE");
        if master {
            if started.elapsed() > EXPANSION_DEADLINE { parsed.skipped += 1; continue }
            // Defence in depth: a panic inside the recurrence library costs this one event, not the feed.
            let expanded = std::panic::catch_unwind(std::panic::AssertUnwindSafe(||
                expand(start, &props, ev.duration_ms, ev.start_ms, win, device, &mut parsed.tz_fallbacks, &mut budget))).unwrap_or(None);
            match expanded {
                Some(mut inst) => {
                    if let Some(skip) = overridden.get(&ev.uid) { inst.retain(|t| !skip.contains(t)) }
                    inst.retain(|t| t + ev.duration_ms >= win.start_ms && *t <= win.end_ms);
                    ev.instances = inst;
                }
                None => { parsed.skipped += 1; continue }
            }
        }
        parsed.events.push(ev);
    }
    parsed
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    const CHICAGO: Tz = chrono_tz::America::Chicago;
    fn ms(y: i32, mo: u32, d: u32, h: u32, mi: u32) -> i64 {
        Utc.with_ymd_and_hms(y, mo, d, h, mi, 0).unwrap().timestamp_millis()
    }
    fn win() -> Window { Window { start_ms: ms(2026, 1, 1, 0, 0), end_ms: ms(2026, 7, 1, 0, 0) } }
    fn run(s: &str) -> Parsed { parse(s.as_bytes(), win(), CHICAGO) }
    fn cal(body: &str) -> String { format!("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n{body}END:VCALENDAR\r\n") }

    #[test]
    fn timed_event_in_a_named_zone() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:a1\r\nDTSTART;TZID=America/Chicago:20260105T090000\r\nDTEND;TZID=America/Chicago:20260105T100000\r\nSUMMARY:Doctor\\, dentist\\nroom 2\r\nLOCATION:Main St\r\nEND:VEVENT\r\n"));
        let e = &p.events[0];
        assert_eq!(e.start_ms, ms(2026, 1, 5, 15, 0)); // CST is UTC-6
        assert_eq!(e.duration_ms, 3_600_000);
        assert_eq!(e.summary, "Doctor, dentist\nroom 2");
        assert_eq!((e.uid.as_str(), e.location.as_str(), e.all_day, e.in_window), ("a1", "Main St", false, true));
    }

    #[test]
    fn utc_and_floating_times() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:u\r\nDTSTART:20260105T150000Z\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:f\r\nDTSTART:20260105T090000\r\nEND:VEVENT\r\n"));
        assert_eq!(p.events[0].start_ms, ms(2026, 1, 5, 15, 0));
        assert_eq!(p.events[1].start_ms, ms(2026, 1, 5, 15, 0)); // floating = device zone
        assert_eq!(p.events[0].duration_ms, 0);
    }

    #[test]
    fn all_day_is_one_calendar_day_even_across_dst() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:d\r\nDTSTART;VALUE=DATE:20260105\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:s\r\nDTSTART;VALUE=DATE:20260308\r\nDTEND;VALUE=DATE:20260309\r\nEND:VEVENT\r\n"));
        assert!(p.events[0].all_day);
        assert_eq!(p.events[0].start_ms, ms(2026, 1, 5, 6, 0));
        assert_eq!(p.events[0].duration_ms, 24 * 3_600_000);
        assert_eq!(p.events[1].duration_ms, 23 * 3_600_000); // spring forward in Chicago
    }

    #[test]
    fn weekly_series_honours_exdate_and_edited_instances() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:w\r\nDTSTART;TZID=America/Chicago:20260105T090000\r\nDTEND;TZID=America/Chicago:20260105T093000\r\nRRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=5\r\nEXDATE;TZID=America/Chicago:20260112T090000\r\nSUMMARY:Standup\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:w\r\nRECURRENCE-ID;TZID=America/Chicago:20260119T090000\r\nDTSTART;TZID=America/Chicago:20260119T110000\r\nSUMMARY:Standup (moved)\r\nEND:VEVENT\r\n"));
        let master = p.events.iter().find(|e| e.summary == "Standup").unwrap();
        // Mondays Jan 5, 12, 19, 26, Feb 2 minus the excluded 12th and the edited 19th.
        assert_eq!(master.instances, vec![ms(2026, 1, 5, 15, 0), ms(2026, 1, 26, 15, 0), ms(2026, 2, 2, 15, 0)]);
        let moved = p.events.iter().find(|e| e.summary == "Standup (moved)").unwrap();
        assert!(moved.recurring && moved.instances.is_empty() && moved.in_window);
        assert_eq!(moved.start_ms, ms(2026, 1, 19, 17, 0));
    }

    #[test]
    fn series_wall_clock_survives_dst() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:x\r\nDTSTART;TZID=America/Chicago:20260302T090000\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\nEND:VEVENT\r\n"));
        // Mar 2 is CST (UTC-6); Mar 9 and 16 are CDT (UTC-5): still 09:00 local.
        assert_eq!(p.events[0].instances, vec![ms(2026, 3, 2, 15, 0), ms(2026, 3, 9, 14, 0), ms(2026, 3, 16, 14, 0)]);
    }

    #[test]
    fn yearly_all_day_birthday_lands_in_the_window() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:b\r\nDTSTART;VALUE=DATE:19900315\r\nRRULE:FREQ=YEARLY\r\nSUMMARY:Birthday\r\nEND:VEVENT\r\n"));
        assert_eq!(p.events[0].instances.len(), 1);
        assert_eq!(p.events[0].instances[0], ms(2026, 3, 15, 5, 0)); // midnight CDT
    }

    #[test]
    fn cancelled_events_are_flagged_not_dropped() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:c\r\nDTSTART:20260105T150000Z\r\nSTATUS:CANCELLED\r\nEND:VEVENT\r\n"));
        assert!(p.events[0].cancelled);
    }

    #[test]
    fn folded_lines_and_bare_lf_are_handled() {
        let p = run("BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:l\nDTSTART:20260105T150000Z\nSUMMARY:A very long\n  title that was folded\n\t by a producer\nEND:VEVENT\nEND:VCALENDAR\n");
        assert_eq!(p.events[0].summary, "A very long title that was folded by a producer");
    }

    #[test]
    fn windows_zone_names_resolve_without_fallback() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:o\r\nDTSTART;TZID=\"Central Standard Time\":20260105T090000\r\nEND:VEVENT\r\n"));
        assert_eq!((p.events[0].start_ms, p.tz_fallbacks), (ms(2026, 1, 5, 15, 0), 0));
    }

    #[test]
    fn unknown_zone_falls_back_to_the_device_zone_and_is_counted() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:z\r\nDTSTART;TZID=Not/AZone:20260105T090000\r\nEND:VEVENT\r\n"));
        assert_eq!((p.events[0].start_ms, p.tz_fallbacks), (ms(2026, 1, 5, 15, 0), 1));
    }

    #[test]
    fn alarms_inside_an_event_are_ignored() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:al\r\nDTSTART:20260105T150000Z\r\nSUMMARY:Real\r\nBEGIN:VALARM\r\nSUMMARY:Alarm text\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\nEND:VEVENT\r\n"));
        assert_eq!((p.events.len(), p.events[0].summary.as_str()), (1, "Real"));
    }

    #[test]
    fn events_without_a_usable_start_are_skipped_and_counted() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:n1\r\nSUMMARY:No start\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:n2\r\nDTSTART:99999999T000000Z\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:ok\r\nDTSTART:20260105T150000Z\r\nEND:VEVENT\r\n"));
        assert_eq!((p.events.len(), p.skipped), (1, 2));
    }

    // ----------------------------------------------------------- hostile input

    #[test]
    fn sub_hourly_and_endless_rules_are_refused() {
        for rule in ["FREQ=SECONDLY", "FREQ=MINUTELY;INTERVAL=1", "FREQ=DAILY;COUNT=999999999", "FREQ=BOGUS", "FREQ=HOURLY"] {
            let p = run(&cal(&format!("BEGIN:VEVENT\r\nUID:h\r\nDTSTART:19700101T000000Z\r\nRRULE:{rule}\r\nEND:VEVENT\r\n")));
            assert_eq!((p.events.len(), p.skipped), (0, 1), "{rule}");
        }
    }

    #[test]
    fn a_very_old_daily_series_is_still_cheap() {
        let t = Instant::now();
        let p = run(&cal("BEGIN:VEVENT\r\nUID:old\r\nDTSTART:19000101T090000Z\r\nRRULE:FREQ=DAILY\r\nEND:VEVENT\r\n"));
        assert!(t.elapsed().as_secs() < 2);
        assert!(p.events[0].instances.len() > 150);
    }

    #[test]
    fn many_individually_affordable_series_share_one_work_budget() {
        let one = "BEGIN:VEVENT\nDTSTART:19000101T090000Z\nRRULE:FREQ=DAILY\nEND:VEVENT\n"; // ~46k iterations each
        let t = Instant::now();
        let p = parse(one.repeat(5_000).as_bytes(), win(), CHICAGO);
        assert!(t.elapsed().as_secs() < 3, "{:?}", t.elapsed());
        assert!(p.events.len() < 5_000 && p.skipped > 0, "kept {}, skipped {}", p.events.len(), p.skipped);
    }

    #[test]
    fn rules_that_never_match_cannot_stall_a_feed() {
        let one = "BEGIN:VEVENT\nDTSTART;TZID=America/Chicago:20260815T080000\nRRULE:FREQ=MONTHLY;BYDAY=FR;BYSETPOS=-11\nEND:VEVENT\n";
        let t = Instant::now();
        let p = parse(one.repeat(3_000).as_bytes(), win(), CHICAGO);
        // Even in a slow debug build this stops after the deadline plus one event.
        assert!(t.elapsed().as_secs() < 8, "{:?}", t.elapsed());
        assert!(p.skipped > 0);
    }

    #[test]
    fn instances_per_event_are_capped() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:many\r\nDTSTART:20260101T000000Z\r\nRRULE:FREQ=DAILY;BYHOUR=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23;BYMINUTE=0,5,10,15,20,25,30,35,40,45,50,55\r\nEND:VEVENT\r\n"));
        assert!(p.events.is_empty() || p.events[0].instances.len() <= MAX_INSTANCES as usize);
    }

    #[test]
    fn huge_junk_input_finishes_fast_without_panicking() {
        let t = Instant::now();
        let junk = "X:y\n".repeat(500_000) + &"BEGIN:VEVENT\n".repeat(100_000);
        let _ = parse(junk.as_bytes(), win(), CHICAGO);
        let long = format!("BEGIN:VEVENT\nSUMMARY:{}\nEND:VEVENT\n", "a".repeat(5_000_000));
        let _ = parse(long.as_bytes(), win(), CHICAGO);
        let _ = parse(&[0xff, 0xfe, 0x00, 0x80, b':', b';', b'"', b'\n', b'"'], win(), CHICAGO);
        assert!(t.elapsed().as_secs() < 5, "{:?}", t.elapsed());
    }

    #[test]
    fn oversized_input_is_refused() {
        let big = vec![b'a'; MAX_INPUT + 1];
        let p = parse(&big, win(), CHICAGO);
        assert_eq!((p.events.len(), p.skipped), (0, 1));
    }

    #[test]
    fn event_count_is_capped() {
        let one = "BEGIN:VEVENT\nDTSTART:20260105T150000Z\nEND:VEVENT\n";
        let p = parse(one.repeat(MAX_EVENTS + 50).as_bytes(), win(), CHICAGO);
        assert_eq!((p.events.len(), p.skipped), (MAX_EVENTS, 50));
    }

    #[test]
    fn non_ascii_rule_text_skips_only_that_event() { // fuzzer finding: used to panic inside rrule
        let p = run(&cal("BEGIN:VEVENT\r\nUID:bad\r\nDTSTART:20260105T150000Z\r\nRRULE:FREQ=WEEKLY;BYDAY=S\u{FFFD},SA\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nUID:good\r\nDTSTART:20260105T150000Z\r\nSUMMARY:Still here\r\nEND:VEVENT\r\n"));
        assert_eq!((p.events.len(), p.skipped, p.events[0].summary.as_str()), (1, 1, "Still here"));
    }

    #[test]
    fn json_has_the_shape_kotlin_reads() {
        let p = run(&cal("BEGIN:VEVENT\r\nUID:j\r\nDTSTART:20260105T150000Z\r\nSUMMARY:Hi \"there\"\r\nEND:VEVENT\r\n"));
        let v: Value = serde_json::from_str(&p.to_json()).unwrap();
        let e = &v["events"][0];
        assert_eq!(e["summary"], "Hi \"there\"");
        assert_eq!(e["startMs"], ms(2026, 1, 5, 15, 0));
        assert!(e["recurrenceIdMs"].is_null() && e["instances"].is_array());
        assert_eq!((v["skipped"].as_u64(), v["tzFallbacks"].as_u64()), (Some(0), Some(0)));
    }
}
