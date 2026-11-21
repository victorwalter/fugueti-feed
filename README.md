# Fugueti Feed

Small companion app that lets **[Fugueti](https://github.com/victorwalter/fugueti-launcher)**
show the Google feed (Google Discover) on the page to the left of the home screen.

## Why a separate app?

The Google app only connects its feed to launchers that are *debuggable* (development builds)
or system apps. Regular builds of Fugueti are neither, so Fugueti talks to the Google app through
this bridge instead:

1. Fugueti binds the bridge's `com.android.launcher3.WINDOW_OVERLAY` service.
2. The bridge binds the Google app's feed service **as itself** (it is debuggable, so the Google
   app accepts it) and hands Fugueti a binder that forwards every call.

Making Fugueti itself debuggable would expose the launcher's data over USB debugging, slow it
down and keep it out of the Play Store, so the debuggable part lives in this tiny app instead.

## Safety

- No permissions, no stored data, no network access of its own.
- Only accepts Fugueti builds (`app.fugueti.launcher*`) **signed with the same key** as this app.
- Fugueti recognizes the bridge by its signing certificate and prefers it over other bridges.

## Install

Download `FuguetiFeed-<version>.release.apk` from the
[releases](https://github.com/victorwalter/fugueti-feed/releases), install it, then in Fugueti:
*Settings → Home screen → Show feed* and *Feed provider → Default* (or *Fugueti Feed*).

## Build

Requirements: JDK 17+ and the Android SDK (platform 37).

```bash
./gradlew :app:assembleRelease
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`); it must be the same key used for Fugueti. Without it the APK is signed with the
debug key and Fugueti (release) will not use it.

## License

GPL-3.0-or-later (see [LICENSE](LICENSE)). The bridge interfaces in `app/src/main/aidl` come from
[Lawnchair](https://github.com/LawnchairLauncher/lawnchair) (Apache 2.0).
