# Pulse Horizon

An ultra-lightweight Android privacy browser. Everything you leave behind is wiped when you close it.

Built on a pure programmatic View hierarchy and the system WebView — no heavy UI toolkits, no background services, no analytics, no ads. Engineered for zero UI lag, low thermal overhead and a tiny footprint.

## Features

- **True exit wipe** — history, cookies and cache are erased when you close the browser. Third-party cookies are blocked by default.
- **Bounded 20 MB smart cache** — the cache cleans itself while you browse, and Chromium's internal files are purged on exit.
- **Universal download engine** — files save with correct names and real types (PDF, ZIP, images, video, audio, documents), including sites like Google Drive and Google AI Studio. No more mystery `.bin` downloads.
- **Immersive full screen** — the URL bar hides when a page opens. Double-tap the top of the screen to show or hide it.
- **URL tools** — long-press the URL bar for Copy URL, Share URL, Paste and go, and Help & Feedback.
- **Desktop mode** — one-tap DESK button switches the user-agent and injects viewport for full desktop sites.
- **Pinch-to-zoom** — full zoom control on every page.
- **Ad and tracker blocking** — network-level interception of known ad servers.
- **Zero background** — no services, no timers, no notifications. Zero battery use after you exit.
- **Built-in feedback** — long-press the URL bar → Help & Feedback. Messages go straight to the developer. No account needed.

## Install

1. Download the latest **app-debug.apk** from the [Releases page](https://github.com/dibyendupramanik249-cell/Pulse-Horizon/releases).
2. Your browser will warn that "this file might be harmful" — tap **Download anyway**. This is normal for any APK downloaded outside the Play Store.
3. Open the downloaded file and tap **Install** (enable "Install unknown apps" for your browser if your system asks).

Requires Android 8.0 (API 26) or newer.

## Build from source

The repository builds itself with GitHub Actions — no local Android SDK needed:

1. Fork or clone this repository.
2. Open the **Actions** tab → **Build Android APK** → **Run workflow**.
3. When the run finishes, download the **nano-browser-debug** artifact and extract `app-debug.apk`.

Local build: open the project in Android Studio (AGP 9.x, JDK 17) and run `./gradlew assembleDebug`.

## Privacy

Pulse Horizon sends nothing anywhere by itself. There is no analytics, no crash reporting, no advertising SDK, and no background network activity. The only data that ever leaves the device is the feedback you explicitly write and send from the Help & Feedback page.

## Development story

Pulse Horizon was designed, tested and shipped by a single developer working entirely on an Android phone, with AI assistance: Google AI Studio for the initial application base, and an AI coding assistant for the download engine, cache control, immersive mode, the feedback system and dozens of bug fixes across 58 iterations. Every change was tested on real devices.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE). You are free to use, study, share and modify this software; any distributed modified version must also be open source.

## Contact

Dibyendu Pramanik — dibyendupramanik249@gmail.com
