# My Ad Blocker

A personal ad and tracker blocker in two parts:

- **Browser extension** for Chrome and Edge on a PC (`adblocker/`)
- **Android app** that blocks ads and trackers across the whole phone (`android-app/`)

## Download

Latest versions, always at the same links:

- **Android app:** [MyAdBlocker.apk](https://github.com/salmanfariz91/my-ad-blocker/releases/latest/download/MyAdBlocker.apk)
- **Browser extension:** [my-ad-blocker-extension.zip](https://github.com/salmanfariz91/my-ad-blocker/releases/latest/download/my-ad-blocker-extension.zip)

All releases: <https://github.com/salmanfariz91/my-ad-blocker/releases>

## Install the Android app

1. Download `MyAdBlocker.apk` on the phone and tap it. Allow **Install unknown apps** if asked.
2. Open **My Ad Blocker**, tap **Turn on**, and accept Android's connection request.
3. Tap **Update block list** once. After that the lists refresh by themselves about once a day.
4. Set **Private DNS** (in the phone's network settings) to **Off** or **Automatic**, or the app is bypassed.
5. On Xiaomi/Poco phones, turn on **Autostart** for the app and set its battery saver to **No restrictions**.

The key icon and "VPN enabled" are expected: Android only lets an app filter the whole phone
through its VPN slot. Only DNS lookups pass through the app, all filtering happens on the
phone, and no traffic is sent to a VPN server.

## Install the browser extension

1. Download `my-ad-blocker-extension.zip` and unzip it into a folder you will keep.
2. Open `chrome://extensions` (or `edge://extensions`) and turn on **Developer mode**.
3. Click **Load unpacked** and choose the unzipped folder.
4. Optional: click **Details** and turn on **Allow in Incognito**.

If a site misbehaves, click the extension icon and turn off **Block on this site**.

## What they block

| | Extension | Android app |
|---|---|---|
| Banner and display ads | Yes | Ads from ad servers |
| Pop-up and redirect ads | Yes | Yes |
| Trackers and analytics | Yes | Yes |
| Empty ad boxes on pages | Yes | No |
| "Turn off your ad blocker" messages | Common ones | No |
| YouTube video ads | Muted and skipped | No |
| Ads inside apps and games | Not applicable | Yes |

Neither blocks sponsored posts served from a site's own servers (for example in the
Facebook, Instagram or YouTube apps).

## Refreshing the extension's lists

The extension's lists are files in `adblocker/rules/`. To refresh them, run these from the
`adblocker` folder (Node.js required for the first), then reload the extension:

```powershell
node tools\build-easylist.js
```

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\update-lists.ps1
```

## Building the Android app

Open `android-app/` in Android Studio and run **Build > Build APK(s)**. It uses Java only and
has no library dependencies.

## Licence

My Ad Blocker is free software, released under the
[GNU General Public License v3.0](LICENSE). You may use, change and share it, as long as
what you share stays under the same licence.

## Block list credits

The block lists are made by other people and are used under their own licences:

- [EasyList](https://easylist.to/) and [EasyPrivacy](https://easylist.to/): GPLv3 / CC BY-SA 3.0
- [Adblock Warning Removal List](https://easylist.to/): GPLv3 / CC BY-SA 3.0
- [HaGeZi Pop-Up Ads](https://github.com/hagezi/dns-blocklists): GPLv3
- [AdAway default blocklist](https://adaway.org/): CC BY 3.0

The files `adblocker/rules/easylist.json`, `generic.css`, `specific.json` and `popups.json`
are converted from those lists.
