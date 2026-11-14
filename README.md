# Oregon Land Explorer

Native Kotlin / MapLibre Android maps with a continuous offline outdoor basemap
covering rural Gervais, St. Louis Ponds, Willamette Mission and Silver Falls.
Property boundaries and owner popups use independent regional packages; OSIP 2024
aerial imagery is optional. No login, server or runtime internet permission.

Built directly on the physically tested PR #3 implementation. **Oregon Land
Explorer Expanded** installs beside that prototype and preserves it.

**Build/download:** open this PR's successful **Offline Android APK** Actions run,
download `oregon-land-explorer-expanded`, extract it in Android Files and install
`app-debug.apk`. Choose **Region → Import .olepack from Downloads** to install the
larger parcel regions or optional aerial. Android 8+; ARM64 and x86_64.

- [Android installation, import, coverage, architecture and test scope](docs/offline-android.md)
- [Data sources, attribution and source limitations](docs/data-use.md)
- [Configurable public region boundaries](regions/corridor.json)
- `pipeline/`: source extraction, Tilemaker profile, packaging and GIS validation
- `android/`: native app and instrumentation
- [Preserved first-prototype documentation](docs/offline-android-prototype.md)

Ownership and legal access remain **Unknown**. A road, trail or park polygon is
not permission to enter. Generated owner datasets and private home coordinates
are never committed. Check actual CI evidence and privately verify coverage using GPS.
