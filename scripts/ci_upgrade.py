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
    for _ in range(attempts):
        adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
        for item in root.iter("node"):
            values = (item.get("text", "") + "\n" + item.get("content-desc", "")).splitlines()
            if label in values:
                return item
        time.sleep(0.5)
    visible = [n.get("text") or n.get("content-desc") for n in root.iter("node")]
    raise AssertionError(f"Missing UI element {label!r}, visible: {list(filter(None, visible))}")


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
adb("install", "-r", new)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
node("UpgradeCheck")
node("Отключено")
screenshot("home")
tap("Серверы")
node("UpgradeCheck")
tap("В избранное")
screenshot("servers")
tap("Подписка")
node("Добавить подписку или ключ")
screenshot("subscriptions")
tap("Настройки")
node("Тема оформления")
node("Светлая")
screenshot("settings")
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
# Switch the real Android VPN between the TUIC and AnyTLS loopback peers. Android
# establishes a real TUN. The production connectivity check verifies HTTPS.
adb("shell", "svc", "wifi", "enable")
adb("shell", "svc", "data", "enable")
peers = json.loads(Path("app/src/androidTest/assets/peers.json").read_text())
profiles = [peers["tuic"] + "#AndroidCheckA", peers["anytls"] + "#AndroidCheckB"]
payload = json.dumps(profiles, separators=(",", ":"))
adb("shell", shlex.join(["am", "start", "-W", "-n", ACTIVITY, "-a", "android.intent.action.SEND",
                        "-t", "text/plain", "--es", "android.intent.extra.TEXT", payload]))
tap("AndroidCheckA")
tap("Главная")
adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
tap("Подключить VPN")
node("Подключено")
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
time.sleep(4)
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
node("Подключено")
tap("Серверы")
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
vpn_state = adb("shell", "dumpsys", "vpn_management")
if "com.android.settings" not in vpn_state:
    raise AssertionError("Android VPN did not report the selected allowed application")
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
tap("Обновить сведения")
node("Данные могут быть устаревшими", attempts=36)
tap("Серверы")
node("OfflineSubscriptionCheck")
