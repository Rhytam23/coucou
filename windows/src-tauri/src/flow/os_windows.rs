// Flow dictation on Windows: the key, through a low-level keyboard hook (it
// only watches, it never swallows a key) and, beside it, raw input; the paste,
// through SendInput; and the app in front, from the foreground window's program.
//
// Both sources report the same key presses and are merged by state (see
// `edge`): another program's own keyboard hook can swallow a key before this
// one's hook sees it (a hook installed later runs first), but raw input still
// arrives, so the dictation key keeps working next to such programs.

use std::sync::atomic::{AtomicU32, AtomicU8, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use windows::core::{w, PCWSTR, PWSTR};
use windows::Win32::Foundation::{CloseHandle, HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::{
    GetCurrentThreadId, OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION,
};
use windows::Win32::UI::Input::{
    GetRawInputData, RegisterRawInputDevices, HRAWINPUT, RAWINPUT, RAWINPUTDEVICE, RAWINPUTHEADER, RIDEV_INPUTSINK,
    RID_INPUT, RIM_TYPEKEYBOARD,
};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYBD_EVENT_FLAGS, KEYEVENTF_KEYUP,
    VIRTUAL_KEY,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, CreateWindowExW, DestroyWindow, DispatchMessageW, EnumWindows, HWND_MESSAGE, WINDOW_EX_STYLE,
    WINDOW_STYLE, WM_INPUT, GetForegroundWindow, GetMessageW, GetWindowTextLengthW,
    GetWindowThreadProcessId, IsWindowVisible, PostThreadMessageW, SetWindowsHookExW, TranslateMessage,
    UnhookWindowsHookEx, KBDLLHOOKSTRUCT, LLKHF_INJECTED, MSG, WH_KEYBOARD_LL, WM_KEYDOWN, WM_KEYUP, WM_QUIT,
    WM_SYSKEYDOWN, WM_SYSKEYUP,
};

use super::logic::FlowKey;
use super::KeyEvent;

const VK_ESCAPE: u32 = 0x1B;
const VK_V: u16 = 0x56;
const VK_CONTROL: u16 = 0x11;
const VK_SHIFT_KEY: u16 = 0x10;
const VK_LSHIFT: u32 = 0xA0;
const VK_RSHIFT: u32 = 0xA1;
const VK_LCONTROL: u32 = 0xA2;
const VK_RCONTROL: u32 = 0xA3;
const VK_LMENU: u32 = 0xA4;
const VK_RMENU: u32 = 0xA5;
const VK_LWIN: u32 = 0x5B;
const VK_RWIN: u32 = 0x5C;

/// Modifier keys never count as "another key": AltGr even sends a fake left
/// Ctrl with every right Alt.
fn is_modifier(vk: u32) -> bool {
    matches!(vk, 0x10 | 0x11 | 0x12 | VK_LSHIFT..=VK_RMENU | VK_LWIN | VK_RWIN | 0x14)
}

static SINK: Mutex<Option<Sender<(KeyEvent, Instant)>>> = Mutex::new(None);
static KEY: AtomicU8 = AtomicU8::new(0);
/// For Ctrl + Win: which of the two is down (bit 0 Ctrl, bit 1 Win).
static COMBO: AtomicU8 = AtomicU8::new(0);
static HELD: AtomicU8 = AtomicU8::new(0);
/// The first key events after the listener starts are written to the log, to tell which source saw them.
static DIAG: AtomicU32 = AtomicU32::new(0);
const DIAG_LINES: u32 = 40;

fn diag(what: &str, source: &str) {
    if DIAG.fetch_add(1, Ordering::Relaxed) < DIAG_LINES {
        crate::log::line(format!("flow key: {what} ({source})"));
    }
}

/// A press or a release of the dictation key, from the hook or from raw input. Only a change of state is sent, so
/// two sources reporting the same press count once, and a held key's repeats do nothing.
fn edge(down: bool, source: &str) {
    if down {
        if HELD.swap(1, Ordering::Relaxed) == 0 {
            diag("down", source);
            send(KeyEvent::Down);
        }
    } else if HELD.swap(0, Ordering::Relaxed) == 1 {
        diag("up", source);
        send(KeyEvent::Up);
    }
}

/// Ctrl + Win: a press when both are down, a release when one of them goes up.
fn combo_edge(vk: u32, down: bool, up: bool, source: &str) {
    let bit = if vk == VK_LCONTROL { 1 } else { 2 };
    let before = COMBO.load(Ordering::Relaxed);
    let after = if down { before | bit } else if up { before & !bit } else { before };
    COMBO.store(after, Ordering::Relaxed);
    if before != 3 && after == 3 {
        diag("down", source);
        send(KeyEvent::Down);
    } else if before == 3 && after != 3 {
        diag("up", source);
        send(KeyEvent::Up);
    }
}

/// The key a raw keyboard event is, in the same codes the hook reports (left and right told apart). Raw input
/// reports Ctrl, Alt and Shift as one code each, with the side in the "extended" flag or in the scan code.
fn raw_to_vk(vkey: u16, make_code: u16, flags: u16) -> Option<u32> {
    const E0: u16 = 2;
    let extended = flags & E0 != 0;
    match vkey as u32 {
        0x11 => Some(if extended { VK_RCONTROL } else { VK_LCONTROL }),
        0x12 => Some(if extended { VK_RMENU } else { VK_LMENU }),
        0x10 => Some(if make_code == 0x36 { VK_RSHIFT } else { VK_LSHIFT }),
        VK_LWIN => Some(VK_LWIN),
        VK_RWIN => Some(VK_RWIN),
        _ => None,
    }
}

/// A raw keyboard event (WM_INPUT): the dictation key's presses and releases, merged with the hook's.
unsafe fn on_raw(lparam: LPARAM) {
    let mut raw: RAWINPUT = std::mem::zeroed();
    let mut size = std::mem::size_of::<RAWINPUT>() as u32;
    let got = GetRawInputData(
        HRAWINPUT(lparam.0 as *mut std::ffi::c_void),
        RID_INPUT,
        Some(&mut raw as *mut RAWINPUT as *mut std::ffi::c_void),
        &mut size,
        std::mem::size_of::<RAWINPUTHEADER>() as u32,
    );
    if got == u32::MAX || raw.header.dwType != RIM_TYPEKEYBOARD.0 {
        return;
    }
    let k = raw.data.keyboard;
    let up = k.Flags & 1 != 0; // RI_KEY_BREAK
    let Some(vk) = raw_to_vk(k.VKey, k.MakeCode, k.Flags) else { return };
    on_modifier(vk, !up, up, "raw");
}

/// The dictation key itself (not "another key"): from either source.
fn on_modifier(vk: u32, down: bool, up: bool, source: &str) {
    let key = KEY.load(Ordering::Relaxed);
    let ours = match key {
        0 => vk == VK_RCONTROL,
        1 => vk == VK_RMENU,
        2 => vk == VK_RSHIFT,
        _ => false,
    };
    if key == 3 && matches!(vk, VK_LCONTROL | VK_LWIN) {
        combo_edge(vk, down, up, source);
    } else if ours {
        edge(down, source);
    }
}

fn key_code(key: FlowKey) -> u8 {
    match key {
        FlowKey::RightCtrl => 0,
        FlowKey::RightAlt => 1,
        FlowKey::RightShift => 2,
        FlowKey::LeftCtrlSuper => 3,
    }
}

fn send(event: KeyEvent) {
    if let Some(tx) = SINK.lock().unwrap().as_ref() {
        let _ = tx.send((event, Instant::now()));
    }
}

unsafe extern "system" fn hook(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code >= 0 {
        let info = &*(lparam.0 as *const KBDLLHOOKSTRUCT);
        // Our own Ctrl+V, and other programs' synthetic keys, are not the user's.
        if info.flags.0 & LLKHF_INJECTED.0 == 0 {
            let down = matches!(wparam.0 as u32, WM_KEYDOWN | WM_SYSKEYDOWN);
            let up = matches!(wparam.0 as u32, WM_KEYUP | WM_SYSKEYUP);
            on_key(info.vkCode, down, up);
        }
    }
    CallNextHookEx(None, code, wparam, lparam)
}

fn on_key(vk: u32, down: bool, up: bool) {
    let key = KEY.load(Ordering::Relaxed);
    let ours = match key {
        0 => vk == VK_RCONTROL,
        1 => vk == VK_RMENU,
        2 => vk == VK_RSHIFT,
        3 => matches!(vk, VK_LCONTROL | VK_LWIN),
        _ => false,
    };
    if ours {
        on_modifier(vk, down, up, "hook");
        return;
    }
    if !down {
        return;
    }
    if vk == VK_ESCAPE {
        send(KeyEvent::Escape);
    } else if !is_modifier(vk) {
        send(KeyEvent::Other);
    }
}

/// A hidden window that gets the keyboard's raw input even when another program is in front. None (and a line in
/// the log) if Windows refuses: the hook alone then does the job, as before.
unsafe fn raw_input_window() -> Option<HWND> {
    let hwnd = match CreateWindowExW(
        WINDOW_EX_STYLE(0),
        w!("STATIC"),
        PCWSTR::null(),
        WINDOW_STYLE(0),
        0,
        0,
        0,
        0,
        Some(HWND_MESSAGE),
        None,
        None,
        None,
    ) {
        Ok(h) => h,
        Err(err) => {
            crate::log::line(format!("flow key: raw input window failed: {err}"));
            return None;
        }
    };
    let device = RAWINPUTDEVICE { usUsagePage: 0x01, usUsage: 0x06, dwFlags: RIDEV_INPUTSINK, hwndTarget: hwnd };
    if let Err(err) = RegisterRawInputDevices(&[device], std::mem::size_of::<RAWINPUTDEVICE>() as u32) {
        crate::log::line(format!("flow key: raw input failed: {err}"));
        let _ = DestroyWindow(hwnd);
        return None;
    }
    Some(hwnd)
}

/// The hook's thread; it ends when dropped.
pub struct KeyListener {
    thread_id: u32,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Drop for KeyListener {
    fn drop(&mut self) {
        unsafe {
            let _ = PostThreadMessageW(self.thread_id, WM_QUIT, WPARAM(0), LPARAM(0));
        }
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
        *SINK.lock().unwrap() = None;
    }
}

pub fn listen(key: FlowKey, tx: Sender<(KeyEvent, Instant)>) -> Result<KeyListener, String> {
    KEY.store(key_code(key), Ordering::Relaxed);
    COMBO.store(0, Ordering::Relaxed);
    HELD.store(0, Ordering::Relaxed);
    DIAG.store(0, Ordering::Relaxed);
    *SINK.lock().unwrap() = Some(tx);
    let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<u32, String>>();
    let thread = std::thread::Builder::new()
        .name("coucou-flow-keys".into())
        .spawn(move || unsafe {
            let module = GetModuleHandleW(None).ok();
            let handle = match SetWindowsHookExW(WH_KEYBOARD_LL, Some(hook), module.map(Into::into), 0) {
                Ok(h) => h,
                Err(err) => {
                    let _ = ready_tx.send(Err(err.to_string()));
                    return;
                }
            };
            // Raw input beside the hook: a message-only window receives it, in this thread's queue.
            let target = raw_input_window();
            let _ = ready_tx.send(Ok(GetCurrentThreadId()));
            let mut msg = MSG::default();
            while GetMessageW(&mut msg, None, 0, 0).as_bool() {
                if msg.message == WM_INPUT {
                    on_raw(msg.lParam);
                    continue;
                }
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            if let Some(h) = target {
                let _ = DestroyWindow(h);
            }
            let _ = UnhookWindowsHookEx(handle);
        })
        .map_err(|e| e.to_string())?;
    match ready_rx.recv_timeout(Duration::from_secs(3)) {
        Ok(Ok(thread_id)) => Ok(KeyListener { thread_id, thread: Some(thread) }),
        Ok(Err(err)) => Err(err),
        Err(_) => Err("the keyboard hook did not start".into()),
    }
}

/// Waits (up to a second) until the dictation key is up, so the paste is a
/// plain Ctrl+V and not Ctrl+Alt+V or Ctrl+Shift+V.
pub fn wait_key_released(key: FlowKey) {
    let vk = match key {
        FlowKey::RightCtrl => VK_RCONTROL,
        FlowKey::RightAlt => VK_RMENU,
        FlowKey::RightShift => VK_RSHIFT,
        FlowKey::LeftCtrlSuper => VK_LWIN,
    } as i32;
    let until = Instant::now() + Duration::from_secs(1);
    while Instant::now() < until {
        let state = unsafe { GetAsyncKeyState(vk) } as u16;
        if state & 0x8000 == 0 {
            return;
        }
        std::thread::sleep(Duration::from_millis(15));
    }
}

fn key_input(vk: u16, up: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: VIRTUAL_KEY(vk),
                wScan: 0,
                dwFlags: if up { KEYEVENTF_KEYUP } else { KEYBD_EVENT_FLAGS(0) },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}

/// Ctrl+V in the app in front. Ctrl+Shift+V for a terminal that wants it.
pub fn send_paste(shift: bool) -> Result<(), String> {
    let mut inputs = vec![key_input(VK_CONTROL, false)];
    if shift {
        inputs.push(key_input(VK_SHIFT_KEY, false));
    }
    inputs.push(key_input(VK_V, false));
    inputs.push(key_input(VK_V, true));
    if shift {
        inputs.push(key_input(VK_SHIFT_KEY, true));
    }
    inputs.push(key_input(VK_CONTROL, true));
    let sent = unsafe { SendInput(&inputs, std::mem::size_of::<INPUT>() as i32) };
    if sent as usize == inputs.len() {
        Ok(())
    } else {
        Err("Windows refused the keystrokes".into())
    }
}

/// A window's program: "outlook", "Outlook".
fn program_of(hwnd: HWND) -> Option<(String, String)> {
    unsafe {
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid as *mut u32));
        if pid == 0 || pid == std::process::id() {
            return None;
        }
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let mut buf = [0u16; 1024];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(process, PROCESS_NAME_WIN32, PWSTR(buf.as_mut_ptr()), &mut len).is_ok();
        let _ = CloseHandle(process);
        if !ok {
            return None;
        }
        let path = String::from_utf16_lossy(&buf[..len as usize]);
        let stem = std::path::Path::new(&path).file_stem()?.to_string_lossy().to_string();
        Some((stem.to_lowercase(), super::display_name(&stem)))
    }
}

/// The app in front: (id, name), None for Coucou itself or the desktop.
pub fn foreground_app() -> Option<(String, String)> {
    let hwnd = unsafe { GetForegroundWindow() };
    if hwnd.0.is_null() {
        return None;
    }
    program_of(hwnd)
}

/// Apps with a visible window, for "Add an app" in Styles.
pub fn running_apps() -> Vec<(String, String)> {
    unsafe extern "system" fn each(hwnd: HWND, lparam: LPARAM) -> windows::core::BOOL {
        let list = &mut *(lparam.0 as *mut Vec<(String, String)>);
        if IsWindowVisible(hwnd).as_bool() && GetWindowTextLengthW(hwnd) > 0 {
            if let Some(app) = program_of(hwnd) {
                if !list.iter().any(|(id, _)| *id == app.0) && !super::is_shell(&app.0) {
                    list.push(app);
                }
            }
        }
        windows::core::BOOL(1)
    }
    let mut list: Vec<(String, String)> = Vec::new();
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(&mut list as *mut _ as isize));
    }
    list
}

/// Whether the platform can listen for the key at all.
pub fn keys_supported() -> Result<(), &'static str> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raw_keys_are_told_left_from_right() {
        assert_eq!(raw_to_vk(0x11, 0x1D, 2), Some(VK_RCONTROL));
        assert_eq!(raw_to_vk(0x11, 0x1D, 0), Some(VK_LCONTROL));
        assert_eq!(raw_to_vk(0x12, 0x38, 2), Some(VK_RMENU));
        assert_eq!(raw_to_vk(0x12, 0x38, 0), Some(VK_LMENU));
        assert_eq!(raw_to_vk(0x10, 0x36, 0), Some(VK_RSHIFT));
        assert_eq!(raw_to_vk(0x10, 0x2A, 0), Some(VK_LSHIFT));
        assert_eq!(raw_to_vk(0x5B, 0x5B, 2), Some(VK_LWIN));
        assert_eq!(raw_to_vk(0x41, 0x1E, 0), None);
    }
}
