#!/usr/bin/env bash
set -uo pipefail
native=0
upgrade=0
bash scripts/ci_native_smoke.sh || native=$?
python3 scripts/ci_upgrade.py previous.apk app/build/outputs/apk/release/phantom-universal-release.apk || upgrade=$?
if ((native != 0 || upgrade != 0)); then
    tail -n 80 "$RUNNER_TEMP/peers/peers.log"
fi
echo "Android native tests: $native, signed upgrade and VPN: $upgrade"
((native == 0 && upgrade == 0))
