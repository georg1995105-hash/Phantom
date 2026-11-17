# sing-box / libbox third-party integration

This project integrates the third-party [sing-box Android library](https://github.com/singbox-android/libbox) at version 1.14.4 for TUIC v5 and AnyTLS transports. The upstream release artifact is retrieved from its public GitHub Releases page, verified against the published SHA-256 and repackaged reproducibly at build time to avoid conflicts with the independent AndroidLibXrayLite gomobile runtime.

Upstream: https://github.com/singbox-android/libbox
Library version: 1.14.4
Upstream archive: https://github.com/singbox-android/libbox/releases/download/1.14.4/libbox.aar
Upstream SHA-256: d2685924a657705beda44e94c6dc499e781df1ad20d65474e3591ec2f8b96465

**License: GNU General Public License version 3** — https://github.com/singbox-android/libbox/blob/main/LICENSE

Distributing an application that combines GPL-3.0 components can impose obligations on the corresponding source code and distribution. A public repository alone does not necessarily satisfy all of those obligations. The app owner must verify license compliance before distributing a build containing the library. This document does not grant an independent license to the Phantom application code.

The runtime namespace and JNI library name are modified mechanically in CI. The upstream implementation of sing-box is otherwise not modified. Source code, build instructions and attribution are available via the upstream repository.
