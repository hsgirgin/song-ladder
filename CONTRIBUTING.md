# Contributing

## Release APK signing

Release APKs must be signed with the same keystore for Android to accept them as
updates. When configured, CI reads the keystore and credentials from the
`SONG_LADDER_KEYSTORE_BASE64`, `SONG_LADDER_KEYSTORE_PASSWORD`,
`SONG_LADDER_KEY_ALIAS`, and `SONG_LADDER_KEY_PASSWORD` GitHub Actions secrets,
and publishes the signed release APK for pushes and manual runs. If those secrets
are not configured, CI skips the signed release artifact and still verifies the
debug build. Increment `versionCode` in `app/build.gradle.kts` for each release.
Existing debug APK installations may need one uninstall before the first signed
release can be installed.
