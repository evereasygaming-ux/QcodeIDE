# Release test signing key

`release-test.p12` is a **public, test-only** signing key used by
`.github/workflows/build.yml` to produce the `qcode-release-test-apk` artifact.

- alias: `qcode-release-test`
- store and key password: `qcode-test`
- type: PKCS12

It exists so consecutive CI test builds keep the same signature, which lets
`adb install -r` upgrade the app in place. Without that, every run needed an
uninstall, and an uninstall deletes the provisioned Linux environment
(~2.5 GB) before each on-device verification.

This key must never sign a distributed build. The shipping key lives in the
admin-only `release` environment (`RELEASE_KEYSTORE_BASE64` and friends).
