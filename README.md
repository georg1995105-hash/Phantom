# Phantom

Личный Android VPN-клиент на sing-box + Xray-core, интерфейс в стиле Happ/Incy.

## Стек

- Kotlin + Jetpack Compose (Material 3)
- Ядра: **Xray-core** (через `libv2ray.aar` из [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) и **sing-box** (через `libbox`, добавляется на шаге 8)
- Один `VpnService`, одновременно работает одно ядро
- Мин. SDK 26 (Android 8.0), target SDK 35, **arm64-v8a**

## Протоколы

VLESS (REALITY, XHTTP, gRPC, WS, HTTPUpgrade), VMess, Trojan, Shadowsocks (включая 2022), Hysteria2, TUIC v5, ShadowTLS, WireGuard, SOCKS5, HTTP.

## Совместимость с Remnawave

- Принимает полные Xray JSON-конфиги (массив с `remarks`/`outbounds`/`balancers`/`observatory`/`routing`), запускает «как есть» + добавляет TUN-inbound.
- Заголовки: `User-Agent: Happ/1.0`, `x-hwid`, `x-device-os`, `x-device-model`, `x-ver-os`.
- Читает `subscription-userinfo`, `profile-title`, `profile-update-interval`.
- Mux, XHTTP, Hysteria2, балансировщики.

## Сборка

APK собирается в GitHub Actions (`.github/workflows/build.yml`).

**Первый запуск** автоматически:
1. Генерирует keystore.
2. Кладёт его в GitHub Secrets (`APP_KEYSTORE_BASE64`, `APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS`, `APP_KEY_PASSWORD`).
3. Скачивает `libv2ray.aar`.
4. Собирает release APK, подписывает, выкладывает как артефакт `phantom-arm64-v8a`.

**Повторные запуски** используют сохранённый keystore.

## План

- [x] 1. Каркас + CI + подпись
- [ ] 2. Имя «PHANTOM», иконка, applicationId
- [ ] 3. Загрузка подписки с заголовками, запуск Xray JSON «как есть»
- [ ] 4. Главный экран в стиле Happ
- [ ] 5. Список серверов с пингом
- [ ] 6. Карточка подписки + первый запуск
- [ ] 7. Per-app proxy
- [ ] 8. sing-box, tuic://, ShadowTLS, автовыбор ядра
