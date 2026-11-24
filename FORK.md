# CleanYourTV SmartTube Shelves

This CleanYourTV launcher fork puts three SmartTube shelves directly above the app dock. Continue Watching combines SmartTube Watch Next with a local saved list. Recommendations and Subscriptions come from the feeds SmartTube publishes to Android TV.

Use Left and Right within a shelf and Up or Down between shelves. The focused thumbnail settles into the second slot where there is room to scroll. Details stay aligned with the left edge. OK opens the video in SmartTube. Hold OK to save or remove it from Continue Watching.

## Requirements

- Android TV 8.0 or newer and SmartTube Stable (`org.smarttube.stable`).
- The dock layout with `top_shelf` enabled. The launcher needs permission to read TV listings.
- SmartTube controls which rows it publishes and when it refreshes them. The launcher displays a status for missing permission, a temporary provider failure, or a successful read with no videos.

## Permissions and data

The fork adds `READ_TV_LISTINGS` and `com.android.providers.tv.permission.READ_EPG_DATA` to read published TV rows, and `INTERNET` to load HTTPS artwork from those rows. Thumbnail loading starts when a card comes into view. The launcher caches decoded artwork in memory and falls back to the video title when artwork is unavailable.

SmartTube handles playback, sign-in, and feed publishing. The launcher stores manual saves and hidden entries in its own app preferences. It leaves the SmartTube account's history and saved lists alone.

## Build from source

Use JDK 17, Android SDK Platform 34, and Build Tools 34.0.0. From `launcher/` on Windows, run:

```powershell
.\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

The APK is under `launcher/app/build/outputs/apk/release/`. The source uses the upstream Home package so it can update an existing installation signed with the same key. Use your own build and retain that key for further updates. Keep a verified copy of the running APK before replacing it, then verify the installed hash and default Home on the TV.

This is a source-only launcher fork for the CleanYourTV Showcase. The upstream core's offline policy and size gate apply to upstream launcher submissions. Build output and private recovery evidence belong outside the source distribution.

## Credits

Based on [CleanYourTV](https://github.com/Gathin23/cleanyourtv) at `f63a1eb`. The shelf artwork loader was adapted from [Mehdi-Ks's `my-tv` branch](https://github.com/Mehdi-Ks/cleanyourtv/tree/my-tv), which credits bjrb20's shelf work. [SmartTube](https://github.com/yuliskov/SmartTube) publishes the feeds and plays the videos.
