//! A small binding to libmpv's client API (libmpv-2.dll, loaded at run time).
//!
//! Only what Codio needs: create a player that draws into a window, run commands, set and
//! observe properties, and read events. Property values travel as JSON for the interface.

use serde_json::{Map, Number, Value};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::Arc;

const FORMAT_NONE: c_int = 0;
const FORMAT_STRING: c_int = 1;
const FORMAT_FLAG: c_int = 3;
const FORMAT_INT64: c_int = 4;
const FORMAT_DOUBLE: c_int = 5;
const FORMAT_NODE: c_int = 6;
const FORMAT_NODE_ARRAY: c_int = 7;
const FORMAT_NODE_MAP: c_int = 8;

pub const EVENT_NONE: c_int = 0;
pub const EVENT_SHUTDOWN: c_int = 1;
pub const EVENT_END_FILE: c_int = 7;
pub const EVENT_FILE_LOADED: c_int = 8;
pub const EVENT_PROPERTY_CHANGE: c_int = 22;

#[repr(C)]
#[derive(Clone, Copy)]
union NodeValue {
    string: *mut c_char,
    flag: c_int,
    int64: i64,
    double: f64,
    list: *mut NodeList,
}

#[repr(C)]
struct Node {
    u: NodeValue,
    format: c_int,
}

#[repr(C)]
struct NodeList {
    num: c_int,
    values: *mut Node,
    keys: *mut *mut c_char,
}

#[repr(C)]
struct RawEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[repr(C)]
struct RawEventProperty {
    name: *const c_char,
    format: c_int,
    data: *mut c_void,
}

#[repr(C)]
struct RawEventEndFile {
    reason: c_int,
    error: c_int,
}

type Handle = *mut c_void;

struct Api {
    create: unsafe extern "C" fn() -> Handle,
    initialize: unsafe extern "C" fn(Handle) -> c_int,
    terminate_destroy: unsafe extern "C" fn(Handle),
    set_option_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int,
    set_property: unsafe extern "C" fn(Handle, *const c_char, c_int, *mut c_void) -> c_int,
    set_property_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int,
    get_property: unsafe extern "C" fn(Handle, *const c_char, c_int, *mut c_void) -> c_int,
    command: unsafe extern "C" fn(Handle, *mut *const c_char) -> c_int,
    observe_property: unsafe extern "C" fn(Handle, u64, *const c_char, c_int) -> c_int,
    wait_event: unsafe extern "C" fn(Handle, f64) -> *mut RawEvent,
    wakeup: unsafe extern "C" fn(Handle),
    free_node_contents: unsafe extern "C" fn(*mut Node),
    error_string: unsafe extern "C" fn(c_int) -> *const c_char,
}

/// What the player reports back.
pub enum Event {
    Property { name: String, value: Value },
    EndFile { reason: &'static str, error: Option<String> },
    FileLoaded,
    Shutdown,
    Other,
}

pub struct Mpv {
    api: Api,
    handle: Handle,
}

// libmpv's client API is thread-safe: every function may be called from any thread.
unsafe impl Send for Mpv {}
unsafe impl Sync for Mpv {}

fn cstring(text: &str) -> CString {
    CString::new(text.replace('\0', "")).unwrap_or_default()
}

impl Mpv {
    /// Loads libmpv-2.dll from the folder Codio.exe is in and creates a player that draws
    /// into the window `wid`.
    pub fn new(wid: isize) -> Result<Arc<Mpv>, String> {
        let api = unsafe { load_api()? };
        let handle = unsafe { (api.create)() };
        if handle.is_null() {
            return Err("libmpv could not create a player".into());
        }
        let mpv = Mpv { api, handle };
        let wid = wid.to_string();
        for (name, value) in [
            ("wid", wid.as_str()),
            ("title", "Codio"),
            ("audio-client-name", "Codio"),
            ("idle", "yes"),
            ("keep-open", "no"),
            ("terminal", "no"),
            ("osc", "no"),
            ("input-default-bindings", "no"),
            ("input-vo-keyboard", "no"),
            ("input-cursor", "no"),
            ("cursor-autohide", "no"),
            ("hwdec", "auto"),
            ("config", "no"),
        ] {
            mpv.set_option(name, value);
        }
        let status = unsafe { (mpv.api.initialize)(mpv.handle) };
        if status < 0 {
            return Err(format!("libmpv could not start: {}", mpv.error_text(status)));
        }
        Ok(Arc::new(mpv))
    }

    fn error_text(&self, code: c_int) -> String {
        unsafe {
            let text = (self.api.error_string)(code);
            if text.is_null() {
                format!("error {code}")
            } else {
                CStr::from_ptr(text).to_string_lossy().into_owned()
            }
        }
    }

    pub fn set_option(&self, name: &str, value: &str) {
        let (name, value) = (cstring(name), cstring(value));
        unsafe { (self.api.set_option_string)(self.handle, name.as_ptr(), value.as_ptr()) };
    }

    /// Runs a command given as strings, for example ["loadfile", url].
    pub fn command(&self, args: &[String]) -> Result<(), String> {
        let owned: Vec<CString> = args.iter().map(|arg| cstring(arg)).collect();
        let mut pointers: Vec<*const c_char> = owned.iter().map(|arg| arg.as_ptr()).collect();
        pointers.push(std::ptr::null());
        let status = unsafe { (self.api.command)(self.handle, pointers.as_mut_ptr()) };
        if status < 0 {
            Err(self.error_text(status))
        } else {
            Ok(())
        }
    }

    /// Sets a property from a JSON value (true/false, a number or text).
    pub fn set_property(&self, name: &str, value: &Value) -> Result<(), String> {
        let cname = cstring(name);
        let status = unsafe {
            match value {
                Value::Bool(flag) => {
                    let mut flag: c_int = *flag as c_int;
                    (self.api.set_property)(self.handle, cname.as_ptr(), FORMAT_FLAG, &mut flag as *mut _ as *mut c_void)
                }
                Value::Number(number) if number.is_i64() => {
                    let mut int = number.as_i64().unwrap_or_default();
                    (self.api.set_property)(self.handle, cname.as_ptr(), FORMAT_INT64, &mut int as *mut _ as *mut c_void)
                }
                Value::Number(number) => {
                    let mut double = number.as_f64().unwrap_or_default();
                    (self.api.set_property)(self.handle, cname.as_ptr(), FORMAT_DOUBLE, &mut double as *mut _ as *mut c_void)
                }
                Value::String(text) => {
                    let text = cstring(text);
                    (self.api.set_property_string)(self.handle, cname.as_ptr(), text.as_ptr())
                }
                Value::Null => return Ok(()),
                other => {
                    let text = cstring(&other.to_string());
                    (self.api.set_property_string)(self.handle, cname.as_ptr(), text.as_ptr())
                }
            }
        };
        if status < 0 {
            Err(format!("{name}: {}", self.error_text(status)))
        } else {
            Ok(())
        }
    }

    /// Reads a property as JSON (null when it has no value right now).
    pub fn get_property(&self, name: &str) -> Value {
        let cname = cstring(name);
        let mut node = Node { u: NodeValue { int64: 0 }, format: FORMAT_NONE };
        let status = unsafe {
            (self.api.get_property)(self.handle, cname.as_ptr(), FORMAT_NODE, &mut node as *mut _ as *mut c_void)
        };
        if status < 0 {
            return Value::Null;
        }
        let value = unsafe { node_to_json(&node) };
        unsafe { (self.api.free_node_contents)(&mut node) };
        value
    }

    pub fn observe(&self, name: &str, id: u64) {
        let cname = cstring(name);
        unsafe { (self.api.observe_property)(self.handle, id, cname.as_ptr(), FORMAT_NODE) };
    }

    /// Waits for the next event (call from one thread only).
    pub fn wait_event(&self, timeout: f64) -> Event {
        unsafe {
            let event = (self.api.wait_event)(self.handle, timeout);
            if event.is_null() {
                return Event::Other;
            }
            let event = &*event;
            match event.event_id {
                EVENT_SHUTDOWN => Event::Shutdown,
                EVENT_FILE_LOADED => Event::FileLoaded,
                EVENT_PROPERTY_CHANGE if !event.data.is_null() => {
                    let property = &*(event.data as *const RawEventProperty);
                    let name = CStr::from_ptr(property.name).to_string_lossy().into_owned();
                    let value = if property.format == FORMAT_NODE && !property.data.is_null() {
                        node_to_json(&*(property.data as *const Node))
                    } else {
                        Value::Null
                    };
                    Event::Property { name, value }
                }
                EVENT_END_FILE if !event.data.is_null() => {
                    let end = &*(event.data as *const RawEventEndFile);
                    let reason = match end.reason {
                        0 => "eof",
                        2 => "stop",
                        3 => "quit",
                        4 => "error",
                        5 => "redirect",
                        _ => "other",
                    };
                    let error = (end.reason == 4).then(|| self.error_text(end.error));
                    Event::EndFile { reason, error }
                }
                EVENT_NONE => Event::Other,
                _ => Event::Other,
            }
        }
    }

    /// Makes a waiting `wait_event` return.
    pub fn wakeup(&self) {
        unsafe { (self.api.wakeup)(self.handle) };
    }
}

impl Drop for Mpv {
    fn drop(&mut self) {
        unsafe { (self.api.terminate_destroy)(self.handle) };
    }
}

unsafe fn node_to_json(node: &Node) -> Value {
    match node.format {
        FORMAT_STRING => {
            if node.u.string.is_null() {
                Value::Null
            } else {
                Value::String(CStr::from_ptr(node.u.string).to_string_lossy().into_owned())
            }
        }
        FORMAT_FLAG => Value::Bool(node.u.flag != 0),
        FORMAT_INT64 => Value::Number(node.u.int64.into()),
        FORMAT_DOUBLE => Number::from_f64(node.u.double).map(Value::Number).unwrap_or(Value::Null),
        FORMAT_NODE_ARRAY | FORMAT_NODE_MAP => {
            let list = node.u.list;
            if list.is_null() {
                return Value::Null;
            }
            let list = &*list;
            let count = list.num.max(0) as usize;
            let values = if list.values.is_null() || count == 0 {
                &[][..]
            } else {
                std::slice::from_raw_parts(list.values, count)
            };
            if node.format == FORMAT_NODE_ARRAY {
                Value::Array(values.iter().map(|value| node_to_json(value)).collect())
            } else {
                let mut map = Map::new();
                for (index, value) in values.iter().enumerate() {
                    let key = *list.keys.add(index);
                    if !key.is_null() {
                        map.insert(CStr::from_ptr(key).to_string_lossy().into_owned(), node_to_json(value));
                    }
                }
                Value::Object(map)
            }
        }
        _ => Value::Null,
    }
}

unsafe fn load_api() -> Result<Api, String> {
    use windows::core::{s, PCWSTR};
    use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

    let mut path = std::env::current_exe().map_err(|error| error.to_string())?;
    path.pop();
    path.push("libmpv-2.dll");
    let wide: Vec<u16> = path.as_os_str().encode_wide_null();
    let module = LoadLibraryW(PCWSTR(wide.as_ptr())).map_err(|error| format!("Cannot load libmpv-2.dll: {error}"))?;

    macro_rules! function {
        ($name:literal) => {{
            let address = GetProcAddress(module, s!($name)).ok_or(concat!("libmpv-2.dll has no ", $name))?;
            std::mem::transmute::<_, _>(address)
        }};
    }
    Ok(Api {
        create: function!("mpv_create"),
        initialize: function!("mpv_initialize"),
        terminate_destroy: function!("mpv_terminate_destroy"),
        set_option_string: function!("mpv_set_option_string"),
        set_property: function!("mpv_set_property"),
        set_property_string: function!("mpv_set_property_string"),
        get_property: function!("mpv_get_property"),
        command: function!("mpv_command"),
        observe_property: function!("mpv_observe_property"),
        wait_event: function!("mpv_wait_event"),
        wakeup: function!("mpv_wakeup"),
        free_node_contents: function!("mpv_free_node_contents"),
        error_string: function!("mpv_error_string"),
    })
}

trait EncodeWideNull {
    fn encode_wide_null(&self) -> Vec<u16>;
}

impl EncodeWideNull for std::ffi::OsStr {
    fn encode_wide_null(&self) -> Vec<u16> {
        use std::os::windows::ffi::OsStrExt;
        self.encode_wide().chain(std::iter::once(0)).collect()
    }
}
