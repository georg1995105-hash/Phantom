#!/usr/bin/env bash
set -euo pipefail

# Feed a QR frame to the emulator's imagefile camera. Android
# Camera and ZXing process the frame as they would one from a physical camera.
sudo apt-get -qq install --no-install-recommends -y qrencode imagemagick
fixture="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/f3a977d5a3d411f57c2be2452ee4b8e6f95eafe0/scripts/fixtures/import-subscription.txt"
qrencode -s 4 -m 4 -o "$RUNNER_TEMP/phantom-qr.png" "$fixture"
# The Pixel 6 imagefile preview shifts the 720px source 208px to the right
# before scaling. Offset the fixture so the full QR is inside ZXing's scan area.
convert -size 720x720 canvas:white "$RUNNER_TEMP/phantom-qr.png" \
    -gravity center -geometry -208+0 -composite -type TrueColor \
    "$RUNNER_TEMP/phantom-subscription.png"
