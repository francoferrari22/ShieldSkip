# ShieldSkip SmartSkip v3

ShieldSkip is a native Android SmartSkip prototype that works **without a VPN**. It uses Android Accessibility to act on controls that another app exposes to accessibility.

## Protection strategy
1. Explicit Skip/Saltar/Omitir ad controls.
2. Close/X/Dismiss controls when ad context is detected.
3. Turbo playback: if an app exposes an accessible playback-speed menu, ShieldSkip tries the highest visible speed up to 20x.
4. Last-resort seek-forward control when the app exposes it and aggressive mode is enabled.

This is intentionally conservative about generic X buttons to avoid closing normal app controls. It cannot guarantee removal of ads that expose no accessible control or playback API. Android does not provide a universal API for one app to force another app's private video player to 20x or delete its ad content.

## UI
- Large animated shield.
- Light / dark / system theme.
- Six accent palettes.
- Aggressive mode.
- Auto-close/X mode.
- Turbo playback mode.
- Fast scan mode.
- Per-app protection selection.
- Statistics and pause timer.

## Build
Use GitHub Actions workflow `.github/workflows/build-apk.yml` and download the APK artifact after a successful build.

After installation, enable **ShieldSkip SmartSkip** in Android Accessibility settings.
