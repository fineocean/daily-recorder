# Daily Recorder

**A small offline diary app with export/import features.**

Write short entries for each day. Browse past days on a month calendar, swipe to move between days and months, and drag entries to reorder them. You can export the whole diary to a file and import it again.

- No Android SDK, Gradle, r8
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

**Build with just a JDK. It does not need the Android SDK, Gradle, or any download.**

```sh
java Build.java   # compiles, dexes and signs dailyrecorder-<version>.apk
```

The first build creates a signing key at `~/.android-keys/release.p12`, or wherever `$KEYSTORE` points. You have to back it up, since an installed app cannot update without it.

## How it works without the SDK

The Android classes the app uses are declared in `stubs`, which holds compile-time placeholders with the same signatures as the real ones. They are never packaged into the app, because the phone supplies the real classes. `Build.java`, in plain Java, does the rest of what the SDK's tools would:

- converts the compiled classes into Android's `classes.dex`, including turning lambdas into small classes
- encodes the manifest and the resource table (for the icon, `src/icon.png`)
- packs and signs the APK

The dex converter only handles the Java features this app uses. If you use one it doesn't know, the build stops with an "unsupported: ..." message rather than producing a broken app.

See the notes at the top of `stubs` before using a new Android API.

## License

MIT
