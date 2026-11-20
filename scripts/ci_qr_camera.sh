#!/usr/bin/env bash
set -euo pipefail

# Put the public subscription fixture directly in the emulator camera's
# field of view. The app still uses Android Camera and ZXing end to end.
sudo apt-get -qq install -y qrencode
resources="$ANDROID_HOME/emulator/resources"
fixture="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/f3a977d5a3d411f57c2be2452ee4b8e6f95eafe0/scripts/fixtures/import-subscription.txt"
qrencode -s 16 -m 4 -o "$resources/phantom-subscription.png" "$fixture"
cat > "$resources/Toren1BD.posters" <<'POSTER'
poster phantom
size 1 1
position 0 0 -1.5
rotation 0 0 0
default phantom-subscription.png
POSTER
