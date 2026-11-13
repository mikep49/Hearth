<p align="center">
  <img src="docs/logo.svg" width="112" height="112" alt="Hearth logo">
</p>

<h1 align="center">Hearth</h1>

<p align="center">
  A native Android app for <a href="https://casaos.io">CasaOS</a>.<br>
  Check your server, manage your apps, browse your files and open a terminal, all from your phone.
</p>

<p align="center">
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/mikep49/Hearth"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="64" alt="Get it on Obtainium"></a>
</p>

![Home, Apps, Files and Terminal in Hearth](docs/screenshots/tour.png)

## Install

Download the latest APK from [Releases](../../releases/latest) and open it on your phone. Hearth needs Android 10 or later.

To get updates as they come out, add Hearth to [Obtainium](https://obtainium.imranr.dev): tap the badge above on your phone, or add `https://github.com/mikep49/Hearth` in Obtainium yourself.

Sign in with your server's address (for example `192.168.1.20` or `casaos.local`) and the same login you use for the CasaOS dashboard. Hearth can also find CasaOS servers on your Wi-Fi for you.

## Features

| | |
| --- | --- |
| **Home** | Live CPU, memory, network and storage, a Task manager with how much each app uses, and every drive with its health and free space |
| **Apps** | Start, stop, restart and update apps, change their settings or version, and read their logs live |
| **App Store** | Browse and install apps from your server's stores |
| **Files** | Browse, preview, upload, download, share and search everything on the server |
| **Android Files** | Optional: your servers in the Files app's side menu, and in other apps' file pickers |
| **Terminal** | An SSH shell with password or key sign-in, copy and paste, extra keys and a one-tap server update |
| **Alerts** | Optional notifications when a server goes offline, storage is nearly full, an app stops or an update is out |
| **Servers** | Save several servers and switch between them |
| **Lock** | Optional fingerprint, face or PIN lock, for all of Hearth or just the terminal and apps |
| **Widget** | Your server's stats on the home screen |
| **Themes** | System, light or dark, pure black, six accent colors, and an Android or One UI style |
| **Tablets** | A side rail and wider layouts on big screens and in landscape |

![The Appearance page, the Android style, a light theme and a pure black theme](docs/screenshots/themes.png)

## Security

CasaOS uses plain HTTP by default, so your password isn't encrypted on the network. Use Hearth on your home network, or connect through a VPN such as Tailscale or WireGuard when you're away.

Your logins are stored encrypted on the phone, and the terminal uses SSH, which is always encrypted.

## Build it yourself

Open the project in Android Studio and press **Run**, or from the command line:

```
./gradlew :app:assembleDebug
```

## Learn more

[How Hearth works](docs/how-it-works.md) explains each feature in detail, the CasaOS API calls Hearth makes, its security measures, and how to build and test it.

Hearth is an unofficial app and isn't affiliated with CasaOS or IceWhale.

## License

Hearth is released under the [MIT License](LICENSE). It ships the Google Sans Flex font, which is under the [SIL Open Font License 1.1](fonts/GoogleSansFlex-OFL.txt).
