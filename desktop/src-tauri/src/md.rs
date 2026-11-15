// Comics metadata bridge: the webview cannot call api.mangadex.org directly (MangaDex only echoes
// CORS headers for allow-listed origins), so the desktop app proxies those JSON GETs through Rust
// with a non-browser user agent. Host-allow-listed; returns the body as a string.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
use std::time::Duration;

use url::Url;

const ALLOWED_HOSTS: &[&str] = &["api.mangadex.org", "uploads.mangadex.org"];
const DEFAULT_UA: &str = "CinetorApp/1.0";

/// https + host allow-list (no redirects to other hosts, no local addresses).
pub fn allowed(raw: &str) -> bool {
    let Ok(u) = Url::parse(raw) else { return false };
    if u.scheme() != "https" {
        return false;
    }
    match u.host_str() {
        Some(h) => ALLOWED_HOSTS.iter().any(|a| h.eq_ignore_ascii_case(a)) || h.to_ascii_lowercase().ends_with(".mangadex.network"),
        None => false,
    }
}

/// GET a comics metadata URL as text. Errors are generic (never leak the URL or host).
#[tauri::command]
pub async fn md_get(url: String, ua: Option<String>) -> Result<String, String> {
    if !allowed(&url) {
        return Err("Comics source not available".into());
    }
    let client = reqwest::Client::builder()
        .user_agent(ua.unwrap_or_else(|| DEFAULT_UA.to_string()))
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(25))
        .build()
        .map_err(|_| "Comics source not available".to_string())?;
    let res = client
        .get(&url)
        .header("accept", "application/json")
        .send()
        .await
        .map_err(|_| "Comics source not available".to_string())?;
    if !res.status().is_success() {
        return Err("Comics source not available".into());
    }
    res.text().await.map_err(|_| "Comics source not available".to_string())
}

#[cfg(test)]
mod tests {
    use super::allowed;

    #[test]
    fn only_https_mangadex_hosts_pass() {
        assert!(allowed("https://api.mangadex.org/manga?limit=1"));
        assert!(allowed("https://uploads.mangadex.org/covers/x/y.512.jpg"));
        assert!(allowed("https://cmdxd98sb0x3yprd.mangadex.network/data/hash/1.png"));
        assert!(!allowed("http://api.mangadex.org/manga"));
        assert!(!allowed("https://evil.example/api.mangadex.org"));
        assert!(!allowed("https://mangadex.network.evil.com/x"));
        assert!(!allowed("not a url"));
    }
}
