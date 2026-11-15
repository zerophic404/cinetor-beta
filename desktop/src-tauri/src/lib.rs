// This app is the complete property of Mythbrix. Do not touch, retrieve, reverse-engineer,
// scrape or hack anything without Mythbrix's explicit permission.
// Cinetor desktop shell: frameless window (glass title bar drawn by the web app), Windows Mica/Acrylic,
// single instance, remembered window size, mini-player mode and the native download engine.
mod dl;
mod md;
mod mini;

use tauri::Manager;

pub fn run() {
    tauri::Builder::default()
        // Must be first: a second launch focuses the running window instead of opening another.
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(w) = app.get_webview_window("main") {
                let _ = w.unminimize();
                let _ = w.show();
                let _ = w.set_focus();
            }
        }))
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_dialog::init())
        .manage(dl::DlState::new())
        .manage(mini::MiniState::default())
        .invoke_handler(tauri::generate_handler![
            mini::set_mini_mode,
            mini::is_mini_mode,
            dl::dl_start,
            dl::dl_pause,
            dl::dl_remove,
            dl::dl_read_playlist,
            dl::dl_dir,
            dl::dl_set_dir,
            dl::dl_pick_dir,
            md::md_get,
            vibrancy_kind
        ])
        .setup(|app| {
            let win = app.get_webview_window("main").expect("main window");
            apply_vibrancy(&win);
            let _ = win.show();
            Ok(())
        })
        .on_window_event(|window, event| {
            let app = window.app_handle();
            let state = app.state::<mini::MiniState>();
            match event {
                // Mini mode stays 16:9 and remembers its bounds.
                tauri::WindowEvent::Resized(_) | tauri::WindowEvent::Moved(_) => {
                    if let Some(w) = app.get_webview_window(window.label()) {
                        mini::on_bounds_changed(&w, &state);
                    }
                }
                // Never persist the mini size as the remembered full-window size.
                tauri::WindowEvent::CloseRequested { .. } => {
                    if let Some(w) = app.get_webview_window(window.label()) {
                        mini::restore(&w, &state);
                    }
                }
                _ => {}
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Cinetor");
}

/// Which backdrop the native side applied ("mica" | "acrylic" | "none") → web picks transparent vs opaque CSS.
#[tauri::command]
fn vibrancy_kind(state: tauri::State<'_, VibrancyKind>) -> String {
    state.0.to_string()
}

pub struct VibrancyKind(pub &'static str);

#[cfg(target_os = "windows")]
fn apply_vibrancy(win: &tauri::WebviewWindow) {
    use window_vibrancy::{apply_acrylic, apply_mica};
    // Mica = Windows 11; Acrylic = Windows 10 1809+; otherwise opaque.
    let kind = if apply_mica(win, Some(true)).is_ok() {
        "mica"
    } else if apply_acrylic(win, Some((10, 10, 12, 160))).is_ok() {
        "acrylic"
    } else {
        "none"
    };
    win.app_handle().manage(VibrancyKind(kind));
}

#[cfg(not(target_os = "windows"))]
fn apply_vibrancy(win: &tauri::WebviewWindow) {
    // Linux (WebKitGTK): opaque window, the web app draws its own glass.
    win.app_handle().manage(VibrancyKind("none"));
}
