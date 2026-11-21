# PHANTOM VPN

**VPN-клиент для Android, планшетов и Android TV.** Подписки и отдельные ключи, удобный выбор серверов и управление подключением.

### Скачать

**[⬇ Скачать Phantom VPN — последняя стабильная версия (универсальный APK)](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-universal-release.apk)**

[Страница загрузки](https://app.veor.pro/) · [Лёгкий APK для ARM64](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-arm64-v8a-release.apk) · [История версий](https://github.com/georg1995105-hash/Phantom/releases/latest) · [Все релизы](https://github.com/georg1995105-hash/Phantom/releases)

> Ссылка «Скачать» **не привязана к номеру версии**. Она ведёт на универсальный APK из последнего официального стабильного GitHub Release. Черновые, тестовые и beta-выпуски не заменяют стабильную версию.

| Устройство | APK |
| --- | --- |
| **Телефон / планшет / Android TV** | [Универсальный — все поддерживаемые архитектуры](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-universal-release.apk) |
| Android ARM64 | [Компактная ARM64-сборка](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-arm64-v8a-release.apk) |
| Android ARM32 | [Компактная ARM32-сборка](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-armeabi-v7a-release.apk) |
| Android x86 / x86_64 | [x86](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-x86-release.apk) · [x86_64](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-x86_64-release.apk) |

### Установка

1. Скачайте APK для своего устройства. Если не знаете архитектуру, используйте **универсальный APK**.
2. Установите файл через Android. Если система попросит разрешение на установку из браузера, подтвердите его для используемого браузера.
3. Добавьте ссылку на подписку либо отдельный ключ и выберите сервер.
4. Новые официальные версии можно устанавливать поверх старых без удаления приложения. Android при необходимости запросит подтверждение установки.

**Важно:** APK из Pull Requests и тестовых сборок не предназначены для обновления официально подписанного приложения.

### Возможности

- Подписки Remnawave и совместимых сервисов, а также отдельные ключи.
- VLESS/REALITY/XHTTP, VMess, Trojan, Shadowsocks, Hysteria2; TUIC и AnyTLS используют отдельный процесс sing-box.
- Четыре вкладки, информация о подписке, список серверов, ручная проверка задержки.
- DNS-настройки, маршрутизация отдельных приложений, QR-коды, темы оформления.
- Интерфейс для телефона, планшета и Android TV; обновления через GitHub Releases.

Доступность и качество отдельных протоколов зависят от сервера и сети. CI-тесты не заменяют проверку на реальных устройствах.

### Обновления и безопасность

Стабильные сборки публикуются только через [официальные GitHub Releases](https://github.com/georg1995105-hash/Phantom/releases). Встроенный загрузчик OTA проверяет SHA-256, версию, имя пакета и подпись перед установкой; Android может потребовать подтверждение. Если GitHub недоступен в сети, проверьте доступ из браузера через работающий VPN.

### Исходный код и разработка

Этот репозиторий содержит исходники Android-клиента. Источником опубликованных сборок являются соответствующие **теги Releases**; разработка и проверка новых функций ведутся в отдельных ветках и Pull Requests. Состояние ветки `main` может отличаться от последнего опубликованного тега.

- [История релизов и APK](https://github.com/georg1995105-hash/Phantom/releases)
- [Проверки GitHub Actions](https://github.com/georg1995105-hash/Phantom/actions/workflows/build.yml)
- [Описание использованных нативных библиотек](THIRD_PARTY_LIBBOX.md)
- [Подробные изменения](RELEASE_NOTES.md)

**PHANTOM VPN** — Android-приложение. Кнопка скачивания не оформляет платную подписку. Для покупки и продления используется отдельный [официальный магазин](https://go.veor.pro/).
