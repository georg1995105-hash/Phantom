"""Exercise both native VPN cores on x86 TV and the x86_64 16 KiB image."""
import json
import re
from pathlib import Path
import shlex
import subprocess
from ci_verify_installed_native import install_apk, verify_native
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "xyz.wraithnet.phantom"
CATEGORY = "android.intent.category.LEANBACK_LAUNCHER"
APK = "app/build/outputs/apk/release/phantom-universal-release.apk"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def key(button):
    adb("shell", "input", "keyevent", "KEYCODE_" + button)
    time.sleep(.25)


def nodes():
    options = ["--windows"] if page_size_16k else []
    result = subprocess.run(["adb", "shell", "uiautomator", "dump", *options, "/sdcard/tv-ui.xml"],
                            capture_output=True, text=True)
    if "null root node" in result.stderr:
        return []
    result.check_returncode()
    assert "UI hierchary dumped to" in result.stdout or "UI hierarchy dumped to" in result.stdout, result
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/tv-ui.xml")).iter("node"))


def report_singbox_exit():
    """Capture only non-secret Android process-exit/crash evidence in CI."""
    def read(*args):
        try:
            return adb(*args).splitlines()
        except (subprocess.CalledProcessError, OSError):
            return []

    def redact(line):
        line = re.sub(r"(?i)(?:vless|vmess|tuic|anytls|ss|hysteria2?|https?)://\S+",
                      "[private URL]", line)
        line = re.sub(r"(?i)(password|secret|token|authorization|uuid|key)[=:]\S+",
                      r"\1=[private]", line)
        line = re.sub(r"\b(?:\d{1,3}\.){3}\d{1,3}\b",
                      "[IP]", line)
        return line[:240]

    print("TV sing-box PID:",
          subprocess.run(["adb", "shell", "pidof", "-s", PACKAGE + ":singbox"],
                         capture_output=True, text=True).stdout.strip() or "not running",
          flush=True)
    history = read("shell", "dumpsys", "activity", "exit-info", PACKAGE)
    relevant = [redact(s.strip()) for s in history
                if ":singbox" in s or "reason=" in s or "status=" in s
                or "REASON_" in s]
    print("TV Android process-exit reasons:", relevant[-15:], flush=True)
    crashes = read("logcat", "-b", "crash", "-d", "-v", "brief")
    fatal = [redact(s.strip()) for s in crashes
             if re.search(r"Fatal signal|SIGABRT|SIGSEGV|Abort message|FATAL EXCEPTION|"
                          r"panic:|runtime:|libbox|libgojni|AndroidRuntime", s, re.I)]
    print("TV crash signatures:", fatal[-25:], flush=True)
    if page_size_16k:
        # libbox redirects Go's crash output to its app-private file. A Go
        # fatal signal can therefore leave the Android crash buffer empty.
        report = read("shell", "su", "0", "head", "-c", "32768",
                      f"/data/user/0/{PACKAGE}/files/singbox/CrashReport-.log")
        frames = []
        for line in report:
            value = line.strip()
            if re.match(r"^(?:fatal error:|panic:|runtime:|SIG[A-Z]+\b|\[signal |unexpected fault)", value):
                frames.append(redact(value))
            elif re.match(r"^(?:created by )?[\w./-]+[.(]", value) and "(" in value:
                frames.append(redact(value.split("(", 1)[0]))
            elif source := re.search(r"([^/\s]+\.(?:go|s|c):\d+)\b", value):
                frames.append(source.group(1))
        print("16 KiB signed APK Go crash frames:", frames[:36], flush=True)
        system = read("logcat", "-b", "all", "-d", "-v", "brief")
        errors = [redact(line) for line in system if re.search(
            r"\bcrash_dump(?:32|64)?\b|\btombstoned\b|Fatal signal|SIGSEGV|"
            r"(?:libbox|singbox).*?(?:failed|died|killed|crash|panic|SIGSEGV)", line, re.I)]
        print("16 KiB Android crash diagnostics:", errors[-32:], flush=True)


def wait_label(label):
    for _ in range(18):
        visible = []
        for node in nodes():
            labels = [value for field in ("text", "content-desc")
                      for value in node.get(field, "").splitlines() if value]
            if label in labels:
                return node
            visible.extend(labels)
    # Never suppress a real VPN connection failure as an emulator flake.
    # Show the app's own diagnostic dialog so the failure is actionable.
    diagnostic = []
    reason = next((item for item in nodes() if item.get("text") == "Причина ошибки"), None)
    if reason is not None:
        bounds = list(map(int, re.findall(r"\d+", reason.get("bounds", ""))))
        if len(bounds) == 4:
            adb("shell", "input", "tap",
                str((bounds[0] + bounds[2]) // 2),
                str((bounds[1] + bounds[3]) // 2))
            # Compose displays AlertDialog asynchronously; wait for it.
            for _ in range(8):
                snapshot = nodes()
                diagnostic = [item.get("text", "") for item in snapshot
                              if item.get("text") and "://" not in item.get("text", "")]
                if "Ошибка подключения" in diagnostic:
                    break
                time.sleep(.3)
    safe = [value for value in visible if "://" not in value]
    print("TV VPN failure: UI diagnostics:", diagnostic[:20], flush=True)
    print("Android window focus:", [line.strip() for line in adb("shell", "dumpsys", "window").splitlines()
          if "mCurrentFocus=" in line or "mFocusedApp=" in line], flush=True)
    report_singbox_exit()
    raise AssertionError(
        f"Missing TV label {label!r}. Visible: {safe[:32]}, reason: {diagnostic[:20]}"
    )


def tap(label):
    left, top, right, bottom = map(int, re.findall(r"\d+", wait_label(label).get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def wait_power_focus():
    """Confirm that the TV power button, not the navigation rail, owns OK."""
    button = wait_label("Подключить VPN")
    box = list(map(int, re.findall(r"\d+", button.get("bounds", ""))))
    assert len(box) == 4, f"Missing power button bounds: {button.attrib}"
    x, y = (box[0] + box[2]) // 2, (box[1] + box[3]) // 2
    for _ in range(32):
        focused = [n for n in nodes() if n.get("focused") == "true"]
        for node in focused:
            rect = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(rect) == 4 and rect[0] <= x <= rect[2] and rect[1] <= y <= rect[3]:
                return
        time.sleep(.15)
    snapshot = [(n.get("text", ""), n.get("content-desc", ""),
                 n.get("bounds", "")) for n in nodes() if n.get("focused") == "true"]
    raise AssertionError("Power did not receive TV focus on cold start: " + repr(snapshot))


page_size_16k = sys.argv[1:] == ["--16k"]
if page_size_16k:
    assert adb("shell", "getconf", "PAGE_SIZE") == "16384"
    assert adb("shell", "id", "-u") == "2000"
    # On the debuggable system image, elevate only the two property writes.
    # Restarting adbd for root interrupts package streaming and UI automation.
    assert adb("shell", "su", "0", "id", "-u") == "0"
    adb("shell", "su", "0", "setprop", "bionic.linker.16kb.app_compat.enabled", "false")
    adb("shell", "su", "0", "setprop", "pm.16kb.app_compat.disabled", "true")
    assert adb("shell", "getprop", "bionic.linker.16kb.app_compat.enabled") == "false"
    assert adb("shell", "getprop", "pm.16kb.app_compat.disabled") == "true"
    memory = adb("shell", "cat", "/proc/meminfo").splitlines()
    print("Android 16 KiB pages, backcompat disabled:",
          [line for line in memory if line.startswith(("MemTotal:", "MemAvailable:"))], flush=True)
    key("WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    CATEGORY = "android.intent.category.LAUNCHER"
    device = "Android x86_64 16 KiB"
else:
    assert "android.software.leanback" in adb("shell", "pm", "list", "features")
    device = "TV x86"
install_apk(APK, "-g")
assert verify_native(APK, PACKAGE) == ("x86_64" if page_size_16k else "x86")
resolve = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a",
              "android.intent.action.MAIN", "-c", CATEGORY, PACKAGE)
assert PACKAGE + "/.MainActivity" in resolve
component = resolve.splitlines()[-1]
if not page_size_16k:
    # Real Android TV emulation has no camera. Import must expose URL entry,
    # never a camera activity that cannot succeed on a remote-only device.
    launched = adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                   "-c", CATEGORY, "-n", component)
    assert "Status: ok" in launched, launched
    # A fresh TV has no subscriptions: it must DISPLAY its own receiver QR,
    # never try to launch a camera. The legacy QR for an existing subscription
    # remains a separate feature tested by Android UI tests.
    wait_label("Добавить с телефона")
    wait_label("QR-код передачи подписки")
    assert not any(n.get("text") == "Сканировать QR" or
                   n.get("content-desc") == "Сканировать QR-код подписки"
                   for n in nodes()), "Camera QR import must be absent on Android TV"
    # A television is remote-only. Closing the dialog by BACK must restore
    # D-pad navigation without requiring a touchscreen tap (which forces the
    # Android TV emulator into touch mode and hides its keyboard focus).
    key("BACK")
    wait_label("QR с телефона")
    time.sleep(.6)
    focused = [(n.get("text", ""), n.get("content-desc", ""),
                n.get("bounds", "")) for n in nodes() if n.get("focused") == "true"]
    print("TV focus after closing receiver QR with remote:", focused, flush=True)
    # OK must activate the real power control: no selected server => import.
    key("DPAD_CENTER")
    wait_label("Добавить конфигурацию")
    key("BACK")
    tap("Вкладка Главная")
    tap("Добавить подписку или ключ")
    wait_label("Ссылка подписки")
    key("BACK")
    adb("shell", "am", "force-stop", PACKAGE)
    print("Android TV receiver QR, manual subscription URL and no-camera scanner passed", flush=True)
cases = json.loads(Path("app/src/androidTest/assets/peers.json").read_text())
for index, (protocol, label) in enumerate((
    ("hysteria2", "TVCheckXray"), ("tuic", "TVCheckTUIC")
)):
    if index:
        assert "Success" in adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
    profile = cases[protocol] + "#" + label
    opened = adb("shell", shlex.join(["am", "start", "-W", "-a", "android.intent.action.VIEW",
                                    "-d", profile, "-n", PACKAGE + "/.MainActivity"]))
    assert "Status: ok" in opened, opened
    wait_label(label)
    adb("shell", "am", "force-stop", PACKAGE)
    launch = adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                 "-c", CATEGORY, "-n", component)
    assert "Status: ok" in launch, launch
    startup = re.search(r"^TotalTime: (\d+)$", launch, re.MULTILINE)
    print(f"{device} cold launch: {startup.group(1) if startup else 'unavailable'} ms",
          flush=True)
    if page_size_16k:
        tap("Подключить VPN")
    else:
        # Compose may display text before its D-pad focus target is attached.
        # Pressing OK too early can activate the navigation rail instead.
        wait_power_focus()
        key("DPAD_CENTER")
    try:
        # On wide TV the state is inside the clickable power control. Its
        # semantics action changes from "Подключить" to "Отключить".
        wait_label("Подключено" if page_size_16k else "Отключить VPN")
    except AssertionError as ex:
        raise AssertionError(f"{device} {protocol} tunnel did not connect: {ex}") from ex
    processes = [PACKAGE]
    if protocol == "tuic":
        worker_pid = adb("shell", "pidof", "-s", PACKAGE + ":singbox")
        assert worker_pid.isdecimal()
        processes.append(worker_pid)
    for process in processes:
        memory = adb("shell", "dumpsys", "meminfo", process)
        totals = [line.strip() for line in memory.splitlines()
                  if "TOTAL PSS:" in line or line.lstrip().startswith("** MEMINFO")]
        print(f"{device} {protocol} memory: {' | '.join(totals)}", flush=True)
    if page_size_16k:
        tap("Отключить VPN")
    else:
        key("DPAD_CENTER")
    wait_label("Отключено" if page_size_16k else "Подключить VPN")
    print(f"{device} native {protocol}: connected and disconnected", flush=True)
if not page_size_16k:
    # The Android TV UI has a real D-pad navigation rail, not phone bottom
    # tabs. Test every destination and TV-only About subpage on the actual
    # signed universal APK, with no touch-only controls or dead-end menus.
    # All servers are reachable directly on Home in two columns.
    # The separate Servers rail tab is intentionally absent.
    assert not any(n.get("content-desc") == "Вкладка Серверы" for n in nodes())
    wait_label("Серверы")
    wait_label("В избранное")
    wait_label("Открыть JSON конфигурации " + label)
    wait_label("Подключить VPN")
    tap("Вкладка Подписка")
    wait_label("В магазин")
    tap("Вкладка Настройки")
    wait_label("Подключение")
    tap("Подключение")
    wait_label("DNS")
    tap("DNS")
    wait_label("Сохранить")
    key("BACK")
    wait_label("Подключение")
    # Navigate settings by remote keys; expanding items must be reachable by
    # D-pad scrolling, not only via touch.
    for _ in range(24):
        if any(n.get("text") == "О приложении" for n in nodes()):
            break
        key("DPAD_DOWN")
    tap("О приложении")
    wait_label("Проверить обновление приложения")
    wait_label("Автообновление по Wi-Fi")
    tap("Компоненты и лицензии")
    wait_label("ВОЗМОЖНОСТИ КЛИЕНТА")
    key("BACK")
    wait_label("Журнал VPN")
    tap("Журнал VPN")
    wait_label("Журнал VPN")
    key("BACK")
    wait_label("К настройкам")
    tap("К настройкам")
    wait_label("Резервная копия")
    tap("Вкладка Главная")
    wait_label("Подключить VPN")
    print("Android TV: D-pad, unified server grid, three rail tabs, VPN cores, DNS, About, journal and Back passed")
