// Mini-player mode: the main window shrinks to a small always-on-top window in the corner of the
// current monitor. It stays RESIZABLE (16:9 enforced, 25–80 % of the work area) and the last mini
// size/position is remembered across toggles and restarts; leaving restores the previous bounds.
//
// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer, scrape
// or hack anything without Mythbrix's explicit permission.
use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use tauri::{Emitter, LogicalSize, Manager, PhysicalPosition, PhysicalSize, State, WebviewWindow};

const MINI_W: f64 = 480.0;
const MARGIN: f64 = 16.0;
pub const MIN_W: f64 = 820.0;
pub const MIN_H: f64 = 520.0;
/// Mini window limits, as a fraction of the monitor work-area width.
const MINI_MIN_FRAC: f64 = 0.25;
const MINI_MAX_FRAC: f64 = 0.80;
const MINI_MIN_PX: f64 = 240.0;

#[derive(Clone, Copy)]
struct Saved {
    pos: PhysicalPosition<i32>,
    size: PhysicalSize<u32>,
    maximized: bool,
}

/// Remembered mini bounds (physical pixels), persisted in the app config dir.
#[derive(Clone, Copy, Serialize, Deserialize, Debug, PartialEq)]
pub struct MiniBounds {
    pub x: i32,
    pub y: i32,
    pub w: u32,
    pub h: u32,
}

#[derive(Default)]
pub struct MiniState {
    saved: Mutex<Option<Saved>>,
    bounds: Mutex<Option<MiniBounds>>,
}

impl MiniState {
    pub fn active(&self) -> bool {
        self.saved.lock().map(|s| s.is_some()).unwrap_or(false)
    }
}

/// 16:9 size for a requested width, clamped to the work area (pure — unit-tested).
pub fn mini_size(width: f64, area_w: f64) -> (f64, f64) {
    let min = (area_w * MINI_MIN_FRAC).max(MINI_MIN_PX).min(area_w.max(MINI_MIN_PX));
    let max = (area_w * MINI_MAX_FRAC).max(min);
    let w = width.clamp(min, max).round();
    (w, (w * 9.0 / 16.0).round())
}

/// Keep the mini window inside the work area (pure — unit-tested).
pub fn clamp_pos(x: i32, y: i32, w: u32, h: u32, area: (i32, i32, u32, u32)) -> (i32, i32) {
    let (ax, ay, aw, ah) = area;
    let max_x = (ax + aw as i32 - w as i32).max(ax);
    let max_y = (ay + ah as i32 - h as i32).max(ay);
    (x.clamp(ax, max_x), y.clamp(ay, max_y))
}

fn store_path(app: &tauri::AppHandle) -> Option<PathBuf> {
    let dir = app.path().app_config_dir().ok()?;
    let _ = fs::create_dir_all(&dir);
    Some(dir.join("mini-window.json"))
}

fn load_bounds(app: &tauri::AppHandle) -> Option<MiniBounds> {
    let txt = fs::read_to_string(store_path(app)?).ok()?;
    serde_json::from_str::<MiniBounds>(&txt).ok()
}

fn save_bounds(app: &tauri::AppHandle, b: MiniBounds) {
    if let Some(p) = store_path(app) {
        if let Ok(txt) = serde_json::to_string(&b) {
            let _ = fs::write(p, txt);
        }
    }
}

#[tauri::command]
pub fn is_mini_mode(state: State<'_, MiniState>) -> bool {
    state.active()
}

#[tauri::command]
pub fn set_mini_mode(window: WebviewWindow, state: State<'_, MiniState>, enabled: bool) -> Result<bool, String> {
    if enabled {
        enter(&window, &state)?;
    } else {
        restore(&window, &state);
    }
    let on = state.active();
    let _ = window.emit("mini-mode", on);
    Ok(on)
}

fn enter(w: &WebviewWindow, state: &MiniState) -> Result<(), String> {
    {
        let mut guard = state.saved.lock().map_err(|e| e.to_string())?;
        if guard.is_some() {
            return Ok(());
        }
        let maximized = w.is_maximized().unwrap_or(false);
        if w.is_fullscreen().unwrap_or(false) {
            let _ = w.set_fullscreen(false);
        }
        if maximized {
            let _ = w.unmaximize();
        }
        let pos = w.outer_position().map_err(|e| e.to_string())?;
        let size = w.inner_size().map_err(|e| e.to_string())?;
        *guard = Some(Saved { pos, size, maximized });
    }

    let monitor = w
        .current_monitor()
        .map_err(|e| e.to_string())?
        .or_else(|| w.primary_monitor().ok().flatten())
        .ok_or_else(|| "no monitor".to_string())?;
    let scale = monitor.scale_factor();
    let area = monitor.work_area();
    let area_t = (area.position.x, area.position.y, area.size.width, area.size.height);

    // A remembered size/position (this session or a previous run) wins over the default corner.
    let remembered = state.bounds.lock().ok().and_then(|g| *g).or_else(|| load_bounds(w.app_handle()));
    let (mw, mh, x, y) = match remembered {
        Some(b) => {
            let (cw, ch) = mini_size(b.w as f64, area.size.width as f64);
            let (x, y) = clamp_pos(b.x, b.y, cw as u32, ch as u32, area_t);
            (cw as i32, ch as i32, x, y)
        }
        None => {
            let (cw, ch) = mini_size(MINI_W * scale, area.size.width as f64);
            let margin = (MARGIN * scale).round() as i32;
            let x = area.position.x + area.size.width as i32 - cw as i32 - margin;
            let y = area.position.y + area.size.height as i32 - ch as i32 - margin;
            (cw as i32, ch as i32, x, y)
        }
    };

    let _ = w.set_min_size(None::<tauri::Size>);
    let _ = w.set_resizable(true);
    w.set_size(PhysicalSize::new(mw as u32, mh as u32)).map_err(|e| e.to_string())?;
    w.set_position(PhysicalPosition::new(x, y)).map_err(|e| e.to_string())?;
    w.set_always_on_top(true).map_err(|e| e.to_string())?;
    remember(w, state, MiniBounds { x, y, w: mw as u32, h: mh as u32 });
    Ok(())
}

fn remember(w: &WebviewWindow, state: &MiniState, b: MiniBounds) {
    if let Ok(mut g) = state.bounds.lock() {
        *g = Some(b);
    }
    save_bounds(w.app_handle(), b);
}

/// The window was resized/moved while in mini mode: keep 16:9 and remember the new bounds.
pub fn on_bounds_changed(w: &WebviewWindow, state: &MiniState) {
    if !state.active() {
        return;
    }
    let Ok(size) = w.inner_size() else { return };
    let Ok(pos) = w.outer_position() else { return };
    let area_w = w
        .current_monitor()
        .ok()
        .flatten()
        .map(|m| m.work_area().size.width as f64)
        .unwrap_or((size.width as f64) * 2.0);
    let (cw, ch) = mini_size(size.width as f64, area_w);
    if (cw as u32, ch as u32) != (size.width, size.height) {
        let _ = w.set_size(PhysicalSize::new(cw as u32, ch as u32));
    }
    remember(w, state, MiniBounds { x: pos.x, y: pos.y, w: cw as u32, h: ch as u32 });
}

/// Leave mini mode (no-op when not in it).
pub fn restore(w: &WebviewWindow, state: &MiniState) {
    let saved = match state.saved.lock() {
        Ok(mut g) => g.take(),
        Err(_) => None,
    };
    let Some(s) = saved else { return };
    let _ = w.set_always_on_top(false);
    let _ = w.set_size(s.size);
    let _ = w.set_position(s.pos);
    let _ = w.set_min_size(Some(LogicalSize::new(MIN_W, MIN_H)));
    if s.maximized {
        let _ = w.maximize();
    }
}

#[cfg(test)]
mod tests {
    use super::{clamp_pos, mini_size};

    #[test]
    fn mini_size_keeps_16_9_within_25_to_80_percent() {
        assert_eq!(mini_size(480.0, 1920.0), (480.0, 270.0));
        // Clamped at 25 % / 80 % of the work area.
        assert_eq!(mini_size(100.0, 1920.0), (480.0, 270.0));
        assert_eq!(mini_size(5000.0, 1920.0), (1536.0, 864.0));
        // Tiny monitors still get at least 240 px.
        assert_eq!(mini_size(10.0, 800.0), (240.0, 135.0));
    }

    #[test]
    fn resized_mini_window_stays_on_screen() {
        let area = (0, 0, 1920, 1080);
        assert_eq!(clamp_pos(100, 100, 480, 270, area), (100, 100));
        assert_eq!(clamp_pos(1900, 1070, 480, 270, area), (1440, 810));
        assert_eq!(clamp_pos(-50, -80, 480, 270, area), (0, 0));
    }
}
