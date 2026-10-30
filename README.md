# Hearth

A native Android client for [CasaOS](https://casaos.io): live system stats, app control, file browsing and server power actions from your phone. Kotlin, Jetpack Compose, Material 3.

![App tour](docs/screenshots/tour.png)

## Features

- **Connect**: server address + your CasaOS login. Tokens are stored encrypted with an Android Keystore key and refreshed automatically.
- **Home**: live CPU / memory / storage gauges, CPU history, network throughput (polled every 2.5 s, only while the screen is visible).
- **Apps**: installed apps with status, start / stop / restart, open the app's web UI (inside Hearth or in your browser, your choice in Settings), view logs. **Settings** (next to View logs) shows an app's container settings, the same ones the CasaOS dashboard's settings dialog has: image and tag, name, icon, web UI address, network, ports, volumes, environment variables (passwords hidden until tapped), devices, command, privileged mode, memory limit, CPU shares, restart policy and capabilities. The pencil edits them in a form; **Edit as YAML** in the menu edits the app's whole compose file for anything else. CasaOS checks changes (including ports already in use) before saving, then restarts the app with them. **Version** shows which version (image tag) the app runs and lists the versions its image has, newest first, with dates and sizes on Docker Hub; search finds older ones, or type any tag. Tap one to switch the app to it, like changing the tag in the dashboard's settings. When CasaOS has an update for a store app, the row is highlighted and **Update** installs the version the App Store ships. Apps with several containers get a chip per container. **Create app** (long-press an app, or the button in the in-app browser's top bar) puts an app's web UI on your home screen with its icon. It opens full-screen in its own window with no toolbar, like a native app; open the app through Hearth when you want the toolbar.
- **Files**: browse `/DATA`, download to `Downloads/Hearth` (no storage permission needed).
- **Terminal**: a shell on the server over SSH, with color, scrollback (drag), pinch-to-zoom text, an extra-keys row (Ctrl, Alt, Esc, Tab, arrows…), paste, and a one-tap **Update server** that runs `sudo apt update && sudo apt upgrade -y`. The session keeps running while you switch tabs.
- **Settings**: server version, where apps open (Hearth or browser), restart / shut down (with confirmation), sign out. Signing out also clears logins made inside Hearth's in-app browser.
- **Themes**: System / Light / Dark, six accents (Ember, Ocean, Forest, Violet, Rose, and Dynamic, which uses your wallpaper colors on Android 12+), and a pure-black mode for OLED screens. The choice is remembered and survives sign-out.

![Themes](docs/screenshots/themes.png)

## Build and run

Needs the Android SDK and any Java to start Gradle (it then auto-provisions the JDK 21 the build uses). Opening the project in Android Studio sets both up for you.

From the command line, create `local.properties` (it's per-machine and not committed) pointing at your SDK:

```
# Windows
sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk
# macOS / Linux
sdk.dir=/home/<you>/Android/Sdk
```

If `java` isn't on your PATH, point `JAVA_HOME` at Android Studio's bundled JDK, e.g. `C:\Program Files\Android\Android Studio\jbr` on Windows.

```
# Windows (cmd / PowerShell)
gradlew.bat :app:testDebugUnitTest    # unit tests
gradlew.bat :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk

# macOS / Linux / Git Bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Emulator note: on this machine the headless emulator crashes with the default software renderer; start it with `-gpu host`.

## How it talks to CasaOS

| Feature | Endpoint |
| --- | --- |
| Login / refresh | `POST /v1/users/login`, `POST /v1/users/refresh` |
| Stats | `GET /v1/sys/utilization` |
| Apps | `GET /v2/app_management/compose`, `PUT .../compose/{id}/status`, `GET .../compose/{id}/logs` |
| App settings | `GET /v2/app_management/compose/{id}` with `Accept: application/yaml`, `PUT .../compose/{id}?check_port_conflict=true` with the YAML |
| App version | Switching writes the new tag with the app settings call above. App Store version: `GET /v2/app_management/apps/{id}/stable`; update: `PATCH /v2/app_management/compose/{id}` |
| Files | `GET /v1/folder?path=`, `GET /v1/file?path=` |
| Power | `PUT /v1/sys/state/{restart\|off}` |

CasaOS can't list an image's versions, so the phone asks the image's registry directly: Docker Hub's API (`hub.docker.com/v2/repositories/…/tags`), or the standard registry API (`/v2/<name>/tags/list`, with an anonymous token) for ghcr.io, lscr.io, quay.io and others. Only public images can be listed, and the CasaOS login is never sent to a registry.

The terminal does not use CasaOS's web terminal (`/v1/sys/wsssh`, which takes the SSH password in the URL over plain HTTP). It connects straight to the server's SSH port with [JSch](https://github.com/mwiede/jsch) and renders with Termux's [terminal-emulator](https://github.com/termux/termux-app/tree/master/terminal-emulator) library.

CasaOS expects the raw JWT in the `Authorization` header (no `Bearer`). Endpoints were taken from the CasaOS source and OpenAPI specs; they have not yet been tested against a real CasaOS install.

## Security notes

- CasaOS has no TLS by default, so the app permits cleartext HTTP (`network_security_config.xml`) and your password crosses the network unencrypted. Keep to your LAN, or put the server behind HTTPS or a VPN (Tailscale / WireGuard) for remote use.
- SSH is encrypted end to end. On first connect the app shows the server's host key fingerprint (same `SHA256:` format as `ssh-keygen -lf`) for you to confirm; if the key later changes, it warns before connecting. A remembered SSH password is encrypted like the CasaOS tokens and wiped on sign-out, and signing out closes any open shell.
- The auth token is only ever sent to the server you signed in to, never to other hosts (e.g. icon CDNs); this is covered by a unit test.

## Layout

```
app/src/main/kotlin/dev/hearth/casaos/
  data/   CasaClient (OkHttp + refresh), Parsers (lenient JSON), AppSettings + ComposeYaml (app settings <-> compose YAML),
          ImageRegistry (an image's versions from Docker Hub / ghcr.io / other registries),
          SessionStore + SecretBox (Keystore), FileDownloader
  ui/     theme, components, and one package per screen (screen + ViewModel together)
```
