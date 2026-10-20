# Fire TV Home

Minimal tvOS-style launcher for Fire TV: Kotlin + Jetpack Compose for TV, no ads, no autoplay.
Apps grid (banner tiles, streaming apps pinned first) and a Settings tab of system shortcuts.

## Remote
- D-pad moves focus; focused tile scales 1.1x with a white border.
- Select opens an app; **hold Select** opens its App Info (force stop / uninstall).
- Back or Home returns to the first app at the top.

## Install from the TV (no computer needed)
1. Fire TV: Settings > My Fire TV > Developer Options > **ADB Debugging** on.
2. Install **Downloader** from the Appstore; allow it under Developer Options > Install unknown apps.
3. In Downloader, open
   `https://github.com/aminmenaa06-ship-it/firetv-home/releases/latest/download/firetv-home.apk`
   and install.
4. Open **Home**, go to the **Settings** tab, choose **Use as Home Screen**, and approve the
   "Allow USB debugging?" prompt (tick Always allow). The app connects to the TV's own ADB
   (localhost:5555), disables the packages below and makes itself Home. If Fire OS doesn't hand
   Home over, it undoes everything and says so.
5. **Restore Amazon Home** in the same tab reverses it.

## Scripts (Mac and TV on the same network, ADB debugging on)
| | |
|---|---|
| `./deploy.sh <ip>` | build release APK, install, launch |
| `./debloat.sh <ip>` | disable packages in `bloat-packages.txt`, make this the Home app |
| `./restore-stock.sh <ip>` | undo debloat, give Home back to Amazon's launcher |

Fire TV's launcher has a higher-priority HOME filter than any third-party app, so Home only
comes here once `com.amazon.tv.launcher` is disabled. Setting a preferred Home alone does nothing.

## If something goes wrong
- Want Amazon's Home back: Settings tab > **Restore Amazon Home**, or `./restore-stock.sh <ip>` from a Mac on the same network.
- Settings are reachable from this launcher's Settings tab (Network, Developer Options, All Settings).
- Last resort: factory reset by holding **Back + Right** on the remote for 10 seconds.

## Build
JDK 17 (`brew install openjdk@17`), Android SDK at `~/Library/Android/sdk`, then `./gradlew assembleRelease`.
