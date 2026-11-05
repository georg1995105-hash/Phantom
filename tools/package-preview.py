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
target = out / "SmartVoiceKeyboard-approved-preview-2026-11-05.apk"
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
for path in (root / "docs/validation/screenshots").glob("*.png"):
    shutil.copy2(path, out / path.name)
assets = sorted(p for p in out.iterdir() if p.suffix in (".apk", ".zip", ".png"))
(out / "SHA256SUMS.txt").write_text("".join(
    hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n" for p in assets))
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(out / "RELEASE_NOTES.md").write_text(f"""Тестовая **SmartVoice Test** по четырём утверждённым макетам от 05.11.2026.

APK устанавливается отдельно: `ru.smartvoice.keyboard.preview`, версия `0.2.0-alpha5-reference-test`. Стабильное приложение не заменяется. SHA-256 APK: `{digest}`. Все контрольные суммы — в `SHA256SUMS.txt`.

В `SmartVoiceKeyboard-design-comparison.zip` откройте `COMPARE.html`: оригиналы рядом с четырьмя настоящими системными скриншотами Android. Внутри также подробный отчёт, сценарий проверки на телефоне и 30 фраз для оценки диктовки. Скриншоты сняты в рабочей облачной среде с локальной сборки тех же Android-исходников; приложенная APK повторно собрана существующим workflow GitHub Actions.

Реализованы графитовая клавиатура и верхняя панель, функции 3×3 и редактор, категории/поиск эмодзи, двухколоночный буфер с настоящими изображениями, ссылками, поиском и закреплением. Сохранены пользовательские настройки. Голосовой ввод защищён от запоздалых ответов, повторных final, бесконечных повторов и продления срока после «Готово».

Проверена локальная сборка, подпись, установка, русский ввод с тремя кандидатами, основные панели и системный clipboard с текстом, URL и изображением на Android 10/API 29. Unit-тесты: **215 / 204 успешно / 11 прежних падений**. Исходная база: **198 / 181 / 17**. Полностью зелёный CI и чистый lint не заявляются; перечень ошибок и ограничения — `STATUS_RU.md`.

Точность реальной русской диктовки и плавность на телефоне не измерены; WER/CER — 0/30 замеров. Более новые Android, все приложения-получатели, жесты и полный сценарий требуют телефона. Стандартное Android composing-подчёркивание может сохраняться.

Черновой PR: https://github.com/georg1995105-hash/SmartVoiceKeyboard/pull/7

**Приватный prerelease. Main не изменён. Стабильный выпуск — только после проверки владельцем и явного одобрения.**
""")
print("Packaged", len(assets), "assets; APK SHA-256:", digest)
