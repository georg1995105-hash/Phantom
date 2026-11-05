#!/usr/bin/env python3
"""Package a built preview and the committed native Android evidence for private release."""
import hashlib
from pathlib import Path
import shutil
from zipfile import ZipFile, ZIP_DEFLATED

root = Path(__file__).resolve().parents[1]
out = root / "preview-assets"
out.mkdir(exist_ok=True)
apk = next((root / "app/build/outputs/apk/debugNoMinify").glob("*.apk"))
target = out / "SmartVoiceKeyboard-input-fixes-preview-2026-11-05.apk"
shutil.copy2(apk, target)
with ZipFile(out / "SmartVoiceKeyboard-design-comparison.zip", "w", ZIP_DEFLATED) as z:
    html = (root / "docs/validation/COMPARE.html").read_text().replace(
        "../approved-design/approved_mockups/", "approved_mockups/")
    z.writestr("COMPARE.html", html)
    for folder, archive in (("docs/approved-design/approved_mockups", "approved_mockups"),
                            ("docs/validation/screenshots", "screenshots"),
                            ("tools/voice-evaluation", "voice-evaluation")):
        for path in sorted((root / folder).iterdir()):
            if path.is_file():
                z.write(path, archive + "/" + path.name)
    for name in ("STATUS_RU.md", "PHONE_TEST_RU.md", "remaining-tests.txt"):
        z.write(root / "docs/validation" / name, name)
    z.write(root / "docs/approved-design/TASK_FOR_CODEX.md", "TASK_FOR_CODEX.md")
    for path in (root / "docs/validation/fixes").glob("*"):
        if path.is_file(): z.write(path, "fixes/" + path.name)
for path in (root / "docs/validation/screenshots").glob("*.png"):
    shutil.copy2(path, out / path.name)
assets = sorted(p for p in out.iterdir() if p.suffix in (".apk", ".zip", ".png"))
(out / "SHA256SUMS.txt").write_text("".join(
    hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in assets))
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(out / "RELEASE_NOTES.md").write_text(
    (root / "docs/validation/fixes/REPORT_RU.md").read_text() +
    f"\n\nAPK SHA-256: `{digest}`. Пакет: `ru.smartvoice.keyboard.preview`. Версия: `0.2.0-alpha5-input-fixes-test`.\n" +
    "Архив сравнения: распакуйте ZIP и откройте COMPARE.html. Подпись тестовая; при конфликте подписи удалите только старую SmartVoice Test, стабильную клавиатуру оставьте.\n")
print("Packaged", len(assets), "assets; APK SHA-256:", digest)
