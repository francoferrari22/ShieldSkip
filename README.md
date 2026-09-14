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


## SmartSkip v4 — Ad Vision + Play Store Guard

This revision adds screenshot OCR using ML Kit when an ad is visually present but its controls are not exposed to Accessibility. It recognizes visible ad text such as “Anuncios”, “Descargar ahora”, “Más info”, “Saltar” and “Cerrar”, and can tap the detected skip/close control. It also recognizes exposed fast-forward/seek controls.

If an ad CTA launches Google Play immediately after an ad was detected, SmartSkip uses the Accessibility global Back action to return to the protected app instead of leaving the user in the Play Store.

The engine still does not modify another app's private media pipeline; playback speed is only changed when the target app exposes an accessible speed control.
