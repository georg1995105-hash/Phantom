"""Verify the signed production upgrade and its four destinations on the CI emulator."""
from pathlib import Path
import re
import json
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "xyz.wraithnet.phantom"
ACTIVITY = PACKAGE + "/.MainActivity"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)


def node(label):
    for _ in range(8):
        adb("shell", "uiautomator", "dump", "/sdcard/phantom-ui.xml")
        root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-ui.xml"))
        for item in root.iter("node"):
            if label in (item.get("text"), item.get("content-desc")):
                return item
        time.sleep(0.5)
    raise AssertionError(f"Missing UI element {label!r}")


def tap(label):
    left, top, right, bottom = map(int, re.findall(r"\d+", node(label).get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def screenshot(name):
    path = Path("upgrade-screens")
    path.mkdir(exist_ok=True)
    (path / (name + ".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))


old, new = sys.argv[1:]
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


# Exercise the installed APK with two disposable direct Xray profiles. Android
# establishes a real TUN. The production connectivity check verifies HTTPS.
adb("shell", "svc", "wifi", "enable")
adb("shell", "svc", "data", "enable")
profiles = [{"remarks": name, "outbounds": [{"tag": "proxy", "protocol": "freedom"}]}
            for name in ("AndroidCheckA", "AndroidCheckB")]
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
