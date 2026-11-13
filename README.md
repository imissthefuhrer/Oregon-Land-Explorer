# Oregon Land Explorer

Native Kotlin / MapLibre Android prototype with a real, locally installed Gervais
(Marion County) aerial and parcel region. No login, API keys, backend or runtime
internet permission. GPS uses Android LocationManager, including on microG phones.

**Build/download:** open this PR's **Offline Android APK** Actions run, then download
`oregon-land-explorer-debug`. Extract and install `app-debug.apk` on Android 8+.
Build and emulator pass/fail status must be checked on the actual run; source code
alone is not proof of a working APK. Physical-device testing remains required.

- [Install, test, architecture and expansion](docs/offline-android.md)
- [Data-use review and redistribution limits](docs/data-use.md)
- `pipeline/`: configurable real GIS preparation and validation
- `regions/`: bounded region definitions
- `android/`: native Android app and instrumented tests
- `scripts/build-preview.py`: preserved PR #2 self-contained browser prototype

Public ownership and legal access are separate. This first build does not install
a verified public-land classification or legal-access layer; both remain Unknown.
Generated ownership datasets are not committed to the public repository.
