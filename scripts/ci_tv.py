"""Exercise the universal APK with an Android TV launcher and D-pad on the 32-bit TV image."""
import json
import re
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
    raise AssertionError(
        f"Missing TV label {label!r}. Visible: {safe[:32]}, reason: {diagnostic[:20]}"
    )


assert "android.software.leanback" in adb("shell", "pm", "list", "features")
adb("install", "-g", "app/build/outputs/apk/release/phantom-universal-release.apk")
assert "primaryCpuAbi=x86\n" in adb("shell", "dumpsys", "package", PACKAGE) + "\n"
resolve = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a",
              "android.intent.action.MAIN", "-c", CATEGORY, PACKAGE)
assert PACKAGE + "/.MainActivity" in resolve
cases = json.loads(Path("app/src/androidTest/assets/peers.json").read_text())
# Diagnose whether the failure belongs to all VPN tunnels, or specifically
# the second Go runtime. Never treat a failed TUIC check as a passing build.
component = resolve.splitlines()[-1]
for index, (protocol, label) in enumerate((
    ("VLESS-TCP", "TVCheckXray"), ("tuic", "TVCheckTUIC")
)):
    if index:
        assert "Success" in adb("shell", "pm", "clear", PACKAGE)
    adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
    profile = cases[protocol] + "#" + label
    adb("shell", shlex.join(["am", "start", "-W", "-a", "android.intent.action.VIEW",
                            "-d", profile, "-n", PACKAGE + "/.MainActivity"]))
    wait_label(label)
    adb("shell", "am", "force-stop", PACKAGE)
    launch = adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                 "-c", CATEGORY, "-n", component)
    assert "Status: ok" in launch, launch
    wait_label("Подключить VPN")
    key("DPAD_CENTER")
    try:
        wait_label("Подключено")
    except AssertionError as ex:
        raise AssertionError(f"TV {protocol} tunnel did not connect: {ex}") from ex
    key("DPAD_CENTER")
    wait_label("Отключено")
    print(f"TV x86 native {protocol}: connected and disconnected", flush=True)
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
