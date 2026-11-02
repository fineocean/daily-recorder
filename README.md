# Daily Recorder

**A small offline diary app with export/import features.**

Write short entries for each day. Browse past days on a month calendar, swipe to move between days and months, and drag entries to reorder them. You can export the whole diary to a file and import it again.

- No runtime dependencies
- No permissions
- No account
- No network access
- No ads or trackers

Requires Android 11 (API 30) or newer.

## Verifying the APK

Official releases are signed with a key whose certificate has this SHA-256 fingerprint:

```
AA:CC:DA:E0:C0:48:B4:13:22:E6:29:CB:D3:DC:FC:58:21:D5:BE:E4:01:99:A5:C1:D5:83:FD:03:F2:AD:EE:BA
```

An APK you build yourself is signed with your own key, so it has a different fingerprint.

## Building

**Build with just a JDK and r8. It does not need Android SDK or Gradle.**

```sh
./build.sh   # downloads r8.jar on first run, then compiles, dexes and signs dailyrecorder-<version>.apk
```

`build.sh` creates a signing key at `~/.android-keys/release.p12`, or wherever `$KEYSTORE` points. You have to back it up, since an installed app cannot update without it.

## How it works without the SDK

The Android classes the app uses are declared in `buildtools/stubs`, which holds compile-time placeholders with the same signatures as the real ones. They are never packaged into the app, because the phone supplies the real classes. `buildtools/Build.java` is a small manifest and resource-table encoder (for the icon, `src/icon.png`) and an APK packer/signer written in plain Java.

See the notes at the top of `buildtools/stubs` before using a new Android API.

## License

MIT
