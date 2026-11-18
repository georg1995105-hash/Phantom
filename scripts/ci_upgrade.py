"""Verify the signed production upgrade and its four destinations on the CI emulator."""
import re
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
adb("install", "-r", new)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
node("UpgradeCheck")
node("Отключено")
tap("Серверы")
node("UpgradeCheck")
tap("В избранное")
tap("Подписка")
node("Добавить подписку или ключ")
tap("Настройки")
node("Тема оформления")
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
tap("Серверы")
node("Убрать из избранного")
