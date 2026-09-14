# ShieldSkip

Native Android application using Android VpnService for local DNS filtering of advertising/tracker domains.

## Build in GitHub
1. Upload the contents of this folder to the root of a GitHub repository.
2. Open Actions.
3. Select **Build ShieldSkip APK**.
4. Select **Run workflow**.
5. When green, open the run and download **ShieldSkip-debug-apk** from Artifacts.

This project intentionally does not require a Gradle wrapper: GitHub Actions installs Gradle 8.13.

## Scope
The VPN is a real Android VpnService and the app requests the system VPN permission. DNS filtering can block many ad/tracker domains, but it cannot guarantee removal of every advertisement, including ads served from the same first-party domain as content or traffic that bypasses DNS filtering.
