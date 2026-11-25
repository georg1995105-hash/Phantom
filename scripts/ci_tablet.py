#!/usr/bin/env python3
"""Landscape touch-tablet smoke test for the REAL signed universal Phantom APK.

Checks actual UI/import, not just static Compose tests. Source QR frame is
provided by the emulator imagefile camera (ci_qr_camera.sh).
"""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "xyz.wraithnet.phantom"
ACTIVITY = PACKAGE + "/.MainActivity"
APK = "app/build/outputs/apk/release/phantom-universal-release.apk"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, stderr=subprocess.STDOUT)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/phantom-tablet.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/phantom-tablet.xml"))
    return list(root.iter("node"))


def match(label):
    return next((n for n in nodes() if label in
                 (n.get("text", ""), n.get("content-desc", ""))), None)


def visible(label, swipes=0):
    for index in range(swipes + 1):
        for _ in range(6):
            n = match(label)
            if n is not None:
                return n
            time.sleep(.35)
        if index < swipes:
            adb("shell", "input", "swipe", "800", "780", "800", "360", "300")
    raise AssertionError("Tablet UI missing %r. Visible: %r" % (
        label, [n.get("text") or n.get("content-desc") for n in nodes()
                if n.get("text") or n.get("content-desc")][:65]))


def tap(label, swipes=0):
    n = visible(label, swipes)
    bounds = list(map(int, re.findall(r"\d+", n.get("bounds", ""))))
    assert len(bounds) == 4, (label, n.attrib)
    adb("shell", "input", "tap", str((bounds[0]+bounds[2])//2),
        str((bounds[1]+bounds[3])//2))


def capture(name):
    Path("upgrade-screens").mkdir(exist_ok=True)
    with open("upgrade-screens/" + name + ".png", "wb") as target:
        subprocess.run(["adb", "exec-out", "screencap", "-p"],
                       stdout=target, check=True)


print("Tablet: install universal Phantom", flush=True)
assert Path(APK).is_file(), "Only the universal signed APK is supported"
adb("install", "-r", "-g", APK)
adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
adb("shell", "settings", "put", "system", "user_rotation", "0")
# A 1600x1000 screen at 240 dpi gives 667dp smallestWidth: TRUE tablet UI.
adb("shell", "wm", "size", "1600x1000")
adb("shell", "wm", "density", "240")
adb("shell", "pm", "grant", PACKAGE, "android.permission.CAMERA")
adb("logcat", "-c")
adb("shell", "am", "start", "-W", "-n", ACTIVITY)
visible("Phantom")
visible("Добавить подписку или ключ", 3)
capture("tablet-landscape-before-scan")
tap("Добавить подписку или ключ", 3)
tap("Сканировать QR")
# No app orientation transition is allowed when a landscape tablet scans QR.
# Read app-only logs, which never include the scanned QR payload.
for attempt in range(30):
    log = adb("logcat", "-d", "-s", "PhantomQrCapture:I", "*:S")
    if "PhantomQrCapture" in log:
        break
    time.sleep(.4)
else:
    raise AssertionError("QR scanner did not start (missing PhantomQrCapture)")
orientation_lines = [line for line in log.splitlines() if "PhantomQrCapture" in line]
print("Tablet QR orientation:", orientation_lines[-5:], flush=True)
assert any("requested=0 actual=2 landscape=true" in line for line in orientation_lines), (
    "Landscape tablet QR scanner did not remain in landscape: " +
    repr(orientation_lines[-5:]))
assert not any("actual=1" in line for line in orientation_lines), (
    "Scanner switched to portrait on a landscape tablet")

# With a real camera QR imagefile frame, a successful scan returns to Home and
# imports the TEST-only VLESS key. This is NOT a mocked onScanQr callback.
for _ in range(40):
    if match("OfflineSubscriptionCheck") is not None:
        break
    time.sleep(.5)
else:
    capture("tablet-qr-failure")
    raise AssertionError("Tablet camera failed to scan/import QR into Phantom")
capture("tablet-landscape-after-qr-import")
adb("shell", "uiautomator", "dump", "/sdcard/phantom-tablet.xml")
ui = adb("shell", "cat", "/sdcard/phantom-tablet.xml")
assert "OfflineSubscriptionCheck" in ui, "QR not imported into real server list"
assert "Добавить подписку или ключ" in ui or     any(n.get("content-desc") == "Добавить подписку или ключ" for n in nodes()),     "Import action missing after QR"
last = adb("logcat", "-d", "-s", "PhantomQrCapture:I", "*:S")
assert "requested=0 actual=2 landscape=true" in last, last[-1700:]
print("Tablet LANDSCAPE scan → decoded VLESS key → landscape Home passed", flush=True)

# Actual landscape tablet: all internal settings routes must remain clickable.
tap("Вкладка Настройки")
visible("Подключение")
capture("tablet-settings-grid")
tap("Подключение")
tap("DNS", 3)
visible("Сохранить")
adb("shell", "input", "keyevent", "BACK")
tap("Пинг серверов", 3)
visible("Автоматический пинг TUIC")
adb("shell", "input", "keyevent", "BACK")
adb("shell", "input", "keyevent", "BACK")
adb("shell", "input", "swipe", "800", "350", "800", "800", "300")
tap("Тема оформления", 3)
visible("Выберите внешний вид Phantom")
adb("shell", "input", "keyevent", "BACK")
tap("Туннелирование", 3)
visible("Приложения и VPN")
adb("shell", "input", "keyevent", "BACK")
tap("Фоновая работа", 3)
visible("Работа при выключенном экране", 3)
adb("shell", "input", "keyevent", "BACK")
tap("Резервная копия", 3)
visible("Сохранить резервную копию")
visible("Восстановить из файла")
adb("shell", "input", "keyevent", "BACK")
tap("О приложении", 3)
visible("Проверить обновление приложения", 3)
adb("shell", "input", "keyevent", "BACK")
tap("Автоподключение", 3)
tap("Автоподключение")
assert adb("shell", "pidof", "-s", PACKAGE).strip(), "Settings navigation killed Phantom"
print("Tablet settings: internal navigation and auto-connect toggle passed", flush=True)
