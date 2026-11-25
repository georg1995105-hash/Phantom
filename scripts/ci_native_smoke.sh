#!/usr/bin/env bash
# Execute in a single shell. The emulator-runner action executes each YAML line
# as a separate command, so a multi-line inline script cannot preserve 'set +e'.
set -uo pipefail
# Compose can drive a view while Android still denies clipboard reads to its
# window. Keep this disposable emulator awake during Gradle and the native tests.
adb shell svc power stayon true || exit $?
adb shell input keyevent KEYCODE_WAKEUP || exit $?
adb shell wm dismiss-keyguard || exit $?
# Run UI clipboard/focus preflight in a fresh emulator session BEFORE
# memory-heavy two-runtime JNI integration tests. AndroidJUnitRunner supports
# 'class' and 'notClass'; neither test is skipped and both commands must pass.
# Compile/package both test APKs separately. On a cold runner dexing and
# packaging consumed most of the 240s preflight deadline before AndroidJUnit
# even started. Keep the existing runtime deadlines and assertions intact.
echo "Preparing debug and instrumentation APKs before runtime deadlines"
timeout --signal=TERM --kill-after=15s 480s ./gradlew --no-daemon --max-workers=1 -PnativeSmoke \
    :app:assembleDebug :app:assembleDebugAndroidTest
prepare_rc=$?
if [ "$prepare_rc" -ne 0 ]; then
    echo "Android test APK preparation failed (exit=$prepare_rc)"
    exit "$prepare_rc"
fi
preflight="xyz.wraithnet.phantom.aaa.AQuickSubscriptionUiTest"
timeout --signal=TERM --kill-after=15s 240s ./gradlew --no-daemon --max-workers=1 -PnativeSmoke \
    "-Pandroid.testInstrumentationRunnerArguments.class=$preflight" :app:connectedDebugAndroidTest
preflight_rc=$?
if [ "$preflight_rc" -eq 0 ]; then
    timeout --signal=TERM --kill-after=15s 480s ./gradlew --no-daemon --max-workers=1 -PnativeSmoke \
        "-Pandroid.testInstrumentationRunnerArguments.notClass=$preflight" :app:connectedDebugAndroidTest
    rc=$?
else
    rc=$preflight_rc
fi
echo "Android isolated UI preflight: $preflight_rc; all remaining instrumentation: $rc"
adb logcat -d -s PhantomUserAgent:I PhantomLatencyTest:I PhantomLatency:D
if [ "$rc" -ne 0 ]; then
    echo "Android smoke test failed (exit=$rc; preflight deadline 240s, remaining tests 480s)"
    echo "::group::PHANTOM ANDROID JNI DIAGNOSTICS"
    adb logcat -d -v threadtime | grep -E -A 100 -B 16 'PhantomNativeSmoke|FATAL EXCEPTION|Fatal signal|JNI DETECTED ERROR|UnsatisfiedLinkError|NoClassDefFoundError|No implementation found|AndroidRuntime|Abort message|A/libc|libbox.so|libgojni.so' | tail -n 650 || true
    echo "=== TEST RESULT XML ==="
    find app/build/outputs/androidTest-results -name '*.xml' -exec sh -c 'echo ==== "$1"; head -c 30000 "$1"; echo' _ {} \; 2>/dev/null || true
    echo "=== TEST RESULTS LIST ==="
    find app/build/outputs/androidTest-results app/build/reports/androidTests -type f 2>/dev/null | head -n 35 || true
    echo "::endgroup::"
fi
exit "$rc"
