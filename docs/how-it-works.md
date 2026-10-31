# How Hearth works

The details behind each feature, how Hearth talks to CasaOS, and what it does to keep your server safe. For a quick overview, see the [README](../README.md).

- [Features in depth](#features-in-depth)
- [How it talks to CasaOS](#how-it-talks-to-casaos)
- [Security](#security)
- [Building](#building)
- [Code layout](#code-layout)

## Features in depth

### Servers and sign-in

- Sign in with the server address and your CasaOS login. Tokens are encrypted with an Android Keystore key and refreshed automatically.
- The sign-in page looks for CasaOS servers on your Wi-Fi when it opens, or when you tap **Find servers on this network**. It tries `casaos.local` and every address on the phone's subnet (at most 254) on port 80. Tap a result to fill in the address. If it finds exactly one and you haven't typed anything, it fills it in for you.
- You can save more than one server. Each one keeps its own encrypted login. Switch servers from the server name on Home or from Settings, where you can also add and remove servers. A signed-out server opens the sign-in form already filled in.
- **Sign out** ends the current login but keeps the server in your list. It also forgets that server's SSH password, its widget reading and the in-app browser's logins.

### Home

- Live CPU, memory and storage gauges, CPU history and network throughput. They update every 2.5 s, and only while the screen is visible.
- The **Apps** card shows CPU and memory per app, busiest first, sorted by CPU or memory. CasaOS only reports whole-server totals, so this card reads `docker stats` over the SSH login saved on the Terminal tab. While the card is collapsed, Hearth doesn't poll it and closes its SSH connection.

### Apps

- Lists installed apps with their status. Each app can be started, stopped or restarted, and you can open its web UI or view its logs. Search filters the list. Apps with several containers get a chip per container.
- **Logs** opens an app's container logs full screen. They update every 3 seconds while the page is open (pause to read), and reach back 500, 2,000 or 10,000 lines. Search shows only the lines that match. Copy puts them on the clipboard (the newest 300,000 characters if there are more), and Share sends them as a text file. Terminal color codes are removed.
- **Open:** the app's web UI opens inside Hearth or in your browser, depending on the choice in Settings.
- **Settings** (next to Logs) shows the same container settings as the CasaOS dashboard: image and tag, name, icon, web UI address, network, ports, volumes, environment variables, devices, command, privileged mode, memory limit, CPU shares, restart policy and capabilities. Passwords stay hidden until you tap them. The pencil edits these settings in a form, and **Edit as YAML** in the menu edits the whole compose file. CasaOS checks your changes before saving (including ports already in use), then restarts the app with them.
- **Version** shows which image tag the app runs and lists the tags its image has, newest first. Docker Hub tags also show dates and sizes. Search finds older tags, or you can type any tag. Tap one to switch the app to it.
- **Update:** when CasaOS has an update for a store app, the row is highlighted and **Update** installs the version the App Store ships.
- **Uninstall** is at the bottom of an app's details and in its long-press menu. You choose whether its data in `/DATA/AppData` is deleted too.
- **App Store** (top of Apps) lists the apps your server's stores offer, with search, categories, screenshots and descriptions, and installs them.
- **Create app** (long-press an app, or the button in the in-app browser's top bar) puts an app's web UI on your home screen with its icon. It opens full screen in its own window, like a native app.

### Files

- Browse `/DATA`. Photos, videos, songs and text files preview inside Hearth (pinch or double-tap to zoom photos). Other files open in an app you pick.
- Each item's menu (⋮ or long-press) has **Open with…**, **Share**, **Download**, **Rename**, **Copy to…**, **Move to…** and **Delete**. Downloads go to `Downloads/Hearth` and don't need storage permission. To copy or move, browse to the destination and tap **Paste here**.
- **+** uploads photos, videos or any files from the phone, and makes new folders. If an uploaded file's name is already taken, you choose to skip it, keep both or replace it.
- **Search** covers the whole server. CasaOS has no search API, so the first search walks the folders under `/DATA` in the background, and later searches filter that list as you type. Results from the open folder come first, then everywhere else. The walk stops at 100,000 items and shows the best 300 matches. Pull to refresh rescans.

### Terminal

- A shell on the server over SSH. It has color, scrollback (drag), pinch-to-zoom text, paste and a row of extra keys (Ctrl, Alt, Esc, Tab, arrows…).
- **Update server** runs `sudo apt update && sudo apt upgrade -y` in one tap.
- The session keeps running while you switch tabs.

### Lock

- **Lock Hearth** in Settings uses the phone's own screen lock (fingerprint, face, PIN, pattern or password), so Hearth stores no PIN of its own. The switch is disabled if the phone has no screen lock.
- When the lock is on, Hearth starts locked. It locks again after you leave it, either right away, after 1 minute (the default) or after 5 minutes. Moving between screens or rotating the phone doesn't lock it.
- Apps opened from home screen icons are behind the same lock. Hearth's screens are hidden in Recents while the lock is on (Android 13+).
- The lock screen covers the app without closing it, so a terminal session or upload keeps running underneath.
- Turning the lock on or off asks for your fingerprint or PIN first.

### Widget

- Shows your server's CPU, memory and storage use. At 2×1 it shows three numbers, larger sizes add bars, and 3×2 and up add CPU cores, temperature and the amount of memory and storage used.
- Each widget can show any saved server or follow the app. To pick, tap the server name on the widget.
- It refreshes every 30 minutes, when you leave Hearth, and when you tap its refresh button. If the server can't be reached, it keeps the last reading and marks it offline.
- It follows Hearth's theme.

### Themes

- System, Light or Dark.
- Six accents: Ember, Ocean, Forest, Violet, Rose, and Dynamic, which uses your wallpaper colors on Android 12+.
- A pure black mode for OLED screens.
- The choice is remembered and survives sign-out.

### Tablets and landscape

- On screens 600dp wide or more (tablets, and phones turned on their side) the tabs move from the bottom bar to a rail down the side.
- From 840dp, Home puts the Apps card in a second column beside the server's stats.
- Apps and the App Store show two or three cards per row when there's room. Files, Settings and the forms stay in a centred column no wider than 720dp. Logs, the terminal and web pages use the full width.
- Content keeps clear of a camera cutout at the side of the screen.

## How it talks to CasaOS

| Feature | Endpoint |
| --- | --- |
| Login / refresh | `POST /v1/users/login`, `POST /v1/users/refresh` |
| Finding servers | `GET /v1/users/status` (needs no login), falling back to a `<title>CasaOS` check on `GET /` |
| Stats | `GET /v1/sys/utilization` |
| Apps | `GET /v2/app_management/compose`, `PUT .../compose/{id}/status`, `GET .../compose/{id}/logs` |
| App settings | `GET /v2/app_management/compose/{id}` with `Accept: application/yaml`, `PUT .../compose/{id}?check_port_conflict=true` with the YAML |
| App version | Switching writes the new tag with the app settings call above. App Store version: `GET /v2/app_management/apps/{id}/stable`; update: `PATCH /v2/app_management/compose/{id}` |
| Files | `GET /v1/folder?path=`, `GET /v1/file?path=`; new folder `POST /v1/folder`, rename `PUT /v1/file/name`, delete `DELETE /v1/file`, copy / move `POST /v1/batch/task`, upload `POST /v1/file/upload` (1 MB chunks, like the dashboard) |
| App Store | `GET /v2/app_management/apps`; install: `GET .../apps/{id}/compose` (YAML), then `POST /v2/app_management/compose?check_port_conflict=true`; uninstall: `DELETE .../compose/{id}?delete_config_folder=` |
| Power | `PUT /v1/sys/state/{restart\|off}` |

CasaOS expects the raw JWT in the `Authorization` header (no `Bearer`). Endpoints were taken from the CasaOS source and OpenAPI specs.

**Image versions:** CasaOS can't list an image's versions, so the phone asks the image's registry directly. It uses Docker Hub's API (`hub.docker.com/v2/repositories/…/tags`), or the standard registry API (`/v2/<name>/tags/list`, with an anonymous token) for ghcr.io, lscr.io, quay.io and others. Only public images can be listed, and the CasaOS login is never sent to a registry.

**Terminal:** Hearth doesn't use CasaOS's web terminal (`/v1/sys/wsssh`), because that sends the SSH password in the URL over plain HTTP. It connects straight to the server's SSH port with [JSch](https://github.com/mwiede/jsch) and draws the terminal with Termux's [terminal-emulator](https://github.com/termux/termux-app/tree/master/terminal-emulator) library.

## Security

- CasaOS has no TLS by default, so Hearth allows plain HTTP (`network_security_config.xml`), and your password crosses the network unencrypted. Stay on your home network, or put the server behind HTTPS or a VPN (Tailscale, WireGuard) for remote use.
- SSH is encrypted end to end. On the first connection, Hearth shows the server's host key fingerprint for you to confirm. It uses the same `SHA256:` format as `ssh-keygen -lf`. If the key changes later, Hearth warns you before connecting.
- A remembered SSH password is encrypted like the CasaOS tokens and wiped on sign-out. Signing out also closes any open shell.
- The auth token is only sent to the server you signed in to, never to other hosts such as icon CDNs. A unit test covers this.

## Building

You need the Android SDK and any Java to start Gradle. Gradle then downloads the JDK 21 the build uses. Opening the project in Android Studio sets both up for you.

From the command line, create `local.properties` pointing at your SDK. The file is per-machine and isn't committed.

```
# Windows
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
# macOS / Linux
sdk.dir=/home/<you>/Android/Sdk
```

If `java` isn't on your PATH, point `JAVA_HOME` at Android Studio's bundled JDK, e.g. `C:\Program Files\Android\Android Studio\jbr` on Windows.

```
./gradlew :app:testDebugUnitTest    # unit tests
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

On Windows cmd or PowerShell, use `gradlew.bat` instead of `./gradlew`.

If the headless emulator crashes with the default software renderer, start it with `-gpu host`.

## Code layout

```
app/src/main/kotlin/dev/hearth/casaos/
  data/   CasaClient (OkHttp + token refresh), Parsers (lenient JSON),
          AppSettings + ComposeYaml (app settings <-> compose YAML),
          ImageRegistry (an image's versions from Docker Hub / ghcr.io / other registries),
          SessionStore + SecretBox (saved servers, Keystore encryption), FileDownloader
  ui/     theme, components, and one package per screen (screen + ViewModel together)
```
