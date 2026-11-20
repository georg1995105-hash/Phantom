#!/usr/bin/env bash
set -euo pipefail

# Feed a centered QR frame to the emulator's imagefile camera. Android
# Camera and ZXing process the frame as they would one from a physical camera.
sudo apt-get -qq install --no-install-recommends -y qrencode imagemagick
fixture="https://raw.githubusercontent.com/$GITHUB_REPOSITORY/f3a977d5a3d411f57c2be2452ee4b8e6f95eafe0/scripts/fixtures/import-subscription.txt"
qrencode -s 8 -m 4 -o "$RUNNER_TEMP/phantom-qr.png" "$fixture"
convert "$RUNNER_TEMP/phantom-qr.png" -background white -gravity center \
    -extent 1280x720 -type TrueColor "$RUNNER_TEMP/phantom-subscription.png"
