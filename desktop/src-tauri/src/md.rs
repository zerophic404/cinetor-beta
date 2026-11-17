// Comics metadata bridge: the webview cannot call api.mangadex.org directly (MangaDex only echoes
// CORS headers for allow-listed origins), so the desktop app proxies those JSON GETs through Rust
// with a non-browser user agent, connection pooling, burst throttling, and strict host allow-listing.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
use std::sync::OnceLock;
use std::time::Duration;

use tokio::sync::Semaphore;
use url::Url;

const ALLOWED_HOSTS: &[&str] = &["api.mangadex.org", "uploads.mangadex.org"];
const ALLOWED_SUFFIX: &str = ".mangadex.network";
const DEFAULT_UA: &str = "CinetorApp/1.0";
const MAX_CONCURRENT: usize = 4;
const MAX_BODY_BYTES: usize = 4 * 1024 * 1024;
const ERR_UNAVAILABLE: &str = "Comics source not available";

struct BridgePool {
    client: reqwest::Client,
    gate: Semaphore,
}

static POOL: OnceLock<BridgePool> = OnceLock::new();

fn pool() -> &'static BridgePool {
    POOL.get_or_init(|| {
        let redirect = reqwest::redirect::Policy::custom(|attempt| {
            if attempt.previous().len() >= 3 || !allowed(attempt.url().as_str()) {
                attempt.stop()
            } else {
                attempt.follow()
            }
        });
        let client = reqwest::Client::builder()
            .user_agent(DEFAULT_UA)
            .redirect(redirect)
            .connect_timeout(Duration::from_secs(12))
            .read_timeout(Duration::from_secs(25))
            .pool_idle_timeout(Duration::from_secs(60))
            .build()
            .unwrap_or_default();
        BridgePool {
            client,
            gate: Semaphore::new(MAX_CONCURRENT),
        }
    })
}

/// Sanitize caller-supplied User-Agent: strip control chars, cap length, never send empty UA.
pub fn sanitize_ua(raw: Option<&str>) -> String {
    let cleaned: String = raw
        .unwrap_or("")
        .chars()
        .filter(|c| !c.is_control())
        .take(128)
        .collect::<String>()
        .trim()
        .to_string();
    if cleaned.is_empty() {
        DEFAULT_UA.to_string()
    } else {
        cleaned
    }
}

/// https + default port + no credentials + strict MangaDex host allow-list (no local addresses).
pub fn allowed(raw: &str) -> bool {
    let Ok(u) = Url::parse(raw) else { return false };
    if u.scheme() != "https" || !u.username().is_empty() || u.password().is_some() || u.port().is_some() {
        return false;
    }
    match u.host_str() {
        Some(h) => {
            if ALLOWED_HOSTS.iter().any(|a| h.eq_ignore_ascii_case(a)) {
                return true;
            }
            let lower = h.to_ascii_lowercase();
            lower.len() > ALLOWED_SUFFIX.len() && lower.ends_with(ALLOWED_SUFFIX)
        }
        None => false,
    }
}

/// GET a comics metadata URL as text. Errors are generic (never leak the URL or host).
#[tauri::command]
pub async fn md_get(url: String, ua: Option<String>) -> Result<String, String> {
    if !allowed(&url) {
        return Err(ERR_UNAVAILABLE.into());
    }
    let user_agent = sanitize_ua(ua.as_deref());
    let bridge = pool();
    let _permit = bridge.gate.acquire().await.map_err(|_| ERR_UNAVAILABLE.to_string())?;

    for attempt in 0..2u8 {
        let send_res = bridge
            .client
            .get(&url)
            .header(reqwest::header::USER_AGENT, &user_agent)
            .header(reqwest::header::ACCEPT, "application/json")
            .send()
            .await;

        match send_res {
            Ok(res) => {
                let status = res.status();
                if status.is_success() {
                    if res.content_length().is_some_and(|len| len > MAX_BODY_BYTES as u64) {
                        return Err(ERR_UNAVAILABLE.into());
                    }
                    let bytes = res.bytes().await.map_err(|_| ERR_UNAVAILABLE.to_string())?;
                    if bytes.len() > MAX_BODY_BYTES {
                        return Err(ERR_UNAVAILABLE.into());
                    }
                    return String::from_utf8(bytes.to_vec()).map_err(|_| ERR_UNAVAILABLE.to_string());
                }
                if attempt == 0 && (status.as_u16() == 429 || status.is_server_error()) {
                    tokio::time::sleep(Duration::from_millis(650)).await;
                    continue;
                }
                return Err(ERR_UNAVAILABLE.into());
            }
            Err(_) if attempt == 0 => {
                tokio::time::sleep(Duration::from_millis(450)).await;
            }
            Err(_) => return Err(ERR_UNAVAILABLE.into()),
        }
    }
    Err(ERR_UNAVAILABLE.into())
}

#[cfg(test)]
mod tests {
    use super::{allowed, sanitize_ua};

    #[test]
    fn only_https_mangadex_hosts_pass() {
        assert!(allowed("https://api.mangadex.org/manga?limit=1"));
        assert!(allowed("https://API.MANGADEX.ORG/at-home/server/abc"));
        assert!(allowed("https://uploads.mangadex.org/covers/x/y.512.jpg"));
        assert!(allowed("https://cmdxd98sb0x3yprd.mangadex.network/data/hash/1.png"));
        assert!(!allowed("https://.mangadex.network/data/hash/1.png"));
        assert!(!allowed("https://mangadex.network/data/hash/1.png"));
        assert!(!allowed("http://api.mangadex.org/manga"));
        assert!(!allowed("https://api.mangadex.org:8443/manga"));
        assert!(!allowed("https://user:pass@api.mangadex.org/manga"));
        assert!(!allowed("https://evil.example/api.mangadex.org"));
        assert!(!allowed("https://mangadex.network.evil.com/x"));
        assert!(!allowed("https://127.0.0.1/manga"));
        assert!(!allowed("not a url"));
    }

    #[test]
    fn sanitizes_user_agent_safely() {
        assert_eq!(sanitize_ua(None), "CinetorApp/1.0");
        assert_eq!(sanitize_ua(Some("   ")), "CinetorApp/1.0");
        assert_eq!(sanitize_ua(Some("Cinetor/2.0\r\nX-Injected: 1")), "Cinetor/2.0X-Injected: 1");
        assert_eq!(sanitize_ua(Some("CinetorApp/1.0")), "CinetorApp/1.0");
    }
}
