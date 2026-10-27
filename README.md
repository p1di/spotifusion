# Spotifusion

<p align="center">
  <img src="docs/brand/myspoti-logo-512.png" alt="Spotifusion Logo" width="120" style="border-radius: 24px;">
</p>

<p align="center">
  <b>A modern, pixel-perfect native Android music client with an ad-free experience, synced lyrics, and offline downloads.</b>
</p>

<p align="center">
  <a href="https://github.com/p1di/spotifusion"><img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform: Android"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white" alt="Language: Kotlin"></a>
  <a href="https://developer.android.com/jetpack/compose"><img src="https://img.shields.io/badge/UI-Jetpack_Compose-4285F4?logo=jetpackcompose&logoColor=white" alt="UI: Jetpack Compose"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License: GPL-3.0"></a>
  <a href="https://instagram.com/pidi.mp3"><img src="https://img.shields.io/badge/Instagram-@pidi.mp3-E4405F?logo=instagram&logoColor=white" alt="Instagram: @pidi.mp3"></a>
</p>

<p align="center">
  <a href="https://github.com/p1di/spotifusion/releases/latest">
    <img src="https://img.shields.io/badge/⬇%20Download%20Latest%20Release-GitHub%20Releases-1DB954?style=for-the-badge&logo=github&logoColor=white" alt="Download Latest Release" height="48">
  </a>
</p>

---

## Screenshots

<div align="center">

| Now Playing | Synced Lyrics | Home Screen | Search & Browse |
| :---: | :---: | :---: | :---: |
| <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-11-911_com.spotifusion.app.jpg" width="210" alt="Now Playing"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-39-504_com.spotifusion.app.jpg" width="210" alt="Lyrics"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-04-939_com.spotifusion.app.jpg" width="210" alt="Home"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-01-507_com.spotifusion.app.jpg" width="210" alt="Search"> |

| Your Library | Playlist View | Artist Page | User Profile |
| :---: | :---: | :---: | :---: |
| <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-49-57-848_com.spotifusion.app.jpg" width="210" alt="Library"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-52-104_com.spotifusion.app.jpg" width="210" alt="Playlist"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-15-614_com.spotifusion.app.jpg" width="210" alt="Artist"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-22-121_com.spotifusion.app.jpg" width="210" alt="Profile"> |

| Up Next Queue | Recents & Recap | Account Drawer |
| :---: | :---: | :---: |
| <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-51-01-646_com.spotifusion.app.jpg" width="210" alt="Queue"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-51-25-728_com.spotifusion.app.jpg" width="210" alt="Recents"> | <img src="assets/Spotifusion%20Screenshots/Screenshot_2026-10-25-11-50-07-139_com.spotifusion.app.jpg" width="210" alt="Drawer"> |

</div>

---

## Overview

**Spotifusion** is a native Jetpack Compose Android music player engineered to deliver an authentic, 1:1 Spotify UI experience combined with ad-free playback, offline downloads, real-time synced lyrics, and synchronized music videos.

Playback operates through a lightweight, headless official Spotify Web Player running in a background WebView (`:host-android`). The native interface communicates directly with Spotify's catalog and metadata services for lightning-fast navigation, while plays register to your account normally.

---

## Key Features

- **1:1 Official Spotify Design**: Pixel-measured layout, Spotify Mix typography, authentic transport controls, precise seek bar, and bottom lyrics peek cards.
- **Ad-Free Playback**: Audio advertisements are automatically detected and bypassed at the player source without needing Premium or modifying DRM.
- **Fast Offline Downloads (BitChord Engine)**: Download full tracks, playlists, albums, and Liked Songs for offline playback:
  - Powered by BitChord's intelligent `TrackMatcher` algorithm to avoid live takes, karaoke versions, or covers.
  - Downloads high-bitrate audio (up to 320kbps AAC and progressive Opus streams) using range-chunked acceleration that bypasses server throttling.
  - Zero verification gates or login barriers for downloads.
- **Real-Time Synced Lyrics**: Powered by [LRCLIB](https://lrclib.net) with instant line-by-line sync and smooth transitions.
- **Synchronized Music Videos**: Official music videos from YouTube play seamlessly in sync with audio tracks when available.
- **Complete Library & Navigation**: Full support for Liked Songs, user playlists (with pinned item styling), albums, artists, podcasts, and audiobooks.
- **System Integration**: Bluetooth metadata & device naming, lock screen controls, media notifications, Android Auto support, and Spotify Connect.
- **Privacy & Recents**: Built-in Recap and listening history stored locally on your device.

---

## Architecture

The project is structured into modular components:

- **`:api`**: Clean Kotlin domain models and interfaces (catalog, player controls, search, library, devices, lyrics, podcasts).
- **`:api-spotify`**: Implementation of Spotify's Web API, GraphQL operations, and catalog queries.
- **`:host-android`**: Background WebView bridge that securely hosts the official Web Player and bridges playback controls to Android's media session.
- **`:app`**: Full Jetpack Compose interface with Material 3 styling, offline storage, and BitChord audio downloader.
- **`:host-cdp`**: Desktop Chrome DevTools Protocol test harness for validating web player operations headlessly.

---

## Building from Source

### Prerequisites
- JDK 17 or JDK 21
- Android SDK (API 34+ build-tools, `compileSdk = 37`)

### Build Commands

The Android project is located in `base/`:

```bash
# Clone the repository
git clone https://github.com/p1di/spotifusion.git
cd spotifusion/base

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

The compiled release APK will be generated at:
`base/app/build/outputs/apk/release/app-release.apk`

---

## Credits & Acknowledgements

Spotifusion is built on top of incredible open-source projects across the community:

- **[SpotiDuck](https://github.com/SpotiDuck)** — Inspiration, design concepts, and client optimizations.
- **[Myspoti / Spotui](https://github.com/kota-zo/myspoti)** (GPL-3.0) — The foundational Web Player WebView bridge architecture, Compose client components, and catalog interfaces by kota-zo and the MetrolistGroup.
- **[BitChord](https://github.com/kushagrasinghx/BitChord)** (MIT) — The high-performance track matching algorithm, NewPipe stream resolution pipeline, and range-chunked download mechanism by Kushagra Singh.
- **[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)** (GPL-3.0) — Advanced streaming metadata and format extraction by TeamNewPipe.
- **[LRCLIB](https://lrclib.net)** (MIT) — Open-source synchronized lyrics provider by tranxuanthang.
- **[Material Design Icons](https://github.com/google/material-design-icons)** (Apache 2.0) — Core system icon assets by Google.

Full copyright notices and third-party licenses are documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

---

## Community & Feedback

- **Developer:** [p1di (github.com/p1di)](https://github.com/p1di)
- **Instagram:** [@pidi.mp3](https://instagram.com/pidi.mp3)
- **Report Bugs & Suggest Features:** [Feedback & Bug Report Form](https://forms.gle/CLNqCznseJwVqg1Q7)

---

## Disclaimer

Spotifusion is an open-source educational project developed for pair-programming and media playback research. Spotifusion is **not** affiliated with, endorsed by, or associated with Spotify AB. Spotify is a registered trademark of Spotify AB. If you enjoy the music, please consider supporting artists directly through Spotify Premium or purchasing their music.

---

## License

This project is licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
