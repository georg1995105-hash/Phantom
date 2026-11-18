"""Verify the signed production upgrade and its four destinations on the CI emulator."""
from pathlib import Path
import re
import json
import os
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

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
    # cause a *system Pixel Launcher* ANR, independent of Phantom. Recover
    # only that specific system dialog, never an app crash or missing UI.
    for _ in range(max(attempts, 20) if label == "UpgradeCheck" else attempts):
        adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
        for item in root.iter("node"):
            values = (item.get("text", "") + "\n" + item.get("content-desc", "")).splitlines()
            if label in values:
                return item
        visible_text = " ".join((item.get("text") or "") for item in root.iter("node"))
        if (label == "UpgradeCheck" and not launcher_recovered and
                "Pixel Launcher isn't responding" in visible_text):
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


def node_prefix(prefix, attempts=20):
    """Find a dynamic latency reading without assuming its numerical value."""
    for _ in range(attempts):
        adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
        for item in root.iter("node"):
            if any((item.get(field) or "").startswith(prefix) for field in ("text", "content-desc")):
                return item
        time.sleep(0.5)
    raise AssertionError(f"Missing UI reading starting with {prefix!r}")


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


def screenshot(name):
    path = Path("upgrade-screens")
    path.mkdir(exist_ok=True)
    (path / (name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))


old, new, native = sys.argv[1:]
# Native smoke installs a debug package on this disposable emulator.
if PACKAGE in adb("shell", "pm", "list", "packages", PACKAGE):
    adb("uninstall", PACKAGE)
adb("install", "-g", old)
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
tap("Серверы")
tap("В избранное")
tap("Главная")
adb("install", "-r", new)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
node("UpgradeCheck")
node("Отключено")
node("PING")
node("Обновить выбранную подписку")
tap("Обновить выбранную подписку")
node("UpgradeCheck")
screenshot("home")
tap("PING")
node("UpgradeCheck")
node("Убрать из избранного")
screenshot("servers")
tap("Подписка")
node("Добавить подписку или ключ")
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
tap("Серверы")
node("Убрать из избранного")


# The arm64 APK upgrade above verifies the installed package and user data.
# Use the signed release matching this emulator's CPU for the native TUN run.
adb("install", "-r", native)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
tap("Серверы")
node("Убрать из избранного")
tap("Настройки")
tap("Тема оформления")
tap_in_list("Тёмно-зелёная")
adb("shell", "input", "keyevent", "4")
node("Тёмно-зелёная")
screenshot("forest-settings")
tap("Серверы")
# Switch the real Android VPN between the TUIC and AnyTLS loopback peers. Android
# establishes a real TUN. The production connectivity check verifies HTTPS.
adb("shell", "svc", "wifi", "enable")
adb("shell", "svc", "data", "enable")
peers = json.loads(Path("app/src/androidTest/assets/peers.json").read_text())
profiles = [
    peers["tuic"] + "#AndroidCheckA",
    peers["hysteria2"] + "#QuicProbe",
    peers["anytls"] + "#AndroidCheckB",
    # A TCP-only regression fixture: real HTTPS endpoint, deliberately not a VPN server.
    "vless://00000000-0000-4000-8000-000000000001@example.com:443#PhysicalTcpProbe",
]
payload = json.dumps(profiles, separators=(",", ":"))
adb("shell", shlex.join(["am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.SEND",
                        "-t", "text/plain", "--es", "android.intent.extra.TEXT", payload]))
tap("AndroidCheckA")
tap("Главная")
adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
tap("Подключить VPN")
node("Подключено")
screenshot("forest-home-connected")
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
time.sleep(4)
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
node("Подключено")
tap("Серверы")
# Regression: a remote TCP probe must bypass the running Android VPN and use
# validated physical network DNS+socket; the selected TUIC still reports VPN RTT.
node("PhysicalTcpProbe")
node_prefix("VPN · ")
node_prefix("QUIC · ")
tap("Главная")
tap("PING")
node_prefix("TCP · ")
node_prefix("VPN · ")
screenshot("forest-servers")
tap("AndroidCheckB")
tap("Главная")
node("AndroidCheckB")
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

# Use HTTPS from the checked-out public commit, with no subscriber credentials.
sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
# Pull request workflows check out a generated merge commit. Use the head
# commit for the externally retrievable raw file.
with open(os.environ["GITHUB_EVENT_PATH"]) as event_file:
    sha = json.load(event_file).get("pull_request", {}).get("head", {}).get("sha", sha)
url = f"https://raw.githubusercontent.com/{os.environ['GITHUB_REPOSITORY']}/{sha}/scripts/fixtures/import-subscription.txt"
adb("shell", shlex.join(["am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.SEND",
                        "-t", "text/plain", "--es", "android.intent.extra.TEXT", url]))
tap("Подписка")
node("OfflineSubscriptionCheck", attempts=20)
tap("Серверы")
tap("OfflineSubscriptionCheck")
tap("Главная")
tap("Обновить выбранную подписку")
tap("Подписка")
time.sleep(4)
try:
    node("Данные могут быть устаревшими", attempts=1)
except AssertionError:
    pass
else:
    raise AssertionError("Online subscription refresh failed")
node("OfflineSubscriptionCheck")
screenshot("forest-subscriptions")
# Verify that the renewal hint is centered and wraps on a narrow device.
adb("shell", "wm", "size", "720x1600")
node("В магазине выберите „Моя подписка“ → „Продлить“")
screenshot("subscriptions-narrow")
adb("shell", "wm", "size", "reset")
for action in ("Купить подписку Phantom", "Продлить подписку Phantom"):
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
tap("Обновить выбранную подписку")
tap("Подписка")
node("Данные могут быть устаревшими", attempts=36)
tap("Серверы")
node("OfflineSubscriptionCheck")
