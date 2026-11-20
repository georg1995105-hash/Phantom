#!/usr/bin/env bash
# Execute in a single shell. The emulator-runner action executes each YAML line
# as a separate command, so a multi-line inline script cannot preserve 'set +e'.
set -uo pipefail
# Compose can drive a view while Android still denies clipboard reads to its
# window. Keep this disposable emulator awake during Gradle and the native tests.
adb shell svc power stayon true || exit $?
adb shell input keyevent KEYCODE_WAKEUP || exit $?
adb shell wm dismiss-keyguard || exit $?
timeout --signal=TERM --kill-after=15s 480s ./gradlew --no-daemon -PnativeSmoke :app:connectedDebugAndroidTest
rc=$?
adb logcat -d -s PhantomUserAgent:I PhantomLatencyTest:I
if [ "$rc" -ne 0 ]; then
    echo "Android smoke test failed or exceeded the 480-second hard limit (exit=$rc)"
    echo "::group::PHANTOM ANDROID JNI DIAGNOSTICS"
    adb logcat -d -v threadtime | grep -E -A 100 -B 16 'PhantomNativeSmoke|FATAL EXCEPTION|Fatal signal|JNI DETECTED ERROR|UnsatisfiedLinkError|NoClassDefFoundError|No implementation found|AndroidRuntime|Abort message|A/libc|libbox.so|libgojni.so' | tail -n 650 || true
    echo "=== TEST RESULT XML ==="
    find app/build/outputs/androidTest-results -name '*.xml' -exec sh -c 'echo ==== "$1"; head -c 30000 "$1"; echo' _ {} \; 2>/dev/null || true
    echo "=== TEST RESULTS LIST ==="
    find app/build/outputs/androidTest-results app/build/reports/androidTests -type f 2>/dev/null | head -n 35 || true
    echo "::endgroup::"
fi
exit "$rc"
