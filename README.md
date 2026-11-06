# Tonal

A Pixel-style Android launcher for Android 12 and newer. It has Material You themed icons, icon pack support, widgets, the Google Discover feed, and Home Assistant light controls built in.

## Download

Get the APK from the [latest release](../../releases/latest). There are two versions, signed with the same key, so you can switch between them without losing your layout:

| File | Google feed | Notes |
| --- | --- | --- |
| `Tonal-<version>.apk` | No | A normal locked-down app build. |
| `Tonal-<version>-feed.apk` | Yes | Built as *debuggable*, because the Google app only shows its feed to debuggable third-party launchers. The catch: anyone who has your unlocked phone connected to a computer with USB debugging on can read Tonal's stored data, including your Home Assistant token. |

After installing, set Tonal as your home app: long-press the home screen, open **Home settings**, then tap **Set as default home app**.

## Features

- **Pixel-style home screen:**
  - pages
  - dock
  - date header
  - search bar with mic
  - app drawer with search and suggestions
  - folders
  - drag and drop
- **Themed icons:** Material You icons tinted from your wallpaper, with an optional "theme all apps" mode for apps that don't provide one.
- **Icon packs:** any pack in the standard ADW/Nova `appfilter.xml` format, including calendar icons and iconback/iconmask.
- **Widgets:** a widget picker grouped by app, and widgets you can resize.
- **Pinned shortcuts:** "Add to home screen" from other apps works, for example Google Maps routes or Chrome pages.
- **Google feed:** swipe right from the first page. This only works in the `-feed` build; see the table above.
- **Home Assistant:**
  - a lights panel, opened from the bulb in the search bar or the long-press menu, grouped by area
  - tap a tile to toggle, drag across it for brightness, or long-press it for color temperature and color
  - weather from a `weather.*` entity, shown in the search bar

## Using it

| Gesture | Result |
| --- | --- |
| Swipe up on home | App drawer |
| Swipe right on the first page | Google feed |
| Swipe down on home | Notification shade |
| Long-press empty space | Wallpaper, widgets, lights, settings |
| Long-press an app | Shortcuts, app info, remove, or drag to move |
| Drag an app onto another | Create a folder |
| Long-press a widget | Resize, remove, or drag to move |

### Home Assistant

In **Home settings > Home Assistant**, set:

- **Home address:** for example `http://homeassistant.local:8123` or a Tailscale address.
- **Remote address** (optional): for example Nabu Casa or your own domain.
- **Access token:** a long-lived access token. Create one under your Home Assistant profile > Security.

Then tap **Test connection**. Tonal tries the home address first and falls back to the remote one.

Other controls in the same section:

- **Choose lights** picks which lights appear in the panel. LED strip segments are hidden until you choose for yourself.
- **Weather entity** picks the weather shown in the search bar.

The token is stored in the app's private storage. It's only sent to the addresses you enter.

### If the Google feed won't open

The Google app only shows its feed to one launcher at a time. If another launcher, or a feed companion such as Hyperion's dock, is installed and running, it can take the feed, and Tonal will show a warning under **Home settings > Feed connection**. Close or uninstall the other app, then return to the home screen.

## Building

You need Android Studio, or JDK 17 or newer plus the Android SDK.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug
./gradlew testDebugUnitTest    # runs on the JVM with Robolectric, no device needed
```

Release builds (`assembleRelease`, `assembleFeed`) are signed with the key described in `~/.tonal/keystore.properties`:

```properties
storeFile=/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

You can also pass `-Ptonal.signing=/path/to/keystore.properties`. Without that file, the release builds come out unsigned.

`testiconpack/` is a small icon pack and pin-request tester for checking those features on a device.

## Not included yet

- Notification dots
- Calendar events in the header
- Widgets pinned from inside other apps
- An A-Z scroller in the drawer
- Per-app icon overrides
