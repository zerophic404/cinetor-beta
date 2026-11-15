// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Native download engine (Tauri commands).
//  • progressive: up to 16 parallel HTTP Range connections into one preallocated `<name>.download`
//    file; resume state in a `<name>.cinetor-part` JSON sidecar; renamed to `<name>` when complete.
//  • HLS: media-playlist segments fetched in parallel into `<stem>.cinetor-hls/` (resume = finished
//    segment files), then concatenated into one `.ts`/`.mp4` (no ffmpeg) plus a local `<stem>.m3u8`
//    using EXT-X-BYTERANGE into that file so the web player can seek inside it.
// Progress → `dl://progress`, completion → `dl://done`, failure → `dl://error` events.
use std::collections::HashMap;
use std::io::SeekFrom;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use futures_util::{stream, StreamExt};
use serde::{Deserialize, Serialize};
use tauri::async_runtime::JoinHandle;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::io::{AsyncSeekExt, AsyncWriteExt};
use url::Url;

pub const MAX_CONNECTIONS: usize = 16;
const MIN_CHUNK: u64 = 1024 * 1024;
const UNKNOWN: u64 = u64::MAX;
const RESUME_REWIND: u64 = 64 * 1024;
const MEDIA_TOKEN: &str = "__CINETOR_MEDIA__";

pub struct DlState {
    jobs: Mutex<HashMap<String, (u64, JoinHandle<()>)>>,
    gen: AtomicU64,
    client: reqwest::Client,
}

impl DlState {
    pub fn new() -> Self {
        let client = reqwest::Client::builder()
            .user_agent(concat!("Cinetor/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(Duration::from_secs(20))
            .read_timeout(Duration::from_secs(40))
            .build()
            .unwrap_or_default();
        Self { jobs: Mutex::new(HashMap::new()), gen: AtomicU64::new(1), client }
    }
}

#[derive(Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct StartReq {
    pub id: String,
    pub url: String,
    /// "progressive" | "hls"
    pub kind: String,
    pub connections: Option<usize>,
    pub file_name: String,
    pub bandwidth: Option<u64>,
    pub height: Option<u32>,
    /// Request headers for `url` (gateway CDN: Cookie / Referer / Origin — browsers can't send them).
    #[serde(default)]
    pub headers: Option<HashMap<String, String>>,
    /// Used when `url` (+ headers) fails the first probe (gateway proxy URL, no headers needed).
    #[serde(default)]
    pub fallback_url: Option<String>,
    /// Resume identity independent of re-signed URLs (`t=` changes on every resolve).
    #[serde(default)]
    pub resume_key: Option<String>,
}

/// reqwest client that sends `headers` on every request (unknown/invalid header names are skipped).
pub fn client_with_headers(headers: &HashMap<String, String>) -> Option<reqwest::Client> {
    let mut map = reqwest::header::HeaderMap::new();
    for (k, v) in headers {
        let (Ok(name), Ok(val)) = (reqwest::header::HeaderName::from_bytes(k.as_bytes()), reqwest::header::HeaderValue::from_str(v)) else {
            continue;
        };
        map.insert(name, val);
    }
    reqwest::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
        .default_headers(map)
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(40))
        .build()
        .ok()
}

/// Direct CDN URL with headers when it answers, else the fallback (proxy) with the plain client.
async fn pick_route(plain: &reqwest::Client, req: &StartReq) -> (reqwest::Client, StartReq) {
    let Some(h) = req.headers.as_ref().filter(|h| !h.is_empty()) else {
        return (plain.clone(), req.clone());
    };
    let Some(direct) = client_with_headers(h) else {
        return (plain.clone(), req.clone());
    };
    let Some(fb) = req.fallback_url.clone() else {
        return (direct, req.clone());
    };
    let ok = tokio::time::timeout(Duration::from_secs(12), probe(&direct, &req.url)).await.map(|r| r.is_ok()).unwrap_or(false);
    if ok {
        return (direct, req.clone());
    }
    let mut r2 = req.clone();
    r2.url = fb;
    r2.headers = None;
    (plain.clone(), r2)
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ChunkOut {
    start: u64,
    end: u64,
    pos: u64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Progress {
    id: String,
    done_bytes: u64,
    total_bytes: u64,
    speed: f64,
    chunks: Vec<ChunkOut>,
    range_supported: bool,
    segments_done: usize,
    segments_total: usize,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Done {
    id: String,
    path: String,
    playlist: Option<String>,
    size: u64,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct Failed {
    id: String,
    message: String,
}

// ------------------------------------------------------------------ commands ----

#[tauri::command]
pub async fn dl_start(app: AppHandle, state: State<'_, DlState>, req: StartReq) -> Result<(), String> {
    stop_job(&state, &req.id).await;
    let plain = state.client.clone();
    let id = req.id.clone();
    let gen = state.gen.fetch_add(1, Ordering::SeqCst);
    let app2 = app.clone();
    let handle = tauri::async_runtime::spawn(async move {
        let (client, req) = pick_route(&plain, &req).await;
        let res = if req.kind == "hls" {
            run_hls(&app2, &client, &req).await
        } else {
            run_progressive(&app2, &client, &req).await
        };
        match res {
            Ok(done) => {
                let _ = app2.emit("dl://done", done);
            }
            Err(message) => {
                let _ = app2.emit("dl://error", Failed { id: req.id.clone(), message });
            }
        }
        if let Ok(mut jobs) = app2.state::<DlState>().jobs.lock() {
            if jobs.get(&req.id).map(|(g, _)| *g) == Some(gen) {
                jobs.remove(&req.id);
            }
        }
    });
    if let Ok(mut jobs) = state.jobs.lock() {
        jobs.insert(id, (gen, handle));
    }
    Ok(())
}

/// Pause = abort the job; progress already written stays on disk (sidecar / segment files).
#[tauri::command]
pub async fn dl_pause(state: State<'_, DlState>, id: String) -> Result<(), String> {
    stop_job(&state, &id).await;
    Ok(())
}

/// Delete a download (finished file + partial data + sidecars).
#[tauri::command]
pub async fn dl_remove(app: AppHandle, state: State<'_, DlState>, id: String, file_name: String, path: Option<String>) -> Result<(), String> {
    stop_job(&state, &id).await;
    let dir = out_dir(&app)?;
    let name = sanitize(&file_name);
    let stem = stem_of(&name);
    let mut files = vec![
        dir.join(format!("{name}.download")),
        dir.join(format!("{name}.cinetor-part")),
        dir.join(format!("{stem}.m3u8")),
        dir.join(format!("{stem}.cinetor-part")),
        dir.join(format!("{stem}.ts.download")),
        dir.join(format!("{stem}.mp4.download")),
    ];
    if let Some(p) = path {
        let p = PathBuf::from(p);
        if p.starts_with(&dir) {
            if let Some(s) = p.file_stem() {
                files.push(dir.join(format!("{}.m3u8", s.to_string_lossy())));
            }
            files.push(p);
        }
    }
    for f in files {
        let _ = tokio::fs::remove_file(f).await;
    }
    let _ = tokio::fs::remove_dir_all(dir.join(format!("{stem}.cinetor-hls"))).await;
    Ok(())
}

/// Local byte-range playlist of a finished HLS download (only files inside Downloads/Cinetor).
#[tauri::command]
pub async fn dl_read_playlist(app: AppHandle, path: String) -> Result<String, String> {
    let dir = out_dir(&app)?;
    let p = PathBuf::from(&path);
    if !p.starts_with(&dir) || p.extension().map(|e| e != "m3u8").unwrap_or(true) {
        return Err("not a Cinetor playlist".into());
    }
    tokio::fs::read_to_string(p).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub fn dl_dir(app: AppHandle) -> Result<String, String> {
    out_dir(&app).map(|p| p.to_string_lossy().into_owned())
}

/// Remember a user-chosen download folder (Settings → Downloads). Created if missing.
#[tauri::command]
pub fn dl_set_dir(app: AppHandle, path: String) -> Result<String, String> {
    let p = PathBuf::from(path.trim());
    if !p.is_absolute() {
        return Err("Choose a folder".into());
    }
    std::fs::create_dir_all(&p).map_err(|_| "That folder can't be used".to_string())?;
    save_dir_pref(&app, &p);
    Ok(p.to_string_lossy().into_owned())
}

/// Native folder picker (Tauri dialog); returns the chosen folder (remembered) or None if canceled.
#[tauri::command]
pub async fn dl_pick_dir(app: AppHandle) -> Result<Option<String>, String> {
    use tauri_plugin_dialog::DialogExt;
    let start = out_dir(&app).ok();
    let (tx, rx) = tokio::sync::oneshot::channel();
    let mut dialog = app.dialog().file().set_title("Choose the Cinetor download folder");
    if let Some(s) = start {
        dialog = dialog.set_directory(s);
    }
    dialog.pick_folder(move |p| {
        let _ = tx.send(p);
    });
    let picked = rx.await.map_err(|_| "Folder picker unavailable".to_string())?;
    let Some(fp) = picked else { return Ok(None) };
    let path = fp.into_path().map_err(|_| "That folder can't be used".to_string())?;
    std::fs::create_dir_all(&path).map_err(|_| "That folder can't be used".to_string())?;
    save_dir_pref(&app, &path);
    Ok(Some(path.to_string_lossy().into_owned()))
}

async fn stop_job(state: &DlState, id: &str) {
    let h = state.jobs.lock().ok().and_then(|mut j| j.remove(id));
    if let Some((_, h)) = h {
        h.abort();
        let _ = h.await;
    }
}

// ------------------------------------------------------------------ helpers ----

fn dir_pref_path(app: &AppHandle) -> Option<PathBuf> {
    let dir = app.path().app_config_dir().ok()?;
    let _ = std::fs::create_dir_all(&dir);
    Some(dir.join("download-dir.txt"))
}

fn load_dir_pref(app: &AppHandle) -> Option<PathBuf> {
    let txt = std::fs::read_to_string(dir_pref_path(app)?).ok()?;
    let p = PathBuf::from(txt.trim());
    if p.is_absolute() && p.is_dir() {
        Some(p)
    } else {
        None
    }
}

fn save_dir_pref(app: &AppHandle, p: &Path) {
    if let Some(f) = dir_pref_path(app) {
        let _ = std::fs::write(f, p.to_string_lossy().as_bytes());
    }
}

/// Target folder: the remembered one (Settings → Downloads) else `Downloads/Cinetor`.
fn out_dir(app: &AppHandle) -> Result<PathBuf, String> {
    if let Some(p) = load_dir_pref(app) {
        return Ok(p);
    }
    let base = app
        .path()
        .download_dir()
        .or_else(|_| app.path().home_dir().map(|h| h.join("Downloads")))
        .map_err(|e| e.to_string())?;
    let dir = base.join("Cinetor");
    std::fs::create_dir_all(&dir).map_err(|e| format!("can't create {}: {e}", dir.display()))?;
    Ok(dir)
}

pub fn sanitize(name: &str) -> String {
    let mut s: String = name
        .chars()
        .map(|c| if c.is_alphanumeric() || " -_.()[]".contains(c) { c } else { '_' })
        .collect();
    s = s.trim().trim_matches('.').to_string();
    if s.is_empty() {
        s = "download".into();
    }
    if s.chars().count() > 120 {
        let ext = Path::new(&s).extension().map(|e| e.to_string_lossy().into_owned());
        let mut cut: String = s.chars().take(110).collect();
        if let Some(e) = ext {
            cut.push('.');
            cut.push_str(&e);
        }
        s = cut;
    }
    s
}

fn stem_of(name: &str) -> String {
    Path::new(name).file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or_else(|| name.to_string())
}

/// `name.ext` → first free `name (n).ext` in `dir`.
fn unique_path(dir: &Path, name: &str) -> PathBuf {
    let first = dir.join(name);
    if !first.exists() {
        return first;
    }
    let stem = stem_of(name);
    let ext = Path::new(name).extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
    for n in 1..1000 {
        let p = dir.join(format!("{stem} ({n}){ext}"));
        if !p.exists() {
            return p;
        }
    }
    first
}

fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

/// Split `[0, total)` into ≤ `n` inclusive ranges of ≥ 1 MiB.
pub fn split_ranges(total: u64, n: usize) -> Vec<(u64, u64)> {
    if total == 0 {
        return vec![(0, UNKNOWN)];
    }
    let n = (n.clamp(1, MAX_CONNECTIONS) as u64).min((total / MIN_CHUNK).max(1));
    let size = total.div_ceil(n);
    (0..n).map(|i| (i * size, ((i + 1) * size).min(total) - 1)).filter(|(s, e)| s <= e).collect()
}

/// Exponential moving average speed meter.
struct Meter {
    last_t: Instant,
    last_b: u64,
    speed: f64,
}
impl Meter {
    fn new(b: u64) -> Self {
        Self { last_t: Instant::now(), last_b: b, speed: 0.0 }
    }
    fn sample(&mut self, b: u64) -> f64 {
        let dt = self.last_t.elapsed().as_secs_f64();
        if dt >= 0.25 {
            let inst = (b.saturating_sub(self.last_b)) as f64 / dt;
            self.speed = if self.speed == 0.0 { inst } else { self.speed * 0.6 + inst * 0.4 };
            self.last_t = Instant::now();
            self.last_b = b;
        }
        self.speed
    }
}

// --------------------------------------------------------------- progressive ----

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Sidecar {
    url: String,
    #[serde(default)]
    key: Option<String>,
    total: u64,
    range_supported: bool,
    chunks: Vec<(u64, u64, u64)>,
}

struct ChunkState {
    start: u64,
    end: AtomicU64,
    pos: AtomicU64,
}
impl ChunkState {
    fn done(&self) -> bool {
        let end = self.end.load(Ordering::SeqCst);
        end != UNKNOWN && self.pos.load(Ordering::SeqCst) > end
    }
}

async fn probe(client: &reqwest::Client, url: &str) -> Result<(u64, bool), String> {
    let resp = client.get(url).header(reqwest::header::RANGE, "bytes=0-0").send().await.map_err(err)?;
    let status = resp.status();
    if status.as_u16() == 206 {
        let total = resp
            .headers()
            .get(reqwest::header::CONTENT_RANGE)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.rsplit('/').next())
            .and_then(|v| v.trim().parse::<u64>().ok())
            .unwrap_or(0);
        return Ok((total, total > 0));
    }
    if !status.is_success() {
        return Err(format!("HTTP {status}"));
    }
    Ok((resp.content_length().unwrap_or(0), false))
}

async fn save_sidecar(path: &Path, sc: &Sidecar) {
    if let Ok(json) = serde_json::to_vec(sc) {
        let tmp = path.with_extension("cinetor-part.tmp");
        if tokio::fs::write(&tmp, json).await.is_ok() {
            let _ = tokio::fs::rename(&tmp, path).await;
        }
    }
}

/// Same download: same resume key (gateway, URL re-signed) or same URL.
fn sidecar_matches(s: &Sidecar, req: &StartReq) -> bool {
    match (&s.key, &req.resume_key) {
        (Some(a), Some(b)) => a == b,
        _ => s.url == req.url,
    }
}

fn snapshot(req: &StartReq, total: u64, ranges: bool, chunks: &[Arc<ChunkState>]) -> Sidecar {
    Sidecar {
        url: req.url.clone(),
        key: req.resume_key.clone(),
        total,
        range_supported: ranges,
        chunks: chunks.iter().map(|c| (c.start, c.end.load(Ordering::SeqCst), c.pos.load(Ordering::SeqCst))).collect(),
    }
}

async fn run_progressive(app: &AppHandle, client: &reqwest::Client, req: &StartReq) -> Result<Done, String> {
    let dir = out_dir(app)?;
    let name = sanitize(&req.file_name);
    let data_path = dir.join(format!("{name}.download"));
    let side_path = dir.join(format!("{name}.cinetor-part"));

    let prev: Option<Sidecar> = match tokio::fs::read(&side_path).await {
        Ok(b) if data_path.exists() => serde_json::from_slice::<Sidecar>(&b).ok().filter(|s| sidecar_matches(s, req) && s.range_supported),
        _ => None,
    };
    let (total, ranges, chunks): (u64, bool, Vec<Arc<ChunkState>>) = match prev {
        Some(sc) => {
            let chunks = sc
                .chunks
                .iter()
                .map(|&(start, end, pos)| {
                    // Rewind a little: the last buffered write before an abort may not have landed.
                    let pos = if pos > end { pos } else { pos.saturating_sub(RESUME_REWIND).max(start) };
                    Arc::new(ChunkState { start, end: AtomicU64::new(end), pos: AtomicU64::new(pos) })
                })
                .collect();
            (sc.total, true, chunks)
        }
        None => {
            let (total, ranges) = probe(client, &req.url).await?;
            let n = if ranges { req.connections.unwrap_or(4) } else { 1 };
            let parts = if ranges { split_ranges(total, n) } else { vec![(0, if total > 0 { total - 1 } else { UNKNOWN })] };
            let f = tokio::fs::File::create(&data_path).await.map_err(err)?;
            if total > 0 {
                f.set_len(total).await.map_err(err)?;
            }
            drop(f);
            let chunks = parts.into_iter().map(|(s, e)| Arc::new(ChunkState { start: s, end: AtomicU64::new(e), pos: AtomicU64::new(s) })).collect();
            (total, ranges, chunks)
        }
    };
    if ranges {
        save_sidecar(&side_path, &snapshot(req, total, ranges, &chunks)).await;
    }

    let counter = Arc::new(AtomicU64::new(chunks.iter().map(|c| c.pos.load(Ordering::SeqCst) - c.start).sum()));
    let work = futures_util::future::try_join_all(
        chunks
            .iter()
            .filter(|c| !c.done())
            .map(|c| run_chunk(client, &req.url, &data_path, c.clone(), ranges, counter.clone())),
    );
    tokio::pin!(work);
    let mut meter = Meter::new(counter.load(Ordering::SeqCst));
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    let mut n: u64 = 0;
    let emit = |speed: f64| {
        let out: Vec<ChunkOut> = chunks
            .iter()
            .map(|c| ChunkOut { start: c.start, end: c.end.load(Ordering::SeqCst), pos: c.pos.load(Ordering::SeqCst) })
            .collect();
        let done_bytes = counter.load(Ordering::SeqCst);
        let _ = app.emit(
            "dl://progress",
            Progress {
                id: req.id.clone(),
                done_bytes,
                total_bytes: if total > 0 { total } else { done_bytes },
                speed,
                chunks: out,
                range_supported: ranges,
                segments_done: 0,
                segments_total: 0,
            },
        );
    };
    let result = loop {
        tokio::select! {
            r = &mut work => break r,
            _ = tick.tick() => {
                let s = meter.sample(counter.load(Ordering::SeqCst));
                emit(s);
                n += 1;
                if ranges && n % 4 == 0 {
                    save_sidecar(&side_path, &snapshot(req, total, ranges, &chunks)).await;
                }
            }
        }
    };
    if let Err(e) = result {
        if ranges {
            save_sidecar(&side_path, &snapshot(req, total, ranges, &chunks)).await;
        }
        return Err(e);
    }
    emit(0.0);
    let size = tokio::fs::metadata(&data_path).await.map(|m| m.len()).unwrap_or(total);
    let final_path = unique_path(&dir, &name);
    tokio::fs::rename(&data_path, &final_path).await.map_err(err)?;
    let _ = tokio::fs::remove_file(&side_path).await;
    Ok(Done { id: req.id.clone(), path: final_path.to_string_lossy().into_owned(), playlist: None, size })
}

async fn run_chunk(client: &reqwest::Client, url: &str, path: &Path, c: Arc<ChunkState>, ranges: bool, counter: Arc<AtomicU64>) -> Result<(), String> {
    let mut attempt: u32 = 0;
    loop {
        if c.done() {
            return Ok(());
        }
        match fetch_chunk(client, url, path, &c, ranges, &counter).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                attempt += 1;
                if attempt > 5 {
                    return Err(e);
                }
                if !ranges {
                    // No Range support → can only restart from zero.
                    let pos = c.pos.swap(c.start, Ordering::SeqCst);
                    counter.fetch_sub(pos - c.start, Ordering::SeqCst);
                }
                tokio::time::sleep(Duration::from_millis(500 * 2u64.pow(attempt))).await;
            }
        }
    }
}

async fn fetch_chunk(client: &reqwest::Client, url: &str, path: &Path, c: &ChunkState, ranges: bool, counter: &AtomicU64) -> Result<(), String> {
    let pos = c.pos.load(Ordering::SeqCst);
    let end = c.end.load(Ordering::SeqCst);
    let mut rq = client.get(url);
    if ranges {
        rq = rq.header(reqwest::header::RANGE, format!("bytes={pos}-{end}"));
    }
    let resp = rq.send().await.map_err(err)?;
    let status = resp.status();
    if ranges && status.as_u16() != 206 {
        return Err(format!("HTTP {status} (expected 206 Partial Content)"));
    }
    if !status.is_success() {
        return Err(format!("HTTP {status}"));
    }
    let mut f = tokio::fs::OpenOptions::new().write(true).open(path).await.map_err(err)?;
    f.seek(SeekFrom::Start(pos)).await.map_err(err)?;
    let mut body = resp.bytes_stream();
    let mut at = pos;
    while let Some(b) = body.next().await {
        let b = b.map_err(err)?;
        let take = if end == UNKNOWN { b.len() } else { (b.len() as u64).min(end + 1 - at) as usize };
        f.write_all(&b[..take]).await.map_err(err)?;
        at += take as u64;
        c.pos.store(at, Ordering::SeqCst);
        counter.fetch_add(take as u64, Ordering::SeqCst);
        if end != UNKNOWN && at > end {
            break;
        }
    }
    f.flush().await.map_err(err)?;
    if end == UNKNOWN {
        // Unknown length: the stream ending cleanly = done.
        c.end.store(at.saturating_sub(1), Ordering::SeqCst);
        if at == 0 {
            c.pos.store(1, Ordering::SeqCst);
        }
        return Ok(());
    }
    if at <= end {
        return Err("connection closed early".into());
    }
    Ok(())
}

// ----------------------------------------------------------------------- HLS ----

#[derive(Debug, Clone, PartialEq)]
pub struct Variant {
    pub bandwidth: u64,
    pub height: u32,
    pub uri: String,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MediaPlaylist {
    pub init: Option<String>,
    pub segments: Vec<(f64, String)>,
}

fn attr<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    let pat = format!("{key}=");
    let mut rest = line;
    while let Some(i) = rest.find(&pat) {
        let before_ok = i == 0 || matches!(rest.as_bytes()[i - 1], b',' | b':');
        let v = &rest[i + pat.len()..];
        if before_ok {
            if let Some(q) = v.strip_prefix('"') {
                return q.split('"').next();
            }
            return v.split(',').next();
        }
        rest = v;
    }
    None
}

pub fn parse_master(text: &str) -> Vec<Variant> {
    let mut out = Vec::new();
    let mut pending: Option<(u64, u32)> = None;
    for line in text.lines().map(str::trim) {
        if let Some(rest) = line.strip_prefix("#EXT-X-STREAM-INF:") {
            let bw = attr(rest, "BANDWIDTH").and_then(|v| v.parse().ok()).unwrap_or(0);
            let h = attr(rest, "RESOLUTION").and_then(|v| v.split('x').nth(1)).and_then(|v| v.parse().ok()).unwrap_or(0);
            pending = Some((bw, h));
        } else if !line.is_empty() && !line.starts_with('#') {
            if let Some((bandwidth, height)) = pending.take() {
                out.push(Variant { bandwidth, height, uri: line.to_string() });
            }
        }
    }
    out
}

/// Requested height (closest ≤, else smallest above) → bandwidth cap → highest bandwidth.
pub fn pick_variant(vs: &[Variant], height: Option<u32>, bandwidth: Option<u64>) -> Option<Variant> {
    if vs.is_empty() {
        return None;
    }
    if let Some(h) = height.filter(|h| *h > 0) {
        if let Some(v) = vs.iter().filter(|v| v.height > 0 && v.height <= h).max_by_key(|v| (v.height, v.bandwidth)) {
            return Some(v.clone());
        }
        if let Some(v) = vs.iter().filter(|v| v.height > h).min_by_key(|v| (v.height, v.bandwidth)) {
            return Some(v.clone());
        }
    }
    if let Some(b) = bandwidth.filter(|b| *b > 0) {
        if let Some(v) = vs.iter().filter(|v| v.bandwidth <= b).max_by_key(|v| v.bandwidth) {
            return Some(v.clone());
        }
    }
    vs.iter().max_by_key(|v| v.bandwidth).cloned()
}

pub fn parse_media(text: &str) -> Result<MediaPlaylist, String> {
    let mut init = None;
    let mut segments = Vec::new();
    let mut dur: f64 = 0.0;
    for line in text.lines().map(str::trim) {
        if let Some(rest) = line.strip_prefix("#EXT-X-KEY:") {
            if attr(rest, "METHOD").map(|m| m != "NONE").unwrap_or(false) {
                return Err("Encrypted HLS isn't supported by the native downloader".into());
            }
        } else if line.starts_with("#EXT-X-BYTERANGE") {
            return Err("Byte-range HLS sources aren't supported by the native downloader".into());
        } else if let Some(rest) = line.strip_prefix("#EXT-X-MAP:") {
            init = attr(rest, "URI").map(str::to_string);
        } else if let Some(rest) = line.strip_prefix("#EXTINF:") {
            dur = rest.split(',').next().and_then(|v| v.trim().parse().ok()).unwrap_or(0.0);
        } else if !line.is_empty() && !line.starts_with('#') {
            segments.push((dur, line.to_string()));
            dur = 0.0;
        }
    }
    if segments.is_empty() {
        return Err("Playlist has no segments".into());
    }
    Ok(MediaPlaylist { init, segments })
}

/// Byte-range playlist pointing into the concatenated file (URI = MEDIA_TOKEN, replaced by the web app).
pub fn local_playlist(init_len: Option<u64>, segs: &[(f64, u64, u64)]) -> String {
    let target = segs.iter().map(|s| s.0).fold(1.0_f64, f64::max).ceil() as u64;
    let mut s = format!("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:{target}\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n");
    if let Some(len) = init_len {
        s.push_str(&format!("#EXT-X-MAP:URI=\"{MEDIA_TOKEN}\",BYTERANGE=\"{len}@0\"\n"));
    }
    for (d, off, len) in segs {
        s.push_str(&format!("#EXTINF:{d:.3},\n#EXT-X-BYTERANGE:{len}@{off}\n{MEDIA_TOKEN}\n"));
    }
    s.push_str("#EXT-X-ENDLIST\n");
    s
}

async fn get_text(client: &reqwest::Client, url: &str) -> Result<String, String> {
    let resp = client.get(url).send().await.map_err(err)?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {} for playlist", resp.status()));
    }
    resp.text().await.map_err(err)
}

async fn fetch_to_file(client: &reqwest::Client, url: &str, dest: &Path, counter: &AtomicU64) -> Result<u64, String> {
    let mut attempt: u32 = 0;
    loop {
        let r: Result<u64, String> = async {
            let resp = client.get(url).send().await.map_err(err)?;
            if !resp.status().is_success() {
                return Err(format!("HTTP {} for segment", resp.status()));
            }
            let tmp = dest.with_extension("tmp");
            let mut f = tokio::fs::File::create(&tmp).await.map_err(err)?;
            let mut body = resp.bytes_stream();
            let mut n: u64 = 0;
            while let Some(b) = body.next().await {
                let b = b.map_err(err)?;
                f.write_all(&b).await.map_err(err)?;
                n += b.len() as u64;
                counter.fetch_add(b.len() as u64, Ordering::SeqCst);
            }
            f.flush().await.map_err(err)?;
            drop(f);
            tokio::fs::rename(&tmp, dest).await.map_err(err)?;
            Ok(n)
        }
        .await;
        match r {
            Ok(n) => return Ok(n),
            Err(e) => {
                attempt += 1;
                if attempt > 4 {
                    return Err(e);
                }
                tokio::time::sleep(Duration::from_millis(400 * 2u64.pow(attempt))).await;
            }
        }
    }
}

async fn run_hls(app: &AppHandle, client: &reqwest::Client, req: &StartReq) -> Result<Done, String> {
    let dir = out_dir(app)?;
    let mut base = Url::parse(&req.url).map_err(err)?;
    let mut text = get_text(client, base.as_str()).await?;
    if text.contains("#EXT-X-STREAM-INF") {
        let v = pick_variant(&parse_master(&text), req.height, req.bandwidth).ok_or("No playable variant in master playlist")?;
        base = base.join(&v.uri).map_err(err)?;
        text = get_text(client, base.as_str()).await?;
    }
    let pl = parse_media(&text)?;
    let fmp4 = pl.init.is_some() || pl.segments.first().map(|s| s.1.contains(".m4s") || s.1.contains(".mp4")).unwrap_or(false);
    let ext = if fmp4 { "mp4" } else { "ts" };
    let stem = stem_of(&sanitize(&req.file_name));
    let seg_dir = dir.join(format!("{stem}.cinetor-hls"));
    tokio::fs::create_dir_all(&seg_dir).await.map_err(err)?;
    let side_path = dir.join(format!("{stem}.cinetor-part"));
    let _ = tokio::fs::write(&side_path, serde_json::json!({ "url": req.url, "kind": "hls", "segments": pl.segments.len() }).to_string()).await;

    // (index, url, file) — init segment (fMP4) first.
    let mut items: Vec<(usize, String, PathBuf)> = Vec::new();
    if let Some(init) = &pl.init {
        items.push((usize::MAX, base.join(init).map_err(err)?.to_string(), seg_dir.join("init.bin")));
    }
    for (i, (_, uri)) in pl.segments.iter().enumerate() {
        items.push((i, base.join(uri).map_err(err)?.to_string(), seg_dir.join(format!("seg{i:06}.bin"))));
    }
    let total_items = items.len();
    let counter = Arc::new(AtomicU64::new(0));
    let done_items = Arc::new(AtomicUsize::new(0));
    for (_, _, p) in &items {
        if let Ok(m) = tokio::fs::metadata(p).await {
            counter.fetch_add(m.len(), Ordering::SeqCst);
            done_items.fetch_add(1, Ordering::SeqCst);
        }
    }
    let conns = req.connections.unwrap_or(4).clamp(1, MAX_CONNECTIONS);
    let work = stream::iter(items.clone().into_iter().map(|(_, url, path)| {
        let counter = counter.clone();
        let done_items = done_items.clone();
        async move {
            if tokio::fs::metadata(&path).await.is_ok() {
                return Ok::<(), String>(());
            }
            fetch_to_file(client, &url, &path, &counter).await?;
            done_items.fetch_add(1, Ordering::SeqCst);
            Ok(())
        }
    }))
    .buffer_unordered(conns)
    .collect::<Vec<Result<(), String>>>();
    tokio::pin!(work);
    let mut meter = Meter::new(counter.load(Ordering::SeqCst));
    let mut tick = tokio::time::interval(Duration::from_millis(500));
    let emit = |speed: f64| {
        let bytes = counter.load(Ordering::SeqCst);
        let d = done_items.load(Ordering::SeqCst);
        let est = if d > 0 { bytes / d as u64 * total_items as u64 } else { 0 };
        let _ = app.emit(
            "dl://progress",
            Progress {
                id: req.id.clone(),
                done_bytes: bytes,
                total_bytes: est.max(bytes),
                speed,
                chunks: Vec::new(),
                range_supported: true,
                segments_done: d,
                segments_total: total_items,
            },
        );
    };
    let results = loop {
        tokio::select! {
            r = &mut work => break r,
            _ = tick.tick() => {
                let s = meter.sample(counter.load(Ordering::SeqCst));
                emit(s);
            }
        }
    };
    if let Some(Err(e)) = results.into_iter().find(|r| r.is_err()) {
        return Err(e);
    }
    emit(0.0);

    // Concatenate in playlist order (init first) and record byte offsets.
    let final_path = unique_path(&dir, &format!("{stem}.{ext}"));
    let part_path = dir.join(format!("{stem}.{ext}.download"));
    let mut out = tokio::fs::File::create(&part_path).await.map_err(err)?;
    let mut offset: u64 = 0;
    let mut init_len = None;
    let mut ranges: Vec<(f64, u64, u64)> = Vec::with_capacity(pl.segments.len());
    for (idx, _, path) in &items {
        let mut f = tokio::fs::File::open(path).await.map_err(err)?;
        let len = tokio::io::copy(&mut f, &mut out).await.map_err(err)?;
        if *idx == usize::MAX {
            init_len = Some(len);
        } else {
            ranges.push((pl.segments[*idx].0, offset, len));
        }
        offset += len;
    }
    out.flush().await.map_err(err)?;
    drop(out);
    tokio::fs::rename(&part_path, &final_path).await.map_err(err)?;
    let final_stem = final_path.file_stem().map(|s| s.to_string_lossy().into_owned()).unwrap_or(stem.clone());
    let playlist_path = dir.join(format!("{final_stem}.m3u8"));
    tokio::fs::write(&playlist_path, local_playlist(init_len, &ranges)).await.map_err(err)?;
    let _ = tokio::fs::remove_dir_all(&seg_dir).await;
    let _ = tokio::fs::remove_file(&side_path).await;
    Ok(Done {
        id: req.id.clone(),
        path: final_path.to_string_lossy().into_owned(),
        playlist: Some(playlist_path.to_string_lossy().into_owned()),
        size: offset,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_respects_min_chunk_and_max() {
        assert_eq!(split_ranges(0, 8), vec![(0, UNKNOWN)]);
        assert_eq!(split_ranges(500, 8), vec![(0, 499)]);
        let r = split_ranges(100 * MIN_CHUNK, 64);
        assert_eq!(r.len(), MAX_CONNECTIONS);
        assert_eq!(r.first().unwrap().0, 0);
        assert_eq!(r.last().unwrap().1, 100 * MIN_CHUNK - 1);
        for w in r.windows(2) {
            assert_eq!(w[0].1 + 1, w[1].0);
        }
    }

    #[test]
    fn master_and_pick() {
        let m = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS=\"avc1.4d401f,mp4a.40.2\"\nmid.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhi.m3u8\n";
        let v = parse_master(m);
        assert_eq!(v.len(), 3);
        assert_eq!(pick_variant(&v, Some(720), None).unwrap().uri, "mid.m3u8");
        assert_eq!(pick_variant(&v, Some(240), None).unwrap().uri, "low.m3u8");
        assert_eq!(pick_variant(&v, None, Some(3_000_000)).unwrap().uri, "mid.m3u8");
        assert_eq!(pick_variant(&v, None, None).unwrap().uri, "hi.m3u8");
    }

    #[test]
    fn media_parse_and_local_playlist() {
        let t = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\na.m4s\n#EXTINF:4.5,\nb.m4s\n#EXT-X-ENDLIST\n";
        let p = parse_media(t).unwrap();
        assert_eq!(p.init.as_deref(), Some("init.mp4"));
        assert_eq!(p.segments, vec![(6.0, "a.m4s".into()), (4.5, "b.m4s".into())]);
        assert!(parse_media("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:1,\na.ts\n").is_err());
        let lp = local_playlist(Some(10), &[(6.0, 10, 100), (4.5, 110, 50)]);
        assert!(lp.contains("#EXT-X-MAP:URI=\"__CINETOR_MEDIA__\",BYTERANGE=\"10@0\""));
        assert!(lp.contains("#EXT-X-BYTERANGE:50@110"));
        assert!(lp.ends_with("#EXT-X-ENDLIST\n"));
    }

    #[test]
    fn headers_client_and_sidecar_key() {
        let mut h = HashMap::new();
        h.insert("Referer".to_string(), "https://movieboxhd.net/".to_string());
        h.insert("Cookie".to_string(), "a=b".to_string());
        h.insert("bad header".to_string(), "x".to_string());
        assert!(client_with_headers(&h).is_some());
        let req = StartReq {
            id: "x".into(),
            url: "https://cdn/a.mp4?t=2".into(),
            kind: "progressive".into(),
            connections: None,
            file_name: "a.mp4".into(),
            bandwidth: None,
            height: None,
            headers: Some(h),
            fallback_url: Some("https://gw/play".into()),
            resume_key: Some("x|720".into()),
        };
        let sc = Sidecar { url: "https://cdn/a.mp4?t=1".into(), key: Some("x|720".into()), total: 10, range_supported: true, chunks: vec![] };
        assert!(sidecar_matches(&sc, &req));
        let old = Sidecar { url: "https://cdn/a.mp4?t=1".into(), key: None, total: 10, range_supported: true, chunks: vec![] };
        assert!(!sidecar_matches(&old, &req));
    }

    #[test]
    fn sanitize_names() {
        assert_eq!(sanitize("a/b:c?.mp4"), "a_b_c_.mp4");
        assert_eq!(sanitize("  "), "download");
    }
}
