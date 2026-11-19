"""Exercise the universal APK with an Android TV launcher and D-pad on the 32-bit TV image."""
import json
from pathlib import Path
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "xyz.wraithnet.phantom"
CATEGORY = "android.intent.category.LEANBACK_LAUNCHER"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True).strip()


def key(button):
    adb("shell", "input", "keyevent", "KEYCODE_" + button)
    time.sleep(.25)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/tv-ui.xml")
    return list(ET.fromstring(adb("shell", "cat", "/sdcard/tv-ui.xml")).iter("node"))


def wait_label(label):
    for _ in range(18):
        visible = [value for node in nodes() for field in ("text", "content-desc")
                   for value in node.get(field, "").splitlines() if value]
        if label in visible:
            return
    safe = [value for value in visible if "://" not in value]
    raise AssertionError(f"Missing TV label {label!r}. Visible: {safe[:32]}")


assert "android.software.leanback" in adb("shell", "pm", "list", "features")
adb("install", "-g", "app/build/outputs/apk/release/phantom-universal-release.apk")
assert "primaryCpuAbi=x86\n" in adb("shell", "dumpsys", "package", PACKAGE) + "\n"
resolve = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a",
              "android.intent.action.MAIN", "-c", CATEGORY, PACKAGE)
assert PACKAGE + "/.MainActivity" in resolve
adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
profile = json.loads(Path("app/src/androidTest/assets/peers.json").read_text())["tuic"] + "#TVCheck"
adb("shell", shlex.join(["am", "start", "-W", "-a", "android.intent.action.VIEW",
                        "-d", profile, "-n", PACKAGE + "/.MainActivity"]))
wait_label("TVCheck")
adb("shell", "am", "force-stop", PACKAGE)
# TV launchers query activities by category, then start the resolved component explicitly.
component = resolve.splitlines()[-1]
launch = adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
             "-c", CATEGORY, "-n", component)
assert "Status: ok" in launch, launch
wait_label("Подключить VPN")
key("DPAD_CENTER")
wait_label("Подключено")
key("DPAD_CENTER")
wait_label("Отключено")
# Move to the bottom navigation using only directional keys.
for _ in range(7):
    key("DPAD_DOWN")
for _ in range(4):
    key("DPAD_LEFT")
key("DPAD_RIGHT")
key("DPAD_CENTER")
wait_label("В избранное")
key("BACK")
wait_label("Подключить VPN")
print("Android TV launcher, universal x86 runtime, D-pad VPN, server list and Back passed")
