#!/usr/bin/env bash
set -uo pipefail
native=0
upgrade=0
bash scripts/ci_native_smoke.sh || native=$?
python3 scripts/ci_upgrade.py previous.apk app/build/outputs/apk/release/phantom-x86_64-release.apk || upgrade=$?
if ((native != 0 || upgrade != 0)); then
    tail -n 80 "$RUNNER_TEMP/peers/peers.log"
fi
beta=0
beta_apk=app/build/outputs/apk/beta/phantom-beta-universal-release.apk
if [[ -f "$beta_apk" ]]; then
    adb install -r "$beta_apk" || beta=$?
    if (( beta == 0 )); then
        adb shell pm path xyz.wraithnet.phantom.beta | grep -q 'base.apk' || beta=$?
    fi
    if (( beta == 0 )); then
        adb shell am start -W -n xyz.wraithnet.phantom.beta/xyz.wraithnet.phantom.MainActivity || beta=$?
    fi
fi
echo "Android native tests: $native, signed upgrade and VPN: $upgrade, beta install: $beta"
((native == 0 && upgrade == 0 && beta == 0))
