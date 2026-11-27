"""Exercise both native VPN cores on x86 TV and the x86_64 16 KiB image."""
import json
import os
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


def screenshot(name):
    """Real pixels from the running Android TV emulator, never a design mock."""
    os.makedirs("tv-screens", exist_ok=True)
    with open("tv-screens/" + name + ".png", "wb") as output:
        subprocess.run(["adb", "exec-out", "screencap", "-p"],
                       stdout=output, check=True)
    assert Path("tv-screens/" + name + ".png").stat().st_size > 2000


def is_focused(label):
    """One consistent UI snapshot avoids focus/text races during D-pad checks."""
    snapshot = nodes()
    matches = [n for n in snapshot
               if label in (n.get("text", ""), n.get("content-desc", ""))]
    focused = [n for n in snapshot if n.get("focused") == "true"]
    for focus in focused:
        rect = list(map(int, re.findall(r"\d+", focus.get("bounds", ""))))
        if len(rect) != 4:
            continue
        for item in matches:
            bounds = list(map(int, re.findall(r"\d+", item.get("bounds", ""))))
            if len(bounds) == 4 and rect[0] <= bounds[0] and rect[1] <= bounds[1] and rect[2] >= bounds[2] and rect[3] >= bounds[3]:
                return True
    return False


def remote_to(label, max_moves=24):
    """D-pad search in both directions; verifies focused hit, not a touch tap."""
    if is_focused(label):
        return
    for direction in (["DPAD_UP"] * max_moves +
                      ["DPAD_RIGHT"] + ["DPAD_DOWN"] * max_moves):
        key(direction)
        if is_focused(label):
            return
    screenshot("99-tv-remote-focus-failure")
    snapshot = nodes()
    focused = [(n.get("text", ""), n.get("content-desc", ""), n.get("bounds", ""))
               for n in snapshot if n.get("focused") == "true"]
    raise AssertionError("D-pad never focused " + label + "; focused=" + repr(focused[:8]))

def wait_contains(needle, attempts=24):
    for _ in range(attempts):
        if any(needle in (n.get("text", "") + n.get("content-desc", ""))
               for n in nodes()):
            return
        time.sleep(.4)
    raise AssertionError("TV never displayed " + needle)


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
    if not page_size_16k:
        # Setup uses adb input tap for import/QR checks. That switches even
        # the TV emulator to touch mode, where Android deliberately does not
        # assign initial D-pad focus. Return to remote mode BEFORE cold start;
        # the next assertion still requires the power button to own focus.
        key("DPAD_UP")
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
    # The redundant "Серверы" heading was intentionally removed in 0.3.26.
    # Verify the actual remote-selectable server instead of stale decoration.
    wait_label("Выбрать сервер " + label)
    wait_label("В избранное")
    wait_label("Открыть JSON конфигурации " + label)
    wait_label("Подключить VPN")
    # TV rail must display its FULL labels, without the visible truncation
    # present in the owner's screenshots (Подписк / Настрой). The rail is
    # deliberately wider and supports multiline text at large font scale.
    for item in ("Главная", "Подписка", "Настройки"):
        assert any(n.get("text") == item for n in nodes()), "Missing TV navigation label: " + item
    screenshot("00-navigation-full-labels")
    tap("Вкладка Подписка")
    wait_label("Купить подписку Phantom")
    screenshot("00-subscription-access")
    tap("Вкладка Настройки")
    wait_label("Подключение")
    screenshot("01-settings")
    # Exercise both columns with physical D-pad keys and return focus.
    remote_to("Тема оформления")
    key("DPAD_CENTER")
    wait_label("Выберите внешний вид Phantom")
    key("BACK")
    wait_label("Тема оформления")
    for _ in range(8):
        if is_focused("Тема оформления"):
            break
        time.sleep(.2)
    assert is_focused("Тема оформления"), "Theme BACK lost focus"
    key("DPAD_RIGHT")
    assert is_focused("Туннелирование"), "Right column cannot receive D-pad focus"
    key("DPAD_CENTER")
    wait_label("Приложения и VPN")
    key("BACK")
    for _ in range(8):
        if is_focused("Туннелирование"):
            break
        time.sleep(.2)
    assert is_focused("Туннелирование"), "Routing BACK lost focus"
    key("DPAD_LEFT")
    assert is_focused("Тема оформления"), "Left column cannot receive D-pad focus"
    remote_to("Резервная копия")
    key("DPAD_RIGHT")
    assert is_focused("Фоновая работа"), "Background tile cannot receive D-pad focus"
    key("DPAD_CENTER")
    wait_label("Работа при выключенном экране")
    remote_to("Работа при выключенном экране")
    key("DPAD_CENTER")
    key("DPAD_CENTER")
    key("BACK")
    for _ in range(8):
        if is_focused("Фоновая работа"):
            break
        time.sleep(.2)
    assert is_focused("Фоновая работа"), "Background BACK lost focus"
    remote_to("Автоподключение")
    key("DPAD_CENTER")
    key("DPAD_CENTER")
    remote_to("Подключение")
    key("DPAD_CENTER")
    wait_label("DNS")
    wait_label("Фрагментация TLS")
    screenshot("02-connection")
    # Explicitly exercise the previously skipped TLS switch using OK, not tap.
    remote_to("Фрагментация TLS")
    key("DPAD_CENTER")
    assert any(n.get("text") == "Применить параметры" for n in nodes()), (
        "OK did not enable TLS fragmentation")
    key("DPAD_CENTER")
    assert not any(n.get("text") == "Применить параметры" for n in nodes()), (
        "Second OK did not disable TLS fragmentation")
    remote_to("Пинг серверов")
    key("DPAD_CENTER")
    wait_label("Автоматический пинг TUIC")
    wait_label("+ 1 сек")
    screenshot("03-ping")
    # D-pad activation must work on the full switch row.
    remote_to("Автоматический пинг TUIC")
    key("DPAD_CENTER")
    # Discard changed preference by toggling back before saving.
    key("DPAD_CENTER")
    remote_to("Сохранить")
    key("DPAD_CENTER")
    wait_label("Подключение")
    remote_to("DNS")
    key("DPAD_CENTER")
    wait_label("Сохранить")
    key("BACK")
    wait_label("Подключение")
    # BACK should restore the actual DNS action, not the TV rail.
    for _ in range(6):
        if is_focused("DNS"):
            break
        time.sleep(.2)
    if not is_focused("DNS"):
        screenshot("99-tv-dns-back-focus-failure")
        snapshot = nodes()
        focused_bounds = [n.get("bounds", "") for n in snapshot
                          if n.get("focused") == "true"]
        dns_nodes = [(n.get("text", ""), n.get("bounds", "")) for n in snapshot
                     if n.get("text", "") in ("DNS", "Подключение")]
        raise AssertionError("BACK did not restore DNS remote focus; "
                             "focused_bounds=" + repr(focused_bounds[:4]) +
                             "; visible_nav=" + repr(dns_nodes[:5]))
    key("DPAD_UP")
    assert is_focused("Подключение"), "UP from DNS cannot focus connection"
    key("DPAD_CENTER")
    remote_to("О приложении")
    key("DPAD_CENTER")
    wait_label("Проверить обновление приложения")
    wait_label("Автообновление по Wi-Fi")
    remote_to("Компоненты и лицензии")
    key("DPAD_CENTER")
    wait_label("ВОЗМОЖНОСТИ КЛИЕНТА")
    key("BACK")
    for _ in range(6):
        if is_focused("Компоненты и лицензии"):
            break
        time.sleep(.2)
    assert is_focused("Компоненты и лицензии"), "Components BACK lost focus"
    remote_to("Журнал VPN")
    key("DPAD_CENTER")
    wait_label("Журнал VPN")
    key("BACK")
    assert is_focused("Журнал VPN"), "Diagnostics BACK lost focus"
    wait_label("К настройкам")
    tap("К настройкам")
    wait_label("Резервная копия")
    remote_to("Резервная копия")
    key("DPAD_CENTER")
    # The owner's real TV returned to the launcher on this exact transition.
    wait_label("Сохранить резервную копию")
    wait_label("Восстановить из файла")
    assert adb("shell", "pidof", "-s", PACKAGE).strip(), "Backup screen killed Phantom"
    screenshot("04-backup")
    # Save via the physical remote, not the Android system file picker.
    remote_to("Сохранить резервную копию")
    key("DPAD_CENTER")
    wait_contains("Сохранено:")
    screenshot("05-backup-saved")
    tap("Восстановить из файла")
    wait_label("Восстановить резервную копию?")
    key("BACK")  # cancellation must preserve all existing profiles
    wait_label("Восстановить из файла")
    tap("Восстановить из файла")
    wait_label("Восстановить резервную копию?")
    tap("Восстановить")
    wait_label(label)  # restored mock server remains available
    tap("Вкладка Настройки")
    wait_label("Подключение")
    tap("Вкладка Главная")
    wait_label("Подключить VPN")
    screenshot("06-home-after-restore")
    print("Android TV: D-pad TLS and TUIC toggles, backup export/restore, focus, VPN cores, DNS and Back passed")
