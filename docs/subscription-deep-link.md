# Direct subscription import (Android / Android TV)

Available in Phantom 0.3.32 after official release.

- Download: https://github.com/georg1995105-hash/Phantom/releases/latest/download/phantom-universal-release.apk
- Scheme: `phantom://import?url=` followed by the full HTTP(S) subscription URL encoded exactly once with `encodeURIComponent`.
- Example (non-production): `phantom://import?url=https%3A%2F%2Fexample.org%2Fsub%3Fx%3D1%26y%3D2`.
- On user click, open the URI in Android. Keep a separate “Копировать подписку” action for older clients and browsers that cannot open custom schemes. Do not redirect automatically on page load.
- Do not send subscription links to analytics, third-party URL shorteners, or logs. Use the existing native subscription URL and preserve percent escapes.
- Android's normal TLS/cleartext policy remains unchanged. Import fetches and saves via the existing subscription pipeline and rejects duplicates; it does not activate VPN or request VPN permission.
- `phantom://pair` remains dedicated to the existing phone/TV pairing protocol. No production website modifications are included in this Android PR.
