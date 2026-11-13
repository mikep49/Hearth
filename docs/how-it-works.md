# How Hearth works

The details behind each feature, how Hearth talks to CasaOS, and what it does to keep your server safe. For a quick overview, see the [README](../README.md).

Hearth runs on Android 10 or later, phones and tablets.

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
- **Sign out** ends the current login but keeps the server in your list. It also forgets that server's SSH password and key, its widget reading and every login saved in the in-app browser.

### Home

- Live CPU, memory and storage gauges, CPU history and network throughput. They update every 2.5 s, and only while the screen is visible.
- The dot beside the server name is green while the server answers and red when it can't be reached. Tap the name to switch servers.
- The **Storage** card lists each drive: its model, type (HDD, SSD or USB), size and temperature, whether its SMART self-check passes, and how full each of its volumes is. It refreshes every minute, since CasaOS checks every drive to answer. CasaOS reports a drive it can't check (for example when `smartctl` isn't installed) as passing, so "Healthy" means no failure was reported. USB drives and the system drive have no health badge. On servers without this information, Home shows the storage total instead.
- The **Processor** graph covers the last two minutes. Its scale follows the load, so a quiet server isn't a flat line: the top of the graph is 5%, 10%, 20%, 50% or 100%, whichever is the smallest that fits the highest reading with some room to spare, and it's written beside the graph. Below 10% the figure above the graph keeps one decimal.
- Tap the Processor card to open the **Task manager**: the same graph over the last five minutes with the average and the peak, memory, and every app's CPU and memory, busiest first. Tap a column title to sort by app name, CPU or memory. An app with several containers opens to show each one.
- CasaOS only reports whole-server totals, so the Task manager reads each app's use from `docker stats` over SSH. It needs you to sign in once on the Terminal tab, with an SSH key or with **Remember password** on. If your account isn't in the `docker` group, it runs the command with `sudo`. With a key there's no password to give `sudo`, so that only works if `sudo` doesn't ask for one; otherwise add your account to the `docker` group. Hearth only reads this while the Task manager is open, and closes its SSH connection when you leave it.

### Apps

- Lists installed apps with their status. Stopped apps are grayed out. Each app can be started, stopped or restarted, and you can open its web UI or view its logs. Search filters the list. Apps with several containers get a chip per container.
- **Logs** opens an app's container logs full screen. They update every 3 seconds while the page is open (pause to read), and reach back 500, 2,000 or 10,000 lines. Search shows only the lines that match. Copy puts them on the clipboard (the newest 300,000 characters if there are more), and Share sends them as a text file. Terminal color codes are removed.
- **Open:** the app's web UI opens inside Hearth or in your browser, depending on the choice in Settings.
- **Settings** (below Logs in an app's details) shows the same container settings as the CasaOS dashboard: image and tag, name, icon, web UI address, network, ports, volumes, environment variables, devices, command, privileged mode, memory limit, CPU shares, restart policy and capabilities. Passwords stay hidden until you tap them. The pencil edits these settings in a form, and **Edit as YAML** in the menu edits the whole compose file. CasaOS checks your changes before saving (including ports already in use), then restarts the app with them.
- **Version** shows which image tag the app runs and lists the tags its image has, newest first. Docker Hub tags also show dates and sizes. Search finds older tags, or you can type any tag. Tap one to switch the app to it.
- **Update:** when CasaOS has an update for a store app, the row is highlighted and **Update** installs the version the App Store ships.
- **Uninstall** is at the bottom of an app's details and in its long-press menu. You choose whether its data in `/DATA/AppData` is deleted too.
- **App Store** (top of Apps) lists the apps your server's stores offer, with search, categories, screenshots and descriptions, and installs them.
- **Create app** (long-press an app, or the button in the in-app browser's top bar) puts an app's web UI on your home screen with its icon. It opens full screen in its own window, like a native app.
- **Saved logins** (Settings › Apps) lists the apps opened in Hearth that keep you signed in, with a button to sign out of each one or all of them. Hearth keeps the login an app saves (its cookies and site data), not your password. Each app's address and port gets its own WebView profile, so apps on one server don't share logins. Phones whose WebView is too old for profiles share one set for every app, which can only be removed all at once. Turn off **Remember logins** to be signed out of an app whenever you close it.
- **Strict browsing** (Settings › Security & privacy, off by default) stops apps opened in Hearth from using cookies of other sites they embed, and from loading plain-HTTP images or media into an HTTPS page. An app that relies on either may not load fully with it on, so turning it on asks first. Apps opened over plain HTTP still load.

### Files

- Browse `/DATA`. Photos, videos, songs and text files preview inside Hearth (pinch or double-tap to zoom photos). Other files open in an app you pick.
- The sort button orders a folder by name, size or date modified. Tap the same choice again to reverse it. Hidden files (names starting with a dot) are hidden unless you turn on **Show hidden files** there.
- Each item's ⋮ menu has **Select**, **Open with…**, **Share**, **Download**, **Rename**, **Copy to…**, **Move to…** and **Delete**. Downloads go to `Downloads/Hearth` and don't need storage permission. To copy or move, browse to the destination and tap **Paste here**.
- To work on several items at once, long-press one (or pick **Select** in its menu), then tap the others. **Select all** is at the top, and **Copy**, **Move** and **Delete** are at the bottom. It works in search results too, so you can gather items from different folders. Back or ✕ clears the selection.
- **+** uploads photos, videos or any files from the phone, and makes new folders. If an uploaded file's name is already taken, you choose to skip it, keep both or replace it.
- Downloads and uploads keep going when you leave Hearth or the phone sleeps, and ✕ on the progress bar cancels one. If notifications are allowed, a notification shows the progress with a **Cancel** button, and another says how the transfer ended if Hearth wasn't on screen. Android 15 and later stop a transfer that has run in the background for six hours.
- **Open with…** and **Share** download a copy to the phone first. If that finishes while you're away from Hearth or on another tab, the app picker opens when you're back on Files (after unlocking, if Hearth locked meanwhile). Copies are deleted after 24 hours.
- **Search** covers the whole server. CasaOS has no search API, so the first search walks the folders under `/DATA` in the background, and later searches filter that list as you type. Results from the open folder come first, then everywhere else. The walk stops at 100,000 items and shows the best 300 matches. Pull to refresh rescans.

### Android Files

- Off until you turn on **Show in Android Files** in Settings. Then each server you're signed in to shows in the Files app's side menu as **Hearth**, with the server's address under it, and in the file pickers other apps open (for example to attach a file, or to choose a backup folder).
- Browse `/DATA`, open files in other apps, copy files to and from the phone, rename, delete, move and make new folders. Photos get thumbnails. Hidden files follow Hearth's **Show hidden files** choice.
- Opening a file doesn't download it first. Hearth reads just the parts the other app asks for (HTTP range requests), so a video can start playing and a zip can be opened without fetching the whole file.
- Copying or moving within one server happens on the server, like **Paste here** in Hearth. Between two servers, the Files app copies through the phone.
- A file another app saves to the server is written to the phone first and uploaded when the app closes it. Hearth tries the upload three times. If it still fails, the file is saved to `Downloads/Hearth` on the phone instead, and a message says so.
- A save stays on the phone until it's on the server or in `Downloads/Hearth`. If Android ends Hearth before the upload is done, Android starts it again in the background to finish, when it next runs background work with a network connection (often some minutes later), and opening Hearth finishes it right away. If Hearth was ended while the other app was still writing, the next time Hearth runs it uploads what was written, once the file has gone two minutes without changing.
- **The app lock and the Files app don't mix**, since the Files app can't ask for your fingerprint or PIN. Turning on **Lock Hearth** turns off Show in Android Files, and the switch stays unavailable while the lock is on. Turning the lock off later doesn't turn it back on.
- Signing out of a server removes it from the Files app. Turning the switch off removes Hearth entirely, and links other apps saved to server files stop working.

### Terminal

- A shell on the server over SSH. Most CasaOS installs already run an SSH server. If connecting fails, install one on the server with `sudo apt install openssh-server`.
- Sign in with a **password** or an **SSH key**, which also works on servers that don't take passwords. **Create a key** makes an Ed25519 key on the phone: copy its public key and add it as a line of `~/.ssh/authorized_keys` on the server. **Import a key** takes a private key you already use, pasted or picked as a file, in the formats OpenSSH and PuTTY write (Ed25519, ECDSA or RSA), with its passphrase if it has one. Each server keeps its own key.
- The terminal has color and scrollback. Drag or fling to scroll back, and tap the arrow to jump to the latest output. In full-screen programs such as `less`, `nano` or `htop`, scrolling goes to the program instead.
- **Copy:** touch and hold a word to select it, then drag the handles to select more (holding one at the top or bottom edge scrolls). The menu that appears has **Copy**, **Paste** and **Select all**. **Copy all output** in the ⋮ menu copies the whole session, up to the newest 300,000 characters.
- **Paste:** the button at the top, or touch and hold an empty spot. With a keyboard attached, Ctrl+Shift+C and Ctrl+Shift+V copy and paste.
- Two rows of extra keys (Esc, Tab, Ctrl, Alt, arrows, Home, End, Page up and down, and `/ - | ~`). Ctrl and Alt apply to the next key. Arrows and the page keys repeat while held.
- Pinch to change the text size, or use **Bigger text** and **Smaller text** in the ⋮ menu. The size is remembered.
- While the keyboard is open, the title shrinks to one line to leave more room for the terminal.
- **Update server** (⋮ menu) runs `sudo apt update && sudo apt upgrade -y`.
- The session keeps running while you switch tabs. **Disconnect** is in the ⋮ menu.

### Alerts

- Off until you turn on **Alerts** in Settings › Notifications. On Android 13 and later, turning them on asks for permission to show notifications. Each kind can be turned off on its own, there or in Android's notification settings.
- Hearth checks every server you're signed in to about every 15 minutes, even when it's closed, as long as the phone has a network connection. Android may check less often to save battery.
- **Server offline**: after two checks in a row fail, so one dropped request doesn't alert. That includes when your phone is off the server's network. When the server answers again, the alert changes to "back online" and goes away after an hour.
- **Storage almost full**: when total storage or a drive's volume reaches 90%. It alerts again only after dropping below 85%. Each drive's volumes are read at most every 6 hours, since that makes CasaOS run `smartctl`, which can wake sleeping disks. The total is checked every time.
- **App stopped**: when an app that was running is found stopped, or is still restarting at the next check (it keeps crashing). Stopping, restarting, updating or changing an app's settings from Hearth doesn't count for the next hour. Stopping it from the CasaOS dashboard does. Apps already stopped when alerts are turned on aren't reported.
- **App updates**: once for each new update, grouped into one notification.
- Each problem is reported once, not at every check. Tapping an alert opens its server on Home, or on Apps for app alerts. Names of servers and apps don't show on a locked screen. Signing out of a server clears its alerts.

### Lock

- **Lock Hearth** (Settings › Security & privacy) uses the phone's own screen lock (fingerprint, face, PIN, pattern or password), so Hearth stores no PIN of its own. The switch is disabled if the phone has no screen lock.
- When the lock is on, Hearth starts locked. It locks again after you leave it, either right away, after 1 minute (the default) or after 5 minutes. Moving between screens or rotating the phone doesn't lock it.
- Apps opened from home screen icons are behind the same lock. Hearth's screens are hidden in Recents while the lock is on (Android 13+).
- The lock screen covers the app without closing it, so a terminal session or upload keeps running underneath.
- **Lock Terminal** and **Lock apps** are locks of their own, for the terminal and for apps opened in Hearth (home screen icons included). They work with or without Lock Hearth. Each asks when you open what it guards, and again after you close it or leave Hearth for a minute. A shell keeps running while the terminal is locked. While either lock is on, the terminal or app it guards is hidden in Recents (Android 13+).
- Unlocking Hearth while the terminal or an app is in front unlocks that too, so you aren't asked twice in a row.
- Turning a lock on or off asks for your fingerprint or PIN first.

### Widget

- Shows your server's status, CPU load, RAM usage and storage. At 1×1 and 2×1 it shows just the numbers, larger sizes add bars (one stat on its own gets a large number), and 3×2 and up add CPU cores, temperature and the amount of memory and storage used.
- Each widget has its own settings: which of status, CPU, RAM and storage it shows, its theme (the app's, Light, Dark or OLED black) and its text size. Larger text needs a larger widget for the same detail. Showing only the status gives a large Online or Offline with the time of the last reading.
- Each widget can show any saved server or follow the app.
- To open a widget's settings, tap the server name on it, long-press it and pick its settings (Android 12+), or go to Settings → Home screen widgets in Hearth.
- It refreshes every 30 minutes, when you leave Hearth, and when you tap its refresh button. If the server can't be reached, it keeps the last reading and marks it offline.

### Themes

- Everything about the look is on one page, Settings › Appearance.
- System, Light or Dark.
- Six accents: Ember, Ocean, Forest, Violet, Rose, and Dynamic, which uses your wallpaper colors on Android 12+.
- A pure black mode for OLED screens.
- Two styles, with the same colors either way. **Android** follows Material, as on a Pixel: a tab bar across the bottom, and lists whose rows sit a little apart. **One UI** follows Samsung's phones: the tabs float in a pill, each list joins into one card with thin lines between its rows, and switches are plain with a white thumb.
- The choice is remembered and survives sign-out.
- Text uses [Google Sans Flex](https://github.com/googlefonts/googlesans-flex), shipped with the app under the SIL Open Font License 1.1 ([`fonts/GoogleSansFlex-OFL.txt`](../fonts/GoogleSansFlex-OFL.txt)). Monospace text and the widget use the phone's fonts.

### Tablets and landscape

- On screens 600dp wide or more (tablets, and phones turned on their side) the tabs move from the bottom bar to a rail down the side. In the One UI style the pill moves there instead, standing on its end.
- From 840dp, Home puts memory and storage in a second column beside the gauges and graphs.
- Apps and the App Store show two or three cards per row when there's room. Files, Settings and the forms stay in a centred column no wider than 720dp. Logs, the terminal and web pages use the full width.
- Content keeps clear of a camera cutout at the side of the screen.

## How it talks to CasaOS

| Feature | Endpoint |
| --- | --- |
| Login / refresh | `POST /v1/users/login`, `POST /v1/users/refresh` |
| Finding servers | `GET /v1/users/status` (needs no login), falling back to a `<title>CasaOS` check on `GET /` |
| Stats | `GET /v1/sys/utilization` |
| Drives | `GET /v1/disks`, `GET /v1/disks/usb` |
| CasaOS version | `GET /v1/sys/version/current` |
| Apps | `GET /v2/app_management/compose`, `PUT .../compose/{id}/status`, `GET .../compose/{id}/logs` |
| App settings | `GET /v2/app_management/compose/{id}` with `Accept: application/yaml`, `PUT .../compose/{id}?check_port_conflict=true` with the YAML |
| App version | Switching writes the new tag with the app settings call above. App Store version: `GET /v2/app_management/apps/{id}/stable`; update: `PATCH /v2/app_management/compose/{id}` |
| Files | `GET /v1/folder?path=`, `GET /v1/file?path=` (with a `Range` header for Android Files); new folder `POST /v1/folder`, rename `PUT /v1/file/name`, delete `DELETE /v1/file`, copy / move `POST /v1/batch/task`, upload `POST /v1/file/upload` (1 MB chunks, like the dashboard) |
| App Store | `GET /v2/app_management/apps`; install: `GET .../apps/{id}/compose` (YAML), then `POST /v2/app_management/compose?check_port_conflict=true`; uninstall: `DELETE .../compose/{id}?delete_config_folder=` |
| Power | `PUT /v1/sys/state/{restart\|off}` |
| Alerts | `GET /v1/sys/utilization` and `GET /v2/app_management/compose` at each check, `GET /v1/disks` and `/v1/disks/usb` at most every 6 hours |

CasaOS expects the raw JWT in the `Authorization` header (no `Bearer`). Endpoints were taken from the CasaOS source and OpenAPI specs.

**Image versions:** CasaOS can't list an image's versions, so the phone asks the image's registry directly. It uses Docker Hub's API (`hub.docker.com/v2/repositories/…/tags`), or the standard registry API (`/v2/<name>/tags/list`, with an anonymous token) for ghcr.io, lscr.io, quay.io and others. Only public images can be listed, and the CasaOS login is never sent to a registry.

**Terminal:** Hearth doesn't use CasaOS's web terminal (`/v1/sys/wsssh`), because that sends the SSH password in the URL over plain HTTP. It connects straight to the server's SSH port with [JSch](https://github.com/mwiede/jsch) (with [Bouncy Castle](https://www.bouncycastle.org/) for Ed25519 keys, which Android lacks before 13) and draws the terminal with Termux's [terminal-emulator](https://github.com/termux/termux-app/tree/master/terminal-emulator) library.

## Security

- CasaOS has no TLS by default, so Hearth allows plain HTTP (`network_security_config.xml`), and your password crosses the network unencrypted. Stay on your home network, or put the server behind HTTPS or a VPN (Tailscale, WireGuard) for remote use.
- SSH is encrypted end to end. On the first connection, Hearth shows the server's host key fingerprint for you to confirm. It uses the same `SHA256:` format as `ssh-keygen -lf`. If the key changes later, Hearth warns you before connecting.
- A remembered SSH password and a saved SSH key (with its passphrase) are encrypted like the CasaOS tokens and wiped on sign-out. A key made on the phone never leaves it: only its public half can be copied. Signing out also closes any open shell.
- Programs on the server can't read or change the phone's clipboard. Text only moves when you copy or paste it.
- Pages in the in-app browser can't read the phone's files or other apps' content (`file:` and `content:` addresses are off).
- Logins saved by apps in the in-app browser stay in Hearth's private app storage, and Hearth turns off Android backups, so they never leave the phone.
- Only Android itself can list the servers Hearth puts in the Files app (the provider requires `MANAGE_DOCUMENTS`). Other apps only get the files or folders you pick for them, and links to a folder can't reach outside it (paths with `.` or `..` are refused). The provider is disabled while **Show in Android Files** is off and whenever the app lock is on.
- The auth token is only sent to the server you signed in to, never to other hosts such as icon CDNs. A unit test covers this.

## Building

You need the Android SDK and a JDK, version 21 or newer. Android Studio comes with both.

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

`./gradlew :app:assembleRelease` makes a shrunk release build. It's signed only if the `hearth.signing` Gradle property (for example in `~/.gradle/gradle.properties`) or the `HEARTH_SIGNING` environment variable points at a `keystore.properties` file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Otherwise the APK is left unsigned, which is how F-Droid builds it before signing it with its own key. The key and its properties file stay outside the repo.

The store listing (descriptions, changelogs, icon and screenshots) is in `fastlane/metadata/android/en-US/`. Each release adds `changelogs/<versionCode>.txt`.

## Code layout

```
app/src/main/kotlin/dev/hearth/casaos/
  data/    CasaClient (OkHttp + token refresh), Parsers (lenient JSON),
           AppSettings + ComposeYaml (app settings <-> compose YAML),
           ImageRegistry (an image's versions from Docker Hub / ghcr.io / other registries),
           SessionStore + SecretBox (saved servers, Keystore encryption),
           Ssh + SshKeys + SshProfileStore (SSH sessions, keys and saved SSH logins), AppUsage (docker stats over SSH),
           ServerFinder (finding servers on the Wi-Fi), FileSearch, FileSort, FileDownloader,
           AppLogs, AppLock, Alerts (what each background check should notify about),
           Transfers (runs one download or upload at a time for the whole app),
           SavedUploads (keeps what other apps save through Android Files until it's uploaded)
  alerts/  the background check (WorkManager), its schedule and notifications
  files/   ServerDocumentsProvider: servers in Android's Files app (ServerDocuments holds its testable parts),
           SaveUploadWorker: has Android restart Hearth to finish uploading those saves (WorkManager),
           TransferService: keeps Hearth alive during a transfer and shows its notification
  widget/  the home screen widget (Glance), its settings screen and saved readings
  ui/      theme, components, and one package per screen (screen + ViewModel together)
```

Unit tests are in `app/src/test/kotlin/dev/hearth/casaos/`. They cover the parsers, the API client (against a mock HTTP server), the SSH shell and keys (against an in-process SSH server), text selection in the terminal, alerts, search, sorting, the app lock and more.
