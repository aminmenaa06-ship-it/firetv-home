# Fire TV Home

Minimal tvOS-style launcher for Fire TV: Kotlin + Jetpack Compose for TV, no ads, no autoplay.
Apps grid (banner tiles, streaming apps pinned first) and a Settings tab of system shortcuts.

## Remote
- D-pad moves focus; focused tile scales 1.1x with a white border.
- Select opens an app; **hold Select** opens its App Info (force stop / uninstall).
- Back or Home returns to the first app at the top.

## Install
- With a Mac on the same network as the TV: `./deploy.sh <tv-ip>`.
- Without one: install **Downloader** on the TV and open
  `https://github.com/aminmenaa06-ship-it/firetv-home/releases/latest/download/firetv-home.apk`.

## Making it the Home screen
Fire OS 8 marks Amazon's launcher (and `com.amazon.firehomestarter`) as protected packages:
`pm disable-user` fails with "Cannot disable a protected package", and Amazon's Home filter
outranks any third-party launcher. So `./debloat.sh <tv-ip>` turns on this app's accessibility
service instead ("Home button opens Home"). It only watches Amazon's launcher, and when Amazon's
home screen comes up it switches straight here (a brief flash on each Home press). On older Fire
OS versions that still allow disabling, debloat.sh disables the packages outright.

The service can also be toggled on the TV under Settings > Accessibility. Fire OS also refuses
debugging connections from apps on the TV itself, so the setup has to come from another computer.

## Scripts (Mac and TV on the same network, ADB debugging on)
| | |
|---|---|
| `./deploy.sh <ip>` | build release APK, install, launch |
| `./debloat.sh <ip>` | disable `bloat-packages.txt` where Fire OS allows it, else turn on the Home redirect |
| `./restore-stock.sh <ip>` | turn the redirect off, re-enable packages, give Home back to Amazon |


## If something goes wrong
- Want Amazon's Home back: `./restore-stock.sh <ip>`, or turn off "Home button opens Home" under Settings > Accessibility.
- Settings are reachable from this launcher's Settings tab (Network, Developer Options, All Settings).
- Last resort: factory reset by holding **Back + Right** on the remote for 10 seconds.

## Build
JDK 17 (`brew install openjdk@17`), Android SDK at `~/Library/Android/sdk`, then `./gradlew assembleRelease`.
