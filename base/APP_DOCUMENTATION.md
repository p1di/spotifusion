# Spotifusion — Complete Architecture & Beginner Developer Guide

Welcome to **Spotifusion**, a complete, native Android music streaming client that fuses:
1. **Frontend / UX (`redretep/spotify-clone`):** Exact Spotify dark palette (`#121212` primary background, `#282828` secondary card surface, `#1DB954` / `#1ED760` accent green, `#B3B3B3` light grey text, and the 21-color Browse Category palette), `55dp` 2-column quick-access shortcut tiles, `152dp` horizontal media carousels, `100dp` Browse Category cards with `30°` rotated `75dp` artwork and `0.18` black overlay, filter chips, persistent MiniPlayer, and full-screen Now Playing overlay.
2. **Backend Engine (`redretep/spoticlone` / `myspoti`):** Ad-free headless Web Player bridge, direct Spotify Pathfinder GraphQL & Web API requests, synchronized lyrics (`LRCLIB` + `Genius` annotations + `NetEase`), lossless FLAC fallback (`SpotiFLAC`), background `MediaSession` service, and offline caching.

---

## 1. Plain-English Glossary for Android Beginners

Before diving into the code, here is what every technical term in this project means in plain English:

| Term | Plain-English Meaning |
| :--- | :--- |
| **Kotlin (`.kt`)** | The modern programming language used to write Android apps. Think of it as a safer, cleaner version of Java. |
| **Jetpack Compose** | Android's modern toolkit for building user interfaces (UI). Instead of dragging buttons onto an XML canvas, you write Kotlin functions marked with `@Composable` that describe what the screen looks like based on the current data. |
| **Gradle (`build.gradle.kts`)** | The automated build robot for Android. It downloads open-source libraries, compiles your Kotlin files, and packs everything into an installable `.apk` file. |
| **JDK (Java Development Kit)** | The foundational compiler toolkit required on your computer so Gradle and Kotlin can run. We use **JDK 17**. |
| **Android SDK (`compileSdk 36`)** | The collection of Android system libraries and tools (like `adb` for talking to a phone) installed on your computer. |
| **APK (`.apk`)** | **Android Package Kit** — the final installable app file (similar to `.exe` on Windows or `.dmg` on macOS) that you copy onto your Android phone or emulator. |
| **Module (`:api`, `:app`, etc.)** | A sub-folder inside the project with its own responsibility. Splitting an app into modules keeps the backend engine separate from the visual screens. |
| **ViewModel** | A "brain" class for a specific screen (like `HomeViewModel` for `HomeScreen`). It fetches data from the backend and holds onto it so your screen doesn't lose its scroll position or reload if you rotate your phone. |
| **StateFlow** | A live radio broadcast of data inside the app. When a new song starts playing or the playback timer ticks forward by 1 second, the `StateFlow` broadcasts the new value, and Jetpack Compose automatically redraws the screen. |
| **Coroutines (`suspend fun`)** | Background workers. Network calls (like searching for songs or downloading album art) take time; Coroutines run them in the background so the app never freezes while you scroll. |
| **WebView** | An invisible web browser built into Android. Spotifusion runs a hidden desktop-mode WebView in the background (`WebPlayerHost.kt`) to host Spotify's official web player (`open.spotify.com`), extract session tokens, and block audio ads before they play. |
| **MediaSession & Foreground Service** | The Android system feature (`PlaybackService.kt`) that keeps music playing when you lock your phone screen or switch to another app, and puts play/pause/skip buttons on your lock screen, notification shade, and Bluetooth headphones. |
| **ADB (Android Debug Bridge)** | A command-line tool included with the Android SDK that lets your computer install apps onto a plugged-in Android phone or virtual emulator (`adb install ...`). |

---

## 2. Architecture Overview: How Frontend & Backend Interact

Spotifusion is organized into **4 clean modules** inside the root project folder (`C:\Users\pd\.gemini\antigravity\scratch\Spotifusion`):

```
Spotifusion/
├── api/                (:api)          -> Pure Kotlin data models & interfaces (The Contract)
├── api-spotify/        (:api-spotify)  -> Spotify GraphQL, Web API, Ad-Blocker & JS Bridge
├── host-android/       (:host-android) -> Android Background Service, Hidden WebView & MediaSession
└── app/                (:app)          -> Jetpack Compose Screens, Theme, ViewModels & Lyrics
```

### How Data Flows When You Tap a Song:
1. **User Action (`:app` UI):** You tap a song on `HomeScreen.kt` or `SearchScreen.kt`.
2. **State Update (`SongPlayer.kt` & `PlayerViewModel.kt`):** The UI calls `SongPlayer.playSong(uri)` and immediately updates `CurrentSongState` so the **MiniPlayer** slides up at the bottom of the screen with the song's cover art and title.
3. **Engine Command (`:host-android` -> `:api-spotify`):** `MyspotiRuntime` forwards the play command through `SpotifyPlayer.kt` into `Bridge.kt`, which sends a command over the JavaScript bridge (`player-bridge.js`) into the hidden desktop-fingerprint `WebView` (`WebPlayerHost.kt`).
4. **Ad-Free Audio Stream (`AdBlock` & `MediaProxy.kt`):**
   - `player-bridge.js` and `WebPlayerHost.shouldInterceptRequest()` inspect every network request inside the hidden player.
   - Any ad audio or ad-tracking request is blocked at the network boundary (`AdBlock`).
   - Real music audio streams are proxied cleanly through `MediaProxy.kt` (stripping the Android WebView `X-Requested-With` header so Spotify sees a standard desktop browser).
5. **Background Lock-Screen Sync (`PlaybackService.kt`):** As the audio plays, `PlayerChannel.kt` receives real-time position and duration updates (`PlaybackState`) and updates Android's `MediaSessionCompat`, keeping your lock-screen notification and the Compose `MiniPlayer` / `PlayerScreen` scrubber bar in sync at 60–120fps.

---

## 3. Complete Module & File Directory

Below is a comprehensive breakdown of every module, file, class, and key function in Spotifusion.

---

### Module 1: `:api` (`api/src/main/kotlin/app/myspoti/api/`)
*Pure Kotlin contract module. Contains zero Android or Spotify-specific code so the UI and engine communicate through clean data models.*

#### 1. `Api.kt`
- **What it does:** Defines the top-level interfaces for the entire backend engine.
- **Key Interfaces & Functions:**
  - `MyspotiApi`: Root interface exposing `session`, `home`, `catalog`, `search`, `library`, `player`, and `recents`, plus `state: StateFlow<PlaybackState>` and `sessionState: StateFlow<SessionState>`.
  - `SessionApi.login()`, `logout()`, `refresh()`: Manages user authentication state.
  - `HomeApi.feed(facet: String)`: Fetches the personalized home screen sections (`All`, `Music`, `Podcasts`).
  - `HomeApi.categories()`: Fetches the "Browse All" genre categories and colors.
  - `CatalogApi.track(id)`, `album(id)`, `artist(id)`, `playlist(id)`: Fetches full metadata and tracklists.
  - `SearchApi.all(query)`, `tracks(query)`, `suggestions(query)`: Queries Spotify's catalog.
  - `LibraryApi.items()`, `likedTracks()`, `saveTracks(ids)`, `createPlaylist(name)`: Reads and modifies the user's saved library.
  - `PlayerApi.play(contextUri)`, `pause()`, `resume()`, `next()`, `previous()`, `seek(positionMs)`, `setShuffle(enabled)`, `setRepeat(mode)`: Controls audio playback.

#### 2. `Models.kt`
- **What it does:** Defines immutable Kotlin `data class` objects for all musical entities.
- **Key Classes:**
  - `Track`: Holds `uri`, `id`, `name`, `durationMs`, `explicit`, `playable`, `artists: List<ArtistRef>`, `album: AlbumRef`.
  - `Album`: Holds `uri`, `id`, `name`, `albumType`, `releaseDate`, `cover: List<Image>`, `tracks: Page<Track>`, `copyrights`.
  - `Artist`: Holds `uri`, `id`, `name`, `verified`, `monthlyListeners`, `topTracks: List<Track>`, `discography`, `relatedArtists`.
  - `Playlist`: Holds `uri`, `id`, `name`, `description`, `owner`, `followers`, `images`, `items: Page<PlaylistEntry>`.
  - `HomeFeed` & `HomeShelf`: Represents the Home Screen's top shortcut grid and horizontal scrolling shelves.
  - `SearchResult`: Bundles `tracks`, `artists`, `albums`, `playlists`, `shows`, `episodes`, and `topResults`.

#### 3. `Media.kt`
- **What it does:** Defines real-time playback state models.
- **Key Classes:**
  - `PlaybackState`: Holds `isPlaying`, `isPaused`, `isBuffering`, `positionMs`, `durationMs`, `track: Track?`, `previousTracks`, `nextTracks`, `shuffle`, `repeatMode`, `deviceName`.
  - `SessionState`: Tracks whether the user is `SignedOut`, `Ready`, or `Expired`.

#### 4. `Recents.kt`
- **What it does:** Defines `RecentsApi` and `RecentEntry` for recently played tracks, albums, and playlists synced with Spotify.

---

### Module 2: `:api-spotify` (`api-spotify/src/main/kotlin/app/myspoti/spotify/`)
*The core engine that implements `:api` using Spotify's Pathfinder GraphQL API, Web API, and the headless JavaScript player bridge.*

#### 1. `SpotifyApi.kt`
- **What it does:** Main entry point implementing `MyspotiApi`. Coordinates token management, HTTP requests via `OkHttpClient`, and communication with the `BridgeHost`.
- **Data Flow:** Receives `BridgeHost` messages from `player-bridge.js`, extracts the `accessToken` and `clientToken`, and provides authenticated headers to `Gql.kt` and all sub-APIs (`SpotifyContent`, `SpotifyLibrary`, `SpotifyPlayer`, `SpotifyRecents`).

#### 2. `SpotifyPlayer.kt`
- **What it does:** Implements `PlayerApi`. Translates Kotlin playback calls (`play`, `pause`, `seek`, `setShuffle`, `setRepeat`, `transferPlayback`) into JSON commands sent across the `Bridge` to the hidden Web Player or Spotify Connect REST endpoints.

#### 3. `SpotifyContent.kt`
- **What it does:** Implements `HomeApi`, `CatalogApi`, and `SearchApi`.
- **Data Flow:** Calls Spotify's Pathfinder GraphQL operations (`home`, `searchDesktop`, `getTrack`, `getAlbum`, `queryArtistOverview`, `fetchPlaylist`) and maps the raw JSON into `:api` models using `Mappers.kt`.

#### 4. `SpotifyLibrary.kt`
- **What it does:** Implements `LibraryApi`. Fetches `libraryV3`, `fetchLibraryTracks` (Liked Songs), and executes mutations to like/unlike songs, follow artists, save albums, and create/edit playlists.

#### 5. `SpotifyMore.kt` & `SpotifyRecents.kt`
- **What it does:** Fetches extended metadata including Spotify Canvas looping video URLs (`canvas`), artist concerts, credits, and recently played history.

#### 6. `Mappers.kt` & `JsonPaths.kt`
- **What it does:** Safely traverses deeply nested Spotify GraphQL JSON responses (`JsonObject` / `JsonElement`) and converts them into clean `:api` Kotlin data classes without crashing on missing fields.

#### 7. `bridge/Bridge.kt` & `bridge/BridgeHost.kt`
- **What it does:**
  - `BridgeHost`: Interface representing the browser environment hosting `open.spotify.com` (`eval(js)` to run JavaScript, `messages: SharedFlow<String>` to receive events).
  - `Bridge`: Injects `player-bridge.js` into the page, parses incoming JSON events (`token`, `state`, `ad_blocked`), and matches request-response IDs.
  - `AdBlock`: Inspects URLs and blocks known Spotify ad audio CDNs, telemetry, and crash reporters (`spclient.wg.spotify.com/ads/`, `audio-ak-spotify-com.akamaized.net` ad segments, `adeventtracker`, DoubleClick, etc.).

#### 8. `gql/Gql.kt` & `gql/GqlOpsUpdater.kt`
- **What it does:** Manages Spotify Pathfinder persisted GraphQL queries (`sha256Hash` + operation name from `resources/spotify/gql-ops.json`). If Spotify rotates a query hash in a new web player deployment, `GqlOpsUpdater` automatically extracts the updated hashes from the live Web Player JS bundles!

#### 9. `resources/bridge/player-bridge.js`
- **What it does:** The JavaScript payload injected into the hidden `open.spotify.com` WebView.
- **How it works:**
  - Hooks `window.fetch` and `XMLHttpRequest` to capture the live `Authorization: Bearer ...` token and `client-token`.
  - Hooks the Encrypted Media Extensions (EME) / Widevine and HTML5 `<audio>` / `<video>` elements to control playback directly.
  - Intercepts and mutes/skips any ad slot state transitions (`AdBreak`), ensuring uninterrupted ad-free playback.

---

### Module 3: `:host-android` (`host-android/src/main/kotlin/app/myspoti/host/android/`)
*The Android hardware & OS integration layer. Keeps the hidden WebView alive in the background and connects to Android's system media controls.*

#### 1. `MyspotiRuntime.kt`
- **What it does:** Singleton manager (`object MyspotiRuntime`) that initializes `WebPlayerHost`, `SpotifyApi`, and `PlayerChannel` on the Android `Application` context so they survive activity restarts.

#### 2. `WebPlayerHost.kt`
- **What it does:** Hosts the hidden Android `WebView` running `https://open.spotify.com/`.
- **Key Features:**
  - Applies a realistic desktop Linux Chrome `User-Agent` and `Sec-CH-UA` Client Hints (`Fingerprint.kt`) so Spotify serves the full Web Player instead of a "Download the mobile app" landing page.
  - Suppresses the telltale `X-Requested-With: app.myspoti` WebView header using `WebSettingsCompat.setRequestedWithHeaderOriginAllowList`.
  - Grants `RESOURCE_PROTECTED_MEDIA_ID` (Widevine DRM) automatically in `onPermissionRequest` so encrypted Spotify streams play natively.
  - Intercepts network requests in `shouldInterceptRequest`: blocks ads via `AdBlock.isBlocked(url)` and routes audio segment fetches through `MediaProxy.kt`.
  - Exposes `loginRequired: StateFlow<Boolean>`—when the user is signed out, the UI displays this WebView on `SetupScreen.kt` so the user can log in directly on `accounts.spotify.com` (Spotifusion never sees or touches your password).

#### 3. `PlaybackService.kt`
- **What it does:** Android `MediaBrowserServiceCompat` foreground service that runs while music is playing.
- **Key Features:**
  - Creates the persistent Android Media Notification (with album art, track title, artist, Like button, Shuffle toggle, Previous, Play/Pause, Next, and seek bar).
  - Handles hardware media buttons (Bluetooth headphones, car stereos, lock screen controls, and Android Auto browsing).
  - Holds a partial `WakeLock` / `WifiLock` while audio is actively streaming so Android does not put the CPU or Wi-Fi to sleep when your screen is locked.

#### 4. `ControlRelay.kt`, `PlayerChannel.kt`, & `MediaProxy.kt`
- **`ControlRelay.kt`:** Relays play/pause/skip/seek actions between `PlaybackService` and `WebPlayerHost`.
- **`PlayerChannel.kt`:** Bridges playback state changes from the WebView into Android's `MediaSession`.
- **`MediaProxy.kt`:** Fetches audio stream byte ranges via `OkHttpClient` with clean desktop headers so WebView headers never leak on media requests.

---

### Module 4: `:app` (`app/src/main/java/com/music/spotui/`)
*The user-facing Android application built with Jetpack Compose.*

#### 1. Entry Point & Dependency Graph
- **`MainActivity.kt`:** Android's main entry activity. Initializes `MyspotiRuntime`, sets up edge-to-edge transparent system bars, checks whether the user is logged into Spotify (showing `WelcomeScreen` / `SetupScreen` if signed out, or `App()` if signed in).
- **`App.kt`:** The root `@Composable` layout containing the `Scaffold`, `MainBottomNavigation` (with docked `MiniPlayer`), `MyNavHost` (screen router), and `PlayerOverlay` (the full-screen pull-up Now Playing sheet).
- **`di/Graph.kt` & `di/SongPlayer.kt`:**
  - `Graph`: Service locator holding singletons (`api`, `appRepository`, `currentSongState`, `libraryState`).
  - `SongPlayer`: Central playback controller used by the UI. Coordinates between Spotify Web Player streaming, lossless FLAC playback (`LosslessSource`), and downloaded/local audio files (`LocalPlayer`).

#### 2. Theme & Design System (`ui/theme/`) — *1:1 `spotify-clone` Tokens & Fonts*
- **`Color.kt`:**
  - `ScPrimary` / `AppBackground`: `#121212` (or pure `#000000` when OLED Black mode is toggled in Settings).
  - `ScSecondary` / `GridBackground`: `#282828` (`COLORS.SECONDARY` from `spotify-clone`).
  - `ScTint` / `AppAccent`: `#1ED760` (`COLORS.TINT` from `spotify-clone`).
  - `ScGrey`: `#777777`, `ScLightGrey`: `#B3B3B3`, `ScLighterGrey`: `#D5D5D5`, `ScNav`: `#191414`.
  - `BrowseCategoryColors`: Exact 21-color array ported from `spotify-clone/config/colors.ts` (`#248FCF`, `#7811B3`, `#A1727E`, `#6EAF78`, `#58428B`, `#5DF27A`, etc.).
- **`Type.kt`:** Uses the official **Spotify Mix** font family (`spotifymix_regular.ttf`, `spotifymix_medium.ttf`, `spotifymix_bold.ttf`, `spotifymix_extrabold.ttf`, `spotifymix_ultra.ttf`) across all weights and components for 1:1 identical typography matching the official Spotify Android app.
- **`Theme.kt`:** Applies `SpotuiTheme` (`darkColorScheme` with `#121212` background, `#282828` high surface containers, `#B3B3B3` secondary text, and transparent status/navigation bars).

#### 3. Navigation, Profile Push-Drawer & Persistent MiniPlayer (`ui/navigation/` & `ui/components/`)
- **`MainBottomNavigation.kt` (1:1 Official Spotify Android Bottom Bar):**
  - Renders the 3 bottom tabs (`Home`, `Search`, `Your Library`) over a vertical transparent-to-black gradient (`0f` transparent -> `#000000`) so scrolling content peeks behind the floating `MiniPlayer`.
  - Uses custom vector icons ([`ic_spot_home_filled.xml`](file:///C:/Users/pd/.gemini/antigravity/scratch/Spotifusion/app/src/main/res/drawable/ic_spot_home_filled.xml), [`ic_spot_search_filled.xml`](file:///C:/Users/pd/.gemini/antigravity/scratch/Spotifusion/app/src/main/res/drawable/ic_spot_search_filled.xml), [`ic_spot_library_filled.xml`](file:///C:/Users/pd/.gemini/antigravity/scratch/Spotifusion/app/src/main/res/drawable/ic_spot_library_filled.xml) and their outline variants) at `22.dp` with `11.sp` labels (`#FFFFFF` active, `#B3B3B3` inactive).
- **`ProfileDrawer.kt` (1:1 Screenshot 4 Slide-Out Push Drawer):**
  - Tapping the top-left `34.dp` user avatar on Home, Search, or Your Library slides out a `90%`-width (`345.6.dp` = `972px`) `#1F1F1F` drawer while pushing the main `Scaffold` to the right 10% edge.
  - Displays the `52.dp` circular profile header (`"View profile"` + account switcher `+` button), 5 menu items (`"Your plan"` with `"Basic Family"` pill badge, `"Listening stats"`, `"Recents"`, `"Your Updates"`, `"Settings and privacy"`), the horizontal `"Activity / On"` friends row with song badges, and the `"Messages >"` list.
- **`AppComponents.kt` (`MiniPlayer`):**
  - Displays a `56.dp`-tall floating pill card (`8.dp` horizontal margin, `8.dp` corner radius) tinted dynamically from the current song's album cover.
  - Features a `40.dp` rounded cover (`4.dp` radius), `13.5.sp` Bold white track title, `13.sp` artist subtitle, custom Spotify Connect speaker-device icon ([`ic_spot_connect.xml`](file:///C:/Users/pd/.gemini/antigravity/scratch/Spotifusion/app/src/main/res/drawable/ic_spot_connect.xml)), `22.dp` green check circle, `24.dp` Play/Pause button, and a `2.dp` bottom progress bar.
- **`PlayerSheet.kt` (`PlayerOverlay`):**
  - Slides `PlayerScreen` up over the current screen with smooth deceleration curves and handles the system Back button/gesture to collapse back to the MiniPlayer.

#### 4. Core Screens (`ui/screens/`) — *1:1 Pixel-Measured Spotify Android Screens*
- **`HomeScreen.kt` (1:1 Screenshot 1):**
  - **Top Filter Bar (`HomeHeaderRow`):** `34.dp` circular user avatar (opens `ProfileDrawerState`) + `8.dp` gap + `32.dp`-tall filter pills (`"All"` in `#1ED760` green with black text, `"Music"`, `"Podcasts"`, `"Audiobooks"` in `#333333` with white text).
  - **8-Tile Shortcut Grid (`HomeShortcutGrid`):** 2-column × 4-row grid (`16.dp` outer margin, `8.dp` horizontal & vertical gaps) of `48.dp`-tall (`135px`) `#2A2A2A` cards with `4.dp` corner radius, `48.dp × 48.dp` left cover art, and `13.sp` Bold white 2-line titles.
  - **Horizontal Shelves (`HomeFeedSection` & `HomeFeedCard`):** `22.sp` Bold white section headers (`"Your shows"`, `"New releases for you"`) followed by a `LazyRow` (`16.dp` padding & card spacing) of `154.dp × 154.dp` (`434px`) artwork cards with `8.dp` corner radius, `13.5.sp` Bold white title, and `13.sp` `#A7A7A7` subtitle.
- **`SearchScreen.kt` (1:1 Screenshot 2):**
  - **Top Header (`SearchTopBar`):** `34.dp` circular user avatar + `"Search"` (`24.sp` Bold white) on the left, and the custom Spotify camera outline icon ([`ic_spot_camera.xml`](file:///C:/Users/pd/.gemini/antigravity/scratch/Spotifusion/app/src/main/res/drawable/ic_spot_camera.xml), `26.dp`) on the right.
  - **Sticky Search Bar (`SearchStickyBar`):** `48.dp`-tall (`134px`) `#FFFFFF` search box (`6.dp` corner radius) with a `22.dp` dark search icon and left-aligned `#535353` `"What do you want to listen to?"` hint (`15.sp` SemiBold).
  - **3-Tier Browse Grid (`BrowseAllSection` & `BrowseCategoryTile`):**
    1. Top 4 Quick Cards (`69.dp` / `194px` tall, no header above): `"Music"` (`#E1118C`), `"Podcasts"` (`#1E8264`), `"Audiobooks"` (`#1E3264`), and `"Live Events"` (`#8400E7`).
    2. `"More for you"` Section (`102.dp` / `286px` tall cards): `"Made For You"`, `"New Releases"`, `"Upcoming releases"`, `"OFF-CULT"`, `"Halloween"`, and `"Charts"`.
    3. `"Genres"` Section (`69.dp` tall cards with `"See all"` link): Starting with `"Hip-Hop"` (`#283EA3`) and `"Indie"` (`#148A08`) followed by all live Spotify categories, with `25°` tilted bottom-right artwork.
- **`LibraryScreeen.kt` (1:1 Screenshot 3):**
  - **Top Header & Filter Pills:** `34.dp` avatar + `"Your Library"` (`24.sp` Bold white) on the left, Search (`25.dp`) and Plus `+` (`26.dp`) icons on the right, and `32.dp`-tall `#2A2A2A` filter pills (`Playlists`, `Podcasts`, `Albums`, `Artists`, `Downloaded`).
  - **List View Rows (`SumUpLibraryScreen`, default):** `64.dp × 64.dp` (`180px`) cover art (`4.dp` rounded square for playlists/albums, circle for artists), `14.dp` gap, `16.sp` Medium white title, rotated `#1ED760` green pin icon + `#1ED760` green circle-arrow download badge, and `13.sp` `#B3B3B3` subtitle (plus toggle button to switch to 3-column grid view).
- **`ArtistScreen.kt` (1:1 Screenshot 5):**
  - **`267.dp` (`750px`) Hero Header:** Top-left `36.dp` dark circle back arrow, top-right `36.dp` dark circle `⋮` menu button, bottom-left `48.sp` ExtraBold white artist name, and scalloped `#75E668` verified badge ([`ic_spot_verified.xml`](file:///C:/Users/pd/.gemini/antigravity/scratch/Spotifusion/app/src/main/res/drawable/ic_spot_verified.xml)) + `"Verified by Spotify"`.
  - **Action Row & `"Music" | "Merch"` Tabs:** Dark slate-teal gradient (`#192329 -> #121212`), `"94M monthly listeners"` (`14.sp` `#A7A7A7`), `"Follow"` pill button (`1.dp` `#727272` border), Shuffle + `48.dp` `#1ED760` green Play circle, and `"Music"` / `"Merch"` tabs with a `47.dp × 2.5.dp` `#1ED760` underline.
  - **Circular Artist `"Liked songs"` Row & `"Popular"` Tracks:** `48.dp` circular artist photo with bottom-right `#1ED760` heart badge and right chevron `›`, followed by numbered `48.dp` `"Popular"` track rows with green circle checkmarks and `⋮` menu icons.
- **`PlayerScreen.kt` & `LyricsScreen.kt` (`PlayerViewModel.kt`, `LyricsViewModel.kt`):**
  - **Full-Screen Now Playing (`PlayerScreen`)** and **Synchronized Lyrics & Genius Annotations (`LyricsScreen` & `LyricsApi.kt`)**.

---

## 4. Step-by-Step Setup & Build Guide for Beginners

Follow these steps to build, install, and run **Spotifusion** at **full 60–120 FPS** on your computer or Android phone.

> [!IMPORTANT]
> **Why You Should Always Build `assembleRelease` Instead of `assembleDebug` for Daily Use:**
> In Jetpack Compose, a `Debug` build (`assembleDebug`) disables the R8 compiler optimizer, attaches live-literal debug hooks to every single UI element, disables Baseline Profiles, and runs Chrome DevTools debugging on the hidden WebView—which causes noticeable frame drops (low FPS) when scrolling.
> Building **`assembleRelease`** activates Google's R8 optimizer (`isMinifyEnabled = true`), strips all debug overhead, and enables Jetpack Compose Baseline Profiles so Spotifusion runs at a locked **60–120 FPS** just like the original `myspoti` release APK!

### Step 1: Verify Prerequisites (Already Installed on Your PC!)
We verified that your Windows PC already has:
- **Java JDK 17:** Microsoft OpenJDK `17.0.20.1` (`C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`)
- **Android SDK:** Located at `C:\Users\pd\AppData\Local\Android\Sdk` (with Android 35, 36 & 37 platforms and Build Tools `36.0.0`).

---

### Step 2: Compile the High-FPS Release APK (`assembleRelease`)

Open **PowerShell** (on Windows) or **Terminal** (on macOS/Linux) and run these exact copy-paste commands:

#### Windows (PowerShell):
```powershell
cd C:\Users\pd\.gemini\antigravity\scratch\Spotifusion
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat assembleRelease
```

#### macOS / Linux (Bash / Zsh):
```bash
cd ~/Spotifusion
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleRelease
```

**Where is the compiled `.apk` file?**
Once Gradle prints `BUILD SUCCESSFUL`, your optimized, high-FPS Android app file is located at:
```text
C:\Users\pd\.gemini\antigravity\scratch\Spotifusion\app\build\outputs\apk\release\app-release.apk
```

---

### Step 3: Install Spotifusion on a Real Android Phone or Emulator

#### Option A: Install on Your Physical Android Phone via USB (Recommended)
1. **Enable Developer Options on your phone:**
   - Open your phone's **Settings** -> **About phone** -> tap **Build number** **7 times** rapidly until it says *"You are now a developer!"*.
2. **Turn on USB Debugging:**
   - Go back to **Settings** -> **System** -> **Developer options** -> scroll down and toggle on **USB debugging**.
3. **Plug your phone into your PC** with a USB cable and tap **Allow** on the *"Allow USB debugging?"* popup on your phone screen.
4. **Run the install command in PowerShell:**
   ```powershell
   cd C:\Users\pd\.gemini\antigravity\scratch\Spotifusion
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" uninstall app.myspoti.dev
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\release\app-release.apk
   ```

#### Option B: Run on an Android Virtual Device (Emulator) on Your PC
1. Open **Android Studio** -> click **More Actions** -> **Virtual Device Manager** -> start your virtual Pixel phone.
2. Once the emulator window is open, run:
   ```powershell
   cd C:\Users\pd\.gemini\antigravity\scratch\Spotifusion
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r .\app\build\outputs\apk\release\app-release.apk
   ```

---

### Step 4: Troubleshooting Common Beginner Errors

| Symptom / Error Message | Cause | Exact Fix |
| :--- | :--- | :--- |
| `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable...` | Gradle doesn't know where your Android SDK folder is. | Ensure `local.properties` exists in the project root with `sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk`, or run `$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"` in PowerShell before building. |
| `Unable to download toolchain matching the requirements ({languageVersion=21...})` | A `gradle/gradle-daemon-jvm.properties` file is forcing Gradle to download JDK 21 instead of using your local JDK 17. | Delete `gradle\gradle-daemon-jvm.properties` so Gradle uses your local Java 17 installation. (We already fixed this in Spotifusion!) |
| `adb: no devices/emulators found` | Your phone isn't connected in data mode, or USB Debugging isn't authorized yet. | Unplug and replug your USB cable, switch USB mode on your phone from "Charging only" to "File Transfer", and tap **Allow** on your phone's USB debugging prompt. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A previous version of the app signed with a different key is already on the phone. | Uninstall the existing Spotifusion app from your phone first, then run `adb install` again. |
| Music pauses after 5 minutes with the screen off | Android's aggressive battery saver is putting the background service to sleep. | Inside Spotifusion, tap your profile avatar (top-left of Home) -> **Settings** -> tap **Background playback** to disable battery optimization for Spotifusion. |
