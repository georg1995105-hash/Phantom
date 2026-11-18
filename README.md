# Phantom

Личный Android VPN-клиент на sing-box + Xray-core, интерфейс в стиле Happ/Incy.

## Стек

- Kotlin + Jetpack Compose (Material 3)
- Ядра: **Xray-core** (через `libv2ray.aar` из [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) и **sing-box** (через `libbox`, добавляется на шаге 8)
- Один `VpnService`, одновременно работает одно ядро
- Мин. SDK 26 (Android 8.0), target SDK 35, **arm64-v8a, armeabi-v7a, x86_64 и x86**. Для телефона и Android TV скачайте [единый APK](https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-universal-release.apk). Android выбирает нужные библиотеки при установке.

## Протоколы

VLESS (REALITY, XHTTP, gRPC, WS, HTTPUpgrade), VMess, Trojan, Shadowsocks (включая 2022), Hysteria2, TUIC v5, ShadowTLS, WireGuard, SOCKS5, HTTP.

## Совместимость с Remnawave

- Принимает полные Xray JSON-конфиги (массив с `remarks`/`outbounds`/`balancers`/`observatory`/`routing`), запускает «как есть» + добавляет TUN-inbound.
- Заголовки: `User-Agent: Happ/1.0`, `x-hwid`, `x-device-os`, `x-device-model`, `x-ver-os`.
- Читает `subscription-userinfo`, `profile-title`, `profile-update-interval`.
- Mux, XHTTP, Hysteria2, балансировщики.

## Сборка

APK собирается в GitHub Actions (`.github/workflows/build.yml`).

Для ручной установки выпускается `phantom-universal-release.apk`. Для OTA клиент выбирает меньший APK по списку архитектур Android. Идентификатор приложения и сертификат общие. Для PR используется временный ключ. Официальные APK подписываются в ручном релизном workflow.

## План

- [x] 1. Каркас + CI + подпись
- [ ] 2. Имя «PHANTOM», иконка, applicationId
- [ ] 3. Загрузка подписки с заголовками, запуск Xray JSON «как есть»
- [ ] 4. Главный экран в стиле Happ
- [ ] 5. Список серверов с пингом
- [ ] 6. Карточка подписки + первый запуск
- [ ] 7. Per-app proxy
- [ ] 8. sing-box, tuic://, ShadowTLS, автовыбор ядра
