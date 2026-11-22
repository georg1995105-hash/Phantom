"""Verify the signed production upgrade and its four destinations on the CI emulator."""
from pathlib import Path
import hashlib
import re
import json
import secrets
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import urllib.request
from ci_verify_installed_native import install_apk, verify_native

PACKAGE = "xyz.wraithnet.phantom"
ACTIVITY = PACKAGE + "/.MainActivity"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def report_crash(kind, error, traceback):
    sys.__excepthook__(kind, error, traceback)
    print(adb("logcat", "-b", "crash", "-d", "-t", "180"), flush=True)
    print(adb("shell", "dumpsys", "activity", "exit-info", PACKAGE)[:5000], flush=True)


sys.excepthook = report_crash


def node(label, attempts=8):
    launcher_recovered = False
    # Native JNI tests can temporarily exhaust the shared CI emulator and
    # cause a SYSTEM launcher ANR (Pixel Launcher or AOSP Quickstep). Verify
    # that the focused ANR window belongs to a known launcher package before
    # dismissing it. Never suppress Phantom ANRs or missing UI assertions.
    for _ in range(max(attempts, 20) if label == "UpgradeCheck" else attempts):
        adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
        for item in root.iter("node"):
            values = (item.get("text", "") + "\n" + item.get("content-desc", "")).splitlines()
            if label in values:
                return item
        visible_text = " ".join((item.get("text") or "") for item in root.iter("node"))
        known_launcher = ("Pixel Launcher isn't responding" in visible_text or
                          "Quickstep isn't responding" in visible_text)
        focused_window = adb("shell", "dumpsys", "window", "displays") if known_launcher else ""
        launcher_anr = re.search(
            r"mCurrentFocus=.*Application Not Responding: "
            r"(?:com\.android\.launcher3|com\.google\.android\.apps\.nexuslauncher)",
            focused_window,
        ) is not None
        if label == "UpgradeCheck" and not launcher_recovered and launcher_anr:
            dismiss = next((item for item in root.iter("node")
                if item.get("text") == "Close app"), None)
            if dismiss is not None:
                left, top, right, bottom = map(int, re.findall(r"\d+", dismiss.get("bounds", "")))
                adb("shell", "input", "tap", str((left + right) // 2),
                    str((top + bottom) // 2))
                adb("shell", "am", "start", "-W", "-n", ACTIVITY)
                launcher_recovered = True
        time.sleep(0.5)
    visible = [n.get("text") or n.get("content-desc") for n in root.iter("node")]
    raise AssertionError(f"Missing UI element {label!r}, visible: {list(filter(None, visible))}")


def wait_compact_latencies(min_count=2, attempts=30):
    """Verify numerical RTTs without old TCP/QUIC/VPN source prefixes."""
    last = []
    for _ in range(attempts):
        adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
        last = [(item.get("text") or "") for item in root.iter("node")]
        readings = [v for v in last if re.fullmatch(r"[1-9][0-9]* мс", v)]
        assert not any(re.match(r"^(?:VPN|TCP|QUIC) · ", v) for v in last), (
            "Legacy transport labels leaked into server UI"
        )
        if len(readings) >= min_count:
            return readings
        time.sleep(0.5)
    raise AssertionError(f"Expected {min_count} measured server pings, found {last[:40]}")


def verify_tun_transfer(host="198.18.0.1"):
    # A separate Android UID enters TUN. This synthetic address exists only
    # behind the XHTTP peer, so a direct request cannot pass the check.
    payload = secrets.token_bytes(1048576)
    request = (f"POST /VpnTransfer HTTP/1.1\r\nHost: {host}:34301\r\n"
               f"Content-Length: {len(payload)}\r\nConnection: close\r\n\r\n").encode() + payload
    started = time.monotonic()
    response = subprocess.run(["adb", "shell", "-T", "toybox", "nc", "-w", "5", "-W", "12", host, "34301"],
                              input=request, capture_output=True, timeout=25)
    headers, _, body = response.stdout.partition(b"\r\n\r\n")
    if (response.returncode != 0 or not re.match(rb"HTTP/1\.[01] 200\b", headers) or
            body != hashlib.sha256(payload).digest() * 32768):
        raise AssertionError(f"Android TUN transfer failed: code={response.returncode}, bytes={len(body)}, "
                             f"stderr={response.stderr[:150]!r}")
    print(f"Signed APK: Android TUN transferred 1 MiB up and down via XHTTP in {time.monotonic() - started:.2f}s",
          flush=True)


def tap(label):
    left, top, right, bottom = map(int, re.findall(r"\d+", node(label).get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def tap_in_list(label):
    for _ in range(4):
        try:
            node(label, attempts=1)
        except AssertionError:
            adb("shell", "input", "swipe", "500", "1650", "500", "800", "320")
        else:
            tap(label)
            return
    tap(label)


def visible_after_scroll(label, max_swipes=6):
    """Prove a CTA is reachable in compact landscape/TV layouts, not only above the fold."""
    for attempt in range(max_swipes + 1):
        try:
            return node(label, attempts=1)
        except AssertionError:
            if attempt == max_swipes:
                raise
            adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
            root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
            bounds = [list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
                      for n in root.iter("node")]
            bounds = [b for b in bounds if len(b) == 4]
            assert bounds, "UI hierarchy has no bounds"
            width = max(b[2] for b in bounds)
            height = max(b[3] for b in bounds)
            assert width > 0 and height > 0, "Invalid Android display bounds"
            adb("shell", "input", "swipe", str(width // 2), str(int(height * .73)),
                str(width // 2), str(int(height * .27)), "360")


def show_servers():
    """Subscriptions start directly after Connected on the single Home page."""
    tap("Главная")
    # The decorative "Серверы" section label and duplicate current-country
    # card were deliberately removed. The Home tab resets scrolling to top.


def screenshot(name):
    path = Path("upgrade-screens")
    path.mkdir(exist_ok=True)
    (path / (name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))


def installed_version():
    dump = adb("shell", "dumpsys", "package", PACKAGE)
    code = re.search(r"\bversionCode=(\d+)", dump).group(1)
    name = re.search(r"\bversionName=(\S+)", dump).group(1)
    return int(code), name


old, new = sys.argv[1:]
# Native smoke installs a debug package on this disposable emulator.
if PACKAGE in adb("shell", "pm", "list", "packages", PACKAGE):
    adb("uninstall", PACKAGE)
install_apk(old, "-g")
previous_code, previous_name = installed_version()
assert previous_code > 0 and re.fullmatch(r"\d+\.\d+\.\d+", previous_name), (
    f"Unexpected previous stable package version: {previous_code} / {previous_name}"
)
adb("shell", "svc", "wifi", "disable")
adb("shell", "svc", "data", "disable")
adb("shell", "am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.VIEW",
    "-d", "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443#UpgradeCheck")
node("UpgradeCheck")
tap("Настройки")
tap("Тема оформления")
tap("Светлая")
adb("shell", "input", "keyevent", "4")
node("Светлая")
tap("Главная")
visible_after_scroll("В избранное", max_swipes=10)
tap("В избранное")
tap("Главная")
install_apk(new, "-r")
verify_native(new, PACKAGE)
current_code, current_name = installed_version()
# The workflow checks the official baseline APK SHA-256 and production signer.
# Compare the new package against Gradle so future version increments do
# not require maintaining hard-coded versions inside this test script.
gradle_source = Path("app/build.gradle.kts").read_text(encoding="utf-8")
expected_code = int(re.search(r'(?m)^\s*versionCode\s*=\s*(\d+)\s*$', gradle_source).group(1))
expected_name = re.search(r'(?m)^\s*versionName\s*=\s*"([^"]+)"', gradle_source).group(1)
assert (current_code, current_name) == (expected_code, expected_name), (
    f"Installed APK version mismatch: {current_code}/{current_name}, expected "
    f"{expected_code}/{expected_name}"
)
assert current_code > previous_code, (
    f"Stable update must increase versionCode: {previous_code} → {current_code}"
)
print(f"Full-file upgrade: {previous_name} ({previous_code}) → {current_name} ({current_code})",
      flush=True)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
visible_after_scroll("UpgradeCheck")
node("Отключено")
# A standalone key cannot be refreshed as a subscription. The redundant
# global refresh CTA was removed; per-owner refresh remains on owner cards.
adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
ui = adb("shell", "cat", "/sdcard/phantom-ui.xml")
assert "Обновить выбранную подписку" not in ui
assert 'text="Время"' not in ui
visible_after_scroll("UpgradeCheck")
screenshot("home")
show_servers()
visible_after_scroll("UpgradeCheck")
visible_after_scroll("Убрать из избранного")
screenshot("servers")
visible_after_scroll("Добавить подписку или ключ", max_swipes=18)
tap("Подписка")
node("В магазин")
screenshot("subscriptions")
tap("Настройки")
node("Тема оформления")
node("Светлая")
screenshot("settings")
# The APK-update action is separate and reachable in About, not on the VPN home.
tap_in_list("О приложении")
tap_in_list("Проверить обновление приложения")
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
show_servers()
visible_after_scroll("Убрать из избранного")


# Continue through the native TUN checks in the package installed above.
tap("Настройки")
tap("Тема оформления")
tap_in_list("Тёмно-зелёная")
adb("shell", "input", "keyevent", "4")
node("Тёмно-зелёная")
screenshot("forest-settings")
show_servers()
# Switch the real Android VPN between the TUIC and AnyTLS loopback peers. Android
# establishes a real TUN. The production connectivity check verifies HTTPS.
adb("shell", "svc", "wifi", "enable")
adb("shell", "svc", "data", "enable")
peers = json.loads(Path("app/src/androidTest/assets/peers.json").read_text())
profiles = [
    peers["tuic"] + "#AndroidCheckA",
    "vless://00000000-0000-4000-8000-000000000001@10.0.2.2:34304#BareTcp",
    peers["hysteria2"] + "#QuicProbe",
    peers["anytls"] + "#AndroidCheckB",
    peers["VLESS-XHTTP"] + "#XhttpProbe",
]
payload = json.dumps(profiles, separators=(",", ":"))
adb("shell", shlex.join(["am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.SEND",
                        "-t", "text/plain", "--es", "android.intent.extra.TEXT", payload]))
tap_in_list("Раскрыть подписку AndroidCheckA")
tap_in_list("Выбрать сервер AndroidCheckA")
tap("Главная")
node("AndroidCheckA")
adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
tap("Подключить VPN")
node("Подключено")
screenshot("forest-home-connected")
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
time.sleep(4)
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
node("Подключено")
show_servers()
# This port accepts a TCP handshake but has no proxy. The active TUIC peer rejects traffic to it.
def entry_counts():
    with urllib.request.urlopen("http://127.0.0.1:34301/TcpEntryCounts", timeout=2) as response:
        return json.load(response)


visible_after_scroll("XhttpProbe")
wait_compact_latencies(min_count=3)
before, payloads = entry_counts()
subprocess.run(["adb", "shell", "-T", "toybox", "nc", "-w", "2", "-W", "2", "10.0.2.2", "34304"],
               input=b"via-tun", capture_output=True, timeout=8)
assert entry_counts()[1] == payloads, "Control UID unexpectedly reached the physical TCP entry"
tap_in_list("Управление подпиской AndroidCheckA")
tap("Проверить пинг всех серверов")
# Three TCP peers already worked before this change. TUIC has no TCP listener,
# so a fourth number must come from its isolated QUIC/HTTPS latency probe.
# Hysteria2 TCP SYN is intentionally not converted into a synthetic UDP RTT.
wait_compact_latencies(min_count=4, attempts=65)
print("Native TUIC ping and original TCP server pings are both visible", flush=True)
for _ in range(30):
    count, sent = entry_counts()
    if count > before:
        break
    time.sleep(.2)
assert count > before and sent == payloads, "Phantom did not open a fresh physical TCP socket"
print("TCP handshake on the physical network with another TUIC node active; zero application bytes", flush=True)
tap("Главная")
node("Подключено")
node("AndroidCheckA")
show_servers()
screenshot("forest-servers")
tap_in_list("Выбрать сервер AndroidCheckB")
tap("Главная")
node("AndroidCheckB")
node("Подключено")
tap("Отключить VPN")
node("Отключено")
show_servers()
tap_in_list("Свернуть подписку AndroidCheckA")
tap("Главная")

# Import the same provider JSON used by the native routing test. The first
# outbound blocks traffic and the observed peer is down, so CONNECTED requires
# the actual Xray balancer and its XHTTP fallback.
balance = Path("app/src/androidTest/assets/provider-balance.json").read_text()
adb("shell", shlex.join(["am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.SEND",
                        "-t", "text/plain", "--es", "android.intent.extra.TEXT", balance]))
show_servers()
tap_in_list("Выбрать сервер Автовыбор")
# This fixture uses a provider-only fallback DNS name. Its routed behavior is verified below.
tap("Главная")
node("Автовыбор")
tap("Подключить VPN")
node("Подключено")
verify_tun_transfer()
verify_tun_transfer("through-tun.phantom.test")
print("Android system DNS resolved the provider-only name and transferred data through TUN", flush=True)
# Lose the physical network, regain it, then prove the requested VPN still transports bytes.
adb("shell", "svc", "data", "disable")
adb("shell", "svc", "wifi", "disable")
for _ in range(24):
    physical = adb("shell", "ip", "-4", "route", "show", "table", "all")
    if not re.search(r"^default .*dev (?:wlan|eth|rmnet|radio)\w*", physical, re.M):
        break
    time.sleep(.5)
else:
    raise AssertionError("Android retained a physical default route during the offline check")
adb("shell", "svc", "wifi", "enable")
try:
    deadline = time.monotonic() + 50
    while True:
        try:
            verify_tun_transfer("through-tun.phantom.test")
            break
        except (AssertionError, subprocess.TimeoutExpired):
            if time.monotonic() >= deadline:
                raise
            time.sleep(1)
    node("Подключено")
    print("Provider DNS and TUN transfer recovered after loss and return of Wi-Fi", flush=True)
finally:
    adb("shell", "svc", "data", "enable")

print("Signed APK: provider Xray JSON connected through the XHTTP fallback", flush=True)
screenshot("provider-auto-connected")
show_servers()
visible_after_scroll("Выбрать сервер Автовыбор")
tap("Главная")
node("Подключено")
tap("Отключить VPN")
node("Отключено")

# Select an installed system app through the real split-tunneling screen.
tap("Настройки")
tap("Туннелирование")
tap("Через VPN только выбранные")
tap("Показывать системные приложения")
tap("Поиск приложений")
adb("shell", "input", "text", "com.android.settings")
adb("shell", "input", "keyevent", "4")
tap_in_list("com.android.settings · Система")
tap("Применить")
node("Туннелирование")
tap("Главная")
component = PACKAGE + "/.service.PhantomQuickTileService"
adb("shell", "cmd", "statusbar", "add-tile", component)
adb("shell", "cmd", "statusbar", "expand-settings")
time.sleep(1)
adb("shell", "cmd", "statusbar", "click-tile", component)
adb("shell", "cmd", "statusbar", "collapse")
node("Подключено", attempts=20)
def app_uid(package):
    packages = adb("shell", "pm", "list", "packages", "-U", package)
    return re.search(r"package:" + re.escape(package) + r" uid:(\d+)", packages).group(1)


# Android programs kernel policy routes by UID. Its service dump need not
# include package names. Check the actual selected UID and a control UID.
rules = adb("shell", "ip", "-4", "rule", "show")
vpn_tables = set(re.findall(r"oif tun\d+[^\n]*?lookup (\S+)", rules))
vpn_ranges = [tuple(map(int, m.groups())) for line in rules.splitlines()
              if any(line.rstrip().endswith("lookup " + table) for table in vpn_tables)
              for m in [re.search(r"uidrange (\d+)-(\d+)", line)] if m]
allowed = int(app_uid("com.android.settings"))
excluded = int(app_uid(PACKAGE))
if not any(low <= allowed <= high for low, high in vpn_ranges) or any(
        low <= excluded <= high for low, high in vpn_ranges):
    raise AssertionError(f"Unexpected per-app routes for {allowed}/{excluded}: {rules}")
adb("shell", "cmd", "statusbar", "expand-settings")
time.sleep(1)
adb("shell", "cmd", "statusbar", "click-tile", component)
adb("shell", "cmd", "statusbar", "collapse")
node("Отключено")

# Scan the public HTTPS subscription through the actual Android camera.
adb("shell", "pm", "grant", PACKAGE, "android.permission.CAMERA")
tap("Сканировать QR-код подписки")
try:
    node("К серверам", attempts=20)
except AssertionError:
    screenshot("qr-failure")
    print(adb("logcat", "-d", "-s", "CameraPreview", "CameraInstance", "CameraManager", "DecoderThread"))
    raise
show_servers()
tap_in_list("Раскрыть подписку OfflineSubscriptionCheck")
node("OfflineSubscriptionCheck", attempts=20)
print("Camera QR imported a subscription without a second confirmation", flush=True)
tap_in_list("Выбрать сервер OfflineSubscriptionCheck")
tap("Главная")
visible_after_scroll("Обновить подписку OfflineSubscriptionCheck", max_swipes=18)
tap("Обновить подписку OfflineSubscriptionCheck")
node("Подписка обновлена")
show_servers()
visible_after_scroll("Управление подпиской OfflineSubscriptionCheck")
tap("Подписка")
node("В магазин")
screenshot("forest-subscriptions")
# Verify that renewal guidance stays reachable on a narrow device.
adb("shell", "wm", "size", "720x1600")
renewal_hint = "Продлить: «Моя подписка» → «Продлить» в магазине."
for attempt in range(10):
    try:
        node(renewal_hint, attempts=1)
        break
    except AssertionError:
        if attempt == 9:
            print("Subscription screen did not expose renewal hint after in-content swipes",
                  flush=True)
            raise
        # At 720x1600, y=1330 falls within bottom NavigationBar, which
        # consumes the gesture instead of scrolling the subscription list.
        # Keep both touch points safely inside the scrollable content.
        adb("shell", "input", "swipe", "350", "1000", "350", "320", "420")
node(renewal_hint)
screenshot("subscriptions-narrow")
adb("shell", "wm", "size", "reset")
for action in ("В магазин",):
    adb("logcat", "-c")
    tap_in_list(action)
    time.sleep(1)
    activity_log = adb("logcat", "-d", "-s", "ActivityTaskManager:I")
    if "go.veor.pro" not in activity_log or "android.intent.action.VIEW" not in activity_log:
        raise AssertionError(f"Browser handoff failed for {action}")
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
adb("shell", "svc", "wifi", "disable")
adb("shell", "svc", "data", "disable")
tap("Главная")
visible_after_scroll("Обновить подписку OfflineSubscriptionCheck", max_swipes=18)
tap("Обновить подписку OfflineSubscriptionCheck")
show_servers()
# After failed offline refresh the new card shows an explicit owner-scoped
# error while preserving servers. Check the current UI contract, not text
# removed with the old duplicate subscription summary.
# The failed owner can be far below the viewport in the ONE unified
# scrolling page. Navigate to that owner; never replace the error assertion.
visible_after_scroll("Управление подпиской OfflineSubscriptionCheck", max_swipes=18)
visible_after_scroll("Ошибка обновления · серверы сохранены", max_swipes=18)
node("Управление подпиской OfflineSubscriptionCheck")
show_servers()
visible_after_scroll("OfflineSubscriptionCheck", max_swipes=18)

try:
    adb("shell", "wm", "user-rotation", "lock", "1")
    tap("Подписка")
    visible_after_scroll("В магазин")
    for tab in ("Главная", "Подписка", "Настройки"):
        node(tab)
    screenshot("phone-landscape")
finally:
    adb("shell", "wm", "user-rotation", "free")

# Treat phone-size CI emulation as a second, tablet-sized form factor without
# launching another Go runtime or requiring a physical tablet on GitHub runners.
# Exercise portrait and landscape (>600dp width), navigation and persisted data.
try:
    adb("shell", "wm", "density", "240")
    for size, shot in (("1600x2560", "tablet-portrait"),
                       ("2560x1600", "tablet-landscape")):
        adb("shell", "wm", "size", size)
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        node("Главная")
        show_servers()
        visible_after_scroll("OfflineSubscriptionCheck")
        tap("Подписка")
        visible_after_scroll("В магазин")
        tap("Настройки")
        node("Настройки")
        screenshot(shot)
finally:
    adb("shell", "wm", "size", "reset")
    adb("shell", "wm", "density", "reset")
