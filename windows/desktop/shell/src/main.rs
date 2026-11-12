//! Codio for Windows: the desktop window.
//!
//! One window holds two layers: the mpv player draws into a child window at the bottom, and
//! the Codio interface (WebView2, transparent while a video plays) sits on top of it. The
//! interface is served by codio-web.js on 127.0.0.1:11471, which runs on the bundled Node.js
//! (codio-runtime.exe). There is no streaming server: http(s) sources go straight to mpv.
//!
//! The interface talks to this window with JSON messages over `chrome.webview`:
//!   from the page: {"type": 3} once at start, then {"type": 6, "args": [method, ...]}
//!   to the page:   {"type": 3, "data": {"transport": {"properties": [...]}}} and
//!                  {"type": 1, "args": [signal, data]}

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod mpv;

use serde_json::{json, Value};
use std::cell::RefCell;
use std::collections::HashSet;
use std::net::TcpStream;
use std::os::windows::io::AsRawHandle;
use std::os::windows::process::CommandExt;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use webview2_com::Microsoft::Web::WebView2::Win32::*;
use webview2_com::*;
use windows::core::{w, Interface, PCWSTR, PWSTR};
use windows::Win32::Foundation::*;
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::System::Com::{CoInitializeEx, COINIT_APARTMENTTHREADED};
use windows::Win32::System::JobObjects::*;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::CreateMutexW;
use windows::Win32::UI::HiDpi::*;
use windows::Win32::UI::Input::KeyboardAndMouse::EnableWindow;
use windows::Win32::UI::Shell::ShellExecuteW;
use windows::Win32::UI::WindowsAndMessaging::*;

const VERSION: &str = env!("CARGO_PKG_VERSION");
const WEB_PORT: u16 = 11471;
const WEB_URL: &str = "http://127.0.0.1:11471/";
const MAIN_CLASS: PCWSTR = w!("CodioMainWindow");
const VIDEO_CLASS: PCWSTR = w!("CodioVideoWindow");
const WM_OUTGOING: u32 = WM_APP + 1;
const MIN_WIDTH: i32 = 1000;
const MIN_HEIGHT: i32 = 600;

/// Messages waiting to be posted to the page (filled from the player thread).
type Outbox = Arc<Mutex<Vec<String>>>;

struct App {
    window: HWND,
    video: HWND,
    controller: Option<ICoreWebView2Controller>,
    webview: Option<ICoreWebView2>,
    mpv: Option<Arc<mpv::Mpv>>,
    outbox: Outbox,
    observed: HashSet<String>,
    next_observe_id: u64,
    fullscreen: Option<(isize, WINDOWPLACEMENT)>,
}

thread_local! {
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
}

fn with_app<R>(f: impl FnOnce(&mut App) -> R) -> Option<R> {
    APP.with(|app| app.try_borrow_mut().ok().and_then(|mut app| app.as_mut().map(f)))
}

fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

fn message_box(text: &str) {
    let text = wide(text);
    unsafe { MessageBoxW(None, PCWSTR(text.as_ptr()), w!("Codio"), MB_OK | MB_ICONERROR) };
}

fn main() {
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    if focus_running_instance() {
        return;
    }
    start_interface_server();
    if let Err(error) = run() {
        message_box(&format!("Codio couldn't start.\n\n{error}"));
    }
}

/// Only one Codio at a time: a second start brings the open window forward.
fn focus_running_instance() -> bool {
    unsafe {
        let mutex = CreateMutexW(None, false, w!("Local\\CodioDesktopSingleInstance"));
        if mutex.is_ok() && GetLastError() == ERROR_ALREADY_EXISTS {
            if let Ok(window) = FindWindowW(MAIN_CLASS, PCWSTR::null()) {
                if IsIconic(window).as_bool() {
                    let _ = ShowWindow(window, SW_RESTORE);
                }
                let _ = SetForegroundWindow(window);
            }
            return true;
        }
        // The mutex handle stays open for the life of the process.
        std::mem::forget(mutex);
    }
    false
}

/// Starts codio-web.js (unless it is already answering) and waits until it answers.
/// It runs in a job that closes with Codio, so it never outlives the window.
fn start_interface_server() {
    let up = || TcpStream::connect_timeout(&([127, 0, 0, 1], WEB_PORT).into(), Duration::from_millis(200)).is_ok();
    if up() {
        return;
    }
    let mut dir = std::env::current_exe().unwrap_or_default();
    dir.pop();
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    match std::process::Command::new(dir.join("codio-runtime.exe"))
        .arg(dir.join("codio-web.js"))
        .current_dir(&dir)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
    {
        Ok(child) => unsafe {
            if let Ok(job) = CreateJobObjectW(None, PCWSTR::null()) {
                let mut limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
                limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                let _ = SetInformationJobObject(
                    job,
                    JobObjectExtendedLimitInformation,
                    &limits as *const _ as *const _,
                    std::mem::size_of_val(&limits) as u32,
                );
                let _ = AssignProcessToJobObject(job, HANDLE(child.as_raw_handle()));
                // The job handle is never closed: it closes when Codio exits, which stops
                // the interface server with it.
                let _ = job;
            }
            std::mem::forget(child);
        },
        Err(error) => message_box(&format!("Codio couldn't start its interface.\n\n{error}")),
    }
    for _ in 0..200 {
        if up() {
            return;
        }
        std::thread::sleep(Duration::from_millis(25));
    }
}

fn run() -> std::result::Result<(), String> {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        let instance: HINSTANCE = GetModuleHandleW(None).map_err(|e| e.to_string())?.into();
        let icon = LoadIconW(Some(instance), PCWSTR(1 as _)).unwrap_or_default();
        let black = HBRUSH(GetStockObject(BLACK_BRUSH).0);

        let main_class = WNDCLASSW {
            style: CS_HREDRAW | CS_VREDRAW,
            lpfnWndProc: Some(main_proc),
            hInstance: instance,
            hIcon: icon,
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            hbrBackground: black,
            lpszClassName: MAIN_CLASS,
            ..Default::default()
        };
        let video_class = WNDCLASSW {
            lpfnWndProc: Some(video_proc),
            hInstance: instance,
            hbrBackground: black,
            lpszClassName: VIDEO_CLASS,
            ..Default::default()
        };
        RegisterClassW(&main_class);
        RegisterClassW(&video_class);

        let (x, y, width, height) = initial_placement();
        let window = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            MAIN_CLASS,
            w!("Codio"),
            WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN,
            x,
            y,
            width,
            height,
            None,
            None,
            Some(instance),
            None,
        )
        .map_err(|e| e.to_string())?;

        // The player's layer. Disabled, so clicks and keys always reach the interface.
        let video = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            VIDEO_CLASS,
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_DISABLED,
            0,
            0,
            width,
            height,
            Some(window),
            None,
            Some(instance),
            None,
        )
        .map_err(|e| e.to_string())?;
        let _ = EnableWindow(video, false);

        let outbox: Outbox = Arc::new(Mutex::new(Vec::new()));
        APP.with(|app| {
            *app.borrow_mut() = Some(App {
                window,
                video,
                controller: None,
                webview: None,
                mpv: None,
                outbox: outbox.clone(),
                observed: HashSet::new(),
                next_observe_id: 1,
                fullscreen: None,
            })
        });

        let _ = ShowWindow(window, if was_maximized() { SW_SHOWMAXIMIZED } else { SW_SHOWNORMAL });
        let _ = UpdateWindow(window);

        match mpv::Mpv::new(video.0 as isize) {
            Ok(player) => {
                start_player_events(player.clone(), outbox.clone(), window.0 as isize);
                with_app(|app| app.mpv = Some(player));
            }
            Err(error) => message_box(&format!("Codio's video player couldn't start. Videos won't play.\n\n{error}")),
        }

        create_webview(window).map_err(|e| format!("The interface (WebView2) couldn't start: {e}"))?;
        resize();

        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }

        // Stop the player before the process goes away.
        let player = with_app(|app| app.mpv.take()).flatten();
        if let Some(player) = player {
            let _ = player.command(&["quit".into()]);
            player.wakeup();
        }
    }
    Ok(())
}

fn create_webview(window: HWND) -> webview2_com::Result<()> {
    let environment = {
        let (tx, rx) = std::sync::mpsc::channel();
        let options = CoreWebView2EnvironmentOptions::default();
        unsafe { options.set_additional_browser_arguments("--autoplay-policy=no-user-gesture-required".to_string()) };
        let options: ICoreWebView2EnvironmentOptions = options.into();
        CreateCoreWebView2EnvironmentCompletedHandler::wait_for_async_operation(
            Box::new(move |handler| unsafe {
                // No user data folder given: WebView2 keeps it in "Codio.exe.WebView2" next to
                // Codio.exe, where earlier Codio versions kept the sign-in and settings too.
                CreateCoreWebView2EnvironmentWithOptions(PCWSTR::null(), PCWSTR::null(), &options, &handler)
                    .map_err(webview2_com::Error::WindowsError)
            }),
            Box::new(move |error, environment| {
                error?;
                tx.send(environment.ok_or_else(|| windows::core::Error::from(E_POINTER))).ok();
                Ok(())
            }),
        )?;
        rx.recv().map_err(|_| webview2_com::Error::SendError)??
    };

    let controller = {
        let (tx, rx) = std::sync::mpsc::channel();
        CreateCoreWebView2ControllerCompletedHandler::wait_for_async_operation(
            Box::new(move |handler| unsafe {
                environment
                    .CreateCoreWebView2Controller(window, &handler)
                    .map_err(webview2_com::Error::WindowsError)
            }),
            Box::new(move |error, controller| {
                error?;
                tx.send(controller.ok_or_else(|| windows::core::Error::from(E_POINTER))).ok();
                Ok(())
            }),
        )?;
        rx.recv().map_err(|_| webview2_com::Error::SendError)??
    };

    unsafe {
        // Transparent, so the video shows through wherever the page is transparent.
        if let Ok(controller2) = controller.cast::<ICoreWebView2Controller2>() {
            let _ = controller2.SetDefaultBackgroundColor(COREWEBVIEW2_COLOR { A: 0, R: 0, G: 0, B: 0 });
        }
        let webview = controller.CoreWebView2()?;
        let settings = webview.Settings()?;
        let _ = settings.SetIsStatusBarEnabled(false);
        let _ = settings.SetIsZoomControlEnabled(false);
        let _ = settings.SetAreDefaultContextMenusEnabled(cfg!(debug_assertions));
        let _ = settings.SetAreDevToolsEnabled(cfg!(debug_assertions) || std::env::var_os("CODIO_DEVTOOLS").is_some());
        if let Ok(settings3) = settings.cast::<ICoreWebView2Settings3>() {
            let _ = settings3.SetAreBrowserAcceleratorKeysEnabled(false);
        }

        let mut token = 0i64;
        webview.add_WebMessageReceived(
            &WebMessageReceivedEventHandler::create(Box::new(|_, args| {
                if let Some(args) = args {
                    let mut text = PWSTR::null();
                    if args.TryGetWebMessageAsString(&mut text).is_ok() {
                        on_page_message(&take_pwstr(text));
                    }
                }
                Ok(())
            })),
            &mut token,
        )?;
        // Links that would open a new window go to the default browser instead.
        webview.add_NewWindowRequested(
            &NewWindowRequestedEventHandler::create(Box::new(|_, args| {
                if let Some(args) = args {
                    let mut uri = PWSTR::null();
                    if args.Uri(&mut uri).is_ok() {
                        open_in_browser(&take_pwstr(uri));
                    }
                    let _ = args.SetHandled(true);
                }
                Ok(())
            })),
            &mut token,
        )?;
        // The window only ever shows Codio's own interface.
        webview.add_NavigationStarting(
            &NavigationStartingEventHandler::create(Box::new(|_, args| {
                if let Some(args) = args {
                    let mut uri = PWSTR::null();
                    if args.Uri(&mut uri).is_ok() {
                        let uri = take_pwstr(uri);
                        if !uri.starts_with(WEB_URL) {
                            let _ = args.SetCancel(true);
                            open_in_browser(&uri);
                        }
                    }
                }
                Ok(())
            })),
            &mut token,
        )?;

        // Debug builds only: CODIO_TEST_SCRIPT names a script to run in the page, for testing.
        #[cfg(debug_assertions)]
        if let Some(script) = std::env::var_os("CODIO_TEST_SCRIPT").and_then(|path| std::fs::read_to_string(path).ok()) {
            let script = wide(&script);
            let _ = webview.AddScriptToExecuteOnDocumentCreated(
                PCWSTR(script.as_ptr()),
                &AddScriptToExecuteOnDocumentCreatedCompletedHandler::create(Box::new(|_, _| Ok(()))),
            );
        }

        let url = wide(WEB_URL);
        webview.Navigate(PCWSTR(url.as_ptr()))?;
        let _ = controller.SetIsVisible(true);
        let _ = controller.MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
        with_app(|app| {
            app.controller = Some(controller);
            app.webview = Some(webview);
        });
    }
    Ok(())
}

fn open_in_browser(url: &str) {
    if url.starts_with("http://") || url.starts_with("https://") {
        let url = wide(url);
        unsafe { ShellExecuteW(None, w!("open"), PCWSTR(url.as_ptr()), PCWSTR::null(), PCWSTR::null(), SW_SHOWNORMAL) };
    }
}

/// Sends a message to the page now (UI thread only).
fn post_to_page(message: &str) {
    let webview = with_app(|app| app.webview.clone()).flatten();
    if let Some(webview) = webview {
        let message = wide(message);
        unsafe {
            let _ = webview.PostWebMessageAsString(PCWSTR(message.as_ptr()));
        }
    }
}

fn signal(name: &str, data: Value) -> String {
    json!({ "id": 0, "type": 1, "object": "transport", "args": [name, data] }).to_string()
}

fn on_page_message(text: &str) {
    let Ok(message) = serde_json::from_str::<Value>(text) else { return };
    match message.get("type").and_then(Value::as_i64) {
        Some(3) => {
            let property = |name: &str, value: &str| json!(["0", name, "", value]);
            let reply = json!({
                "id": message.get("id").cloned().unwrap_or(json!(0)),
                "type": 3,
                "object": "transport",
                "data": { "transport": { "properties": [
                    property("shellVersion", VERSION),
                    property("gpuVideoProcessing", "false"),
                    property("nativeAssSubtitles", "false"),
                    property("cacheDirectoryPicker", "false"),
                    property("nativeInterfaceScale", "false"),
                ], "signals": [], "methods": [] } }
            });
            post_to_page(&reply.to_string());
        }
        Some(6) => {
            let Some(args) = message.get("args").and_then(Value::as_array) else { return };
            let Some(method) = args.first().and_then(Value::as_str) else { return };
            on_method(method, args.get(1).unwrap_or(&Value::Null));
        }
        _ => {}
    }
}

fn as_text(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        Value::Bool(flag) => if *flag { "yes".into() } else { "no".into() },
        other => other.to_string(),
    }
}

fn on_method(method: &str, arg: &Value) {
    let player = with_app(|app| app.mpv.clone()).flatten();
    match method {
        "mpv-command" => {
            if let (Some(player), Some(args)) = (player, arg.as_array()) {
                let args: Vec<String> = args.iter().map(as_text).collect();
                if let Err(error) = player.command(&args) {
                    eprintln!("mpv command {args:?}: {error}");
                }
            }
        }
        "mpv-set-prop" => {
            if let (Some(player), Some(pair)) = (player, arg.as_array()) {
                if let (Some(name), Some(value)) = (pair.first().and_then(Value::as_str), pair.get(1)) {
                    if let Err(error) = player.set_property(name, value) {
                        eprintln!("mpv set {error}");
                    }
                }
            }
        }
        "mpv-observe-prop" => {
            if let (Some(player), Some(name)) = (player, arg.as_str()) {
                let id = with_app(|app| {
                    if app.observed.insert(name.to_string()) {
                        app.next_observe_id += 1;
                        Some(app.next_observe_id)
                    } else {
                        None
                    }
                })
                .flatten();
                match id {
                    // mpv sends the current value right after observing starts.
                    Some(id) => player.observe(name, id),
                    // Already observed: a new player screen still needs the current value.
                    None => {
                        let value = player.get_property(name);
                        post_to_page(&signal("mpv-prop-change", json!({ "name": name, "data": value })));
                    }
                }
            }
        }
        "win-set-visibility" => {
            if let Some(fullscreen) = arg.get("fullscreen").and_then(Value::as_bool) {
                set_fullscreen(fullscreen);
            }
        }
        "discord-connect" => post_to_page(&signal("discord-status", json!(false))),
        "quit" => {
            let window = with_app(|app| app.window);
            if let Some(window) = window {
                unsafe {
                    let _ = PostMessageW(Some(window), WM_CLOSE, WPARAM(0), LPARAM(0));
                }
            }
        }
        // Not needed by Codio: app-ready, media keys, Discord activity, updater, pickers.
        _ => {}
    }
}

/// Player events, read on their own thread and handed to the UI thread for the page.
fn start_player_events(player: Arc<mpv::Mpv>, outbox: Outbox, window: isize) {
    std::thread::spawn(move || loop {
        let message = match player.wait_event(-1.0) {
            mpv::Event::Shutdown => break,
            mpv::Event::Property { name, value } => signal("mpv-prop-change", json!({ "name": name, "data": value })),
            mpv::Event::EndFile { reason, error } => match error {
                Some(error) => signal(
                    "mpv-event-ended",
                    json!({ "reason": reason, "error": { "code": 83, "message": format!("The video couldn't be played ({error})"), "critical": true } }),
                ),
                None => signal("mpv-event-ended", json!({ "reason": reason })),
            },
            mpv::Event::FileLoaded | mpv::Event::Other => continue,
        };
        outbox.lock().unwrap().push(message);
        unsafe {
            let _ = PostMessageW(Some(HWND(window as _)), WM_OUTGOING, WPARAM(0), LPARAM(0));
        }
    });
}

fn flush_outbox() {
    let messages = with_app(|app| std::mem::take(&mut *app.outbox.lock().unwrap())).unwrap_or_default();
    for message in messages {
        post_to_page(&message);
    }
}

fn resize() {
    let parts = with_app(|app| (app.window, app.video, app.controller.clone()));
    let Some((window, video, controller)) = parts else { return };
    unsafe {
        let mut rect = RECT::default();
        if GetClientRect(window, &mut rect).is_err() {
            return;
        }
        let _ = SetWindowPos(video, Some(HWND_BOTTOM), 0, 0, rect.right, rect.bottom, SWP_NOACTIVATE);
        if let Some(controller) = controller {
            let _ = controller.SetBounds(rect);
        }
    }
}

fn set_fullscreen(fullscreen: bool) {
    let Some(window) = with_app(|app| app.window) else { return };
    unsafe {
        let saved = with_app(|app| app.fullscreen.take()).flatten();
        if fullscreen && saved.is_none() {
            let style = GetWindowLongPtrW(window, GWL_STYLE);
            let mut placement = WINDOWPLACEMENT { length: std::mem::size_of::<WINDOWPLACEMENT>() as u32, ..Default::default() };
            let _ = GetWindowPlacement(window, &mut placement);
            let mut monitor = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
            let _ = GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &mut monitor);
            SetWindowLongPtrW(window, GWL_STYLE, style & !(WS_OVERLAPPEDWINDOW.0 as isize));
            let area = monitor.rcMonitor;
            let _ = SetWindowPos(
                window,
                Some(HWND_TOP),
                area.left,
                area.top,
                area.right - area.left,
                area.bottom - area.top,
                SWP_FRAMECHANGED | SWP_NOOWNERZORDER,
            );
            with_app(|app| app.fullscreen = Some((style, placement)));
        } else if !fullscreen {
            if let Some((style, placement)) = saved {
                SetWindowLongPtrW(window, GWL_STYLE, style);
                let _ = SetWindowPlacement(window, &placement);
                let _ = SetWindowPos(window, None, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
            }
        } else {
            with_app(|app| app.fullscreen = saved);
        }
    }
    resize();
    let is_fullscreen = with_app(|app| app.fullscreen.is_some()).unwrap_or(false);
    post_to_page(&signal("win-visibility-changed", json!({ "visible": true, "visibility": 1, "isFullscreen": is_fullscreen })));
}

// Window size and position are kept in %APPDATA%\Codio\window.json between starts.
fn settings_path() -> std::path::PathBuf {
    let mut path = std::path::PathBuf::from(std::env::var_os("APPDATA").unwrap_or_default());
    path.push("Codio");
    path.push("window.json");
    path
}

fn saved_settings() -> Value {
    std::fs::read_to_string(settings_path()).ok().and_then(|text| serde_json::from_str(&text).ok()).unwrap_or(Value::Null)
}

fn initial_placement() -> (i32, i32, i32, i32) {
    let saved = saved_settings();
    let get = |key: &str| saved.get(key).and_then(Value::as_i64).map(|value| value as i32);
    if let (Some(x), Some(y), Some(w), Some(h)) = (get("x"), get("y"), get("width"), get("height")) {
        let visible = unsafe {
            let point = POINT { x: x + 40, y: y + 20 };
            !MonitorFromPoint(point, MONITOR_DEFAULTTONULL).is_invalid()
        };
        if visible && w >= MIN_WIDTH && h >= MIN_HEIGHT {
            return (x, y, w, h);
        }
    }
    (CW_USEDEFAULT, CW_USEDEFAULT, 1280, 800)
}

fn was_maximized() -> bool {
    saved_settings().get("maximized").and_then(Value::as_bool).unwrap_or(false)
}

fn save_placement(window: HWND) {
    unsafe {
        if with_app(|app| app.fullscreen.is_some()).unwrap_or(false) {
            return;
        }
        let mut placement = WINDOWPLACEMENT { length: std::mem::size_of::<WINDOWPLACEMENT>() as u32, ..Default::default() };
        if GetWindowPlacement(window, &mut placement).is_err() {
            return;
        }
        let rect = placement.rcNormalPosition;
        let settings = json!({
            "x": rect.left, "y": rect.top,
            "width": rect.right - rect.left, "height": rect.bottom - rect.top,
            "maximized": placement.showCmd == SW_SHOWMAXIMIZED.0 as u32,
        });
        let path = settings_path();
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let _ = std::fs::write(path, settings.to_string());
    }
}

unsafe extern "system" fn main_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        WM_SIZE => {
            if wparam.0 as u32 != SIZE_MINIMIZED {
                resize();
            }
            LRESULT(0)
        }
        WM_MOVE | WM_MOVING => {
            let controller = with_app(|app| app.controller.clone()).flatten();
            if let Some(controller) = controller {
                let _ = controller.NotifyParentWindowPositionChanged();
            }
            DefWindowProcW(window, message, wparam, lparam)
        }
        WM_ACTIVATE => {
            if (wparam.0 & 0xFFFF) as u32 != WA_INACTIVE {
                let controller = with_app(|app| app.controller.clone()).flatten();
                if let Some(controller) = controller {
                    let _ = controller.MoveFocus(COREWEBVIEW2_MOVE_FOCUS_REASON_PROGRAMMATIC);
                }
            }
            LRESULT(0)
        }
        WM_GETMINMAXINFO => {
            let info = &mut *(lparam.0 as *mut MINMAXINFO);
            let dpi = GetDpiForWindow(window).max(96) as i32;
            info.ptMinTrackSize.x = MIN_WIDTH * dpi / 96;
            info.ptMinTrackSize.y = MIN_HEIGHT * dpi / 96;
            LRESULT(0)
        }
        WM_DPICHANGED => {
            let rect = &*(lparam.0 as *const RECT);
            let _ = SetWindowPos(window, None, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top, SWP_NOZORDER | SWP_NOACTIVATE);
            LRESULT(0)
        }
        WM_OUTGOING => {
            flush_outbox();
            LRESULT(0)
        }
        WM_CLOSE => {
            save_placement(window);
            let _ = DestroyWindow(window);
            LRESULT(0)
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            LRESULT(0)
        }
        WM_ERASEBKGND => LRESULT(1),
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

unsafe extern "system" fn video_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        // Clicks fall through to the window underneath; the interface on top handles input.
        WM_NCHITTEST => LRESULT(HTTRANSPARENT as isize),
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}
