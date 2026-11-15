// Mini-player mode: the main window shrinks to a 480x270 (16:9) always-on-top window in the
// bottom-right corner of the current monitor; leaving restores the previous bounds.
use std::sync::Mutex;

use tauri::{Emitter, LogicalSize, PhysicalPosition, PhysicalSize, State, WebviewWindow};

const MINI_W: f64 = 480.0;
const MINI_H: f64 = 270.0;
const MARGIN: f64 = 16.0;
pub const MIN_W: f64 = 820.0;
pub const MIN_H: f64 = 520.0;

#[derive(Clone, Copy)]
struct Saved {
    pos: PhysicalPosition<i32>,
    size: PhysicalSize<u32>,
    maximized: bool,
}

#[derive(Default)]
pub struct MiniState(Mutex<Option<Saved>>);

#[tauri::command]
pub fn is_mini_mode(state: State<'_, MiniState>) -> bool {
    state.0.lock().map(|s| s.is_some()).unwrap_or(false)
}

#[tauri::command]
pub fn set_mini_mode(window: WebviewWindow, state: State<'_, MiniState>, enabled: bool) -> Result<bool, String> {
    if enabled {
        enter(&window, &state)?;
    } else {
        restore(&window, &state);
    }
    let on = state.0.lock().map(|s| s.is_some()).unwrap_or(false);
    let _ = window.emit("mini-mode", on);
    Ok(on)
}

fn enter(w: &WebviewWindow, state: &MiniState) -> Result<(), String> {
    let mut guard = state.0.lock().map_err(|e| e.to_string())?;
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
    drop(guard);

    let monitor = w
        .current_monitor()
        .map_err(|e| e.to_string())?
        .or_else(|| w.primary_monitor().ok().flatten())
        .ok_or_else(|| "no monitor".to_string())?;
    let scale = monitor.scale_factor();
    let area = monitor.work_area();
    let mw = (MINI_W * scale).round() as i32;
    let mh = (MINI_H * scale).round() as i32;
    let margin = (MARGIN * scale).round() as i32;
    let x = area.position.x + area.size.width as i32 - mw - margin;
    let y = area.position.y + area.size.height as i32 - mh - margin;

    let _ = w.set_min_size(None::<tauri::Size>);
    w.set_size(PhysicalSize::new(mw as u32, mh as u32)).map_err(|e| e.to_string())?;
    w.set_position(PhysicalPosition::new(x, y)).map_err(|e| e.to_string())?;
    w.set_always_on_top(true).map_err(|e| e.to_string())?;
    Ok(())
}

/// Leave mini mode (no-op when not in it).
pub fn restore(w: &WebviewWindow, state: &MiniState) {
    let saved = match state.0.lock() {
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
