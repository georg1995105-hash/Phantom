# Upstream

SmartVoiceKeyboard is a modified version of HeliBoard (https://github.com/Helium314/HeliBoard),
tag v4.2, commit 0c60dffb03a4e2e7a37cb82210f5299f93c03979, which itself is based on AOSP LatinIME / OpenBoard.

- License: GNU GPL v3 (see LICENSE). Some parts are Apache-2.0 (LICENSE-Apache-2.0) and
  CC-BY-SA-4.0 (LICENSE-CC-BY-SA-4.0), as in upstream.
- Package id and app name were changed so the keyboard installs side by side with HeliBoard and Gboard.
- No proprietary Gboard code or Google gesture library is included. Gesture typing uses an own
  Kotlin decoder (see app/src/main/java/helium314/keyboard/latin/gesture).
