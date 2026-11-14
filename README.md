# Oregon Land Explorer

Native Kotlin / MapLibre Android maps with a continuous offline outdoor basemap
covering rural Gervais, St. Louis Ponds, Willamette Mission and Silver Falls.
Property boundaries and owner popups use independent regional packages; OSIP 2024
aerial imagery is optional. No login, server or runtime internet permission.

Built directly on the physically tested PR #3 implementation. **Oregon Land
Explorer Expanded** installs beside that prototype and preserves it.

**Known-good fallback:** [Expanded Android v0.2 preview](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/expanded-v0.2).
Install `oregon-land-explorer-expanded.apk`, then choose **Region → Import .olepack
from Downloads** for the two larger parcel regions or optional aerial. The vector
corridor is already bundled. ARM64 and x86_64; declared minimum Android 8,
verified on an Android 15 emulator. The expanded build still needs phone testing.

[Measured storage, source coverage, performance and executed tests](docs/offline-comparison.md).

The [disk-backed parcel update (PR #6)](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/6)
uses local vector PMTiles plus indexed SQLite, retaining exact on-demand property
selection and compatibility with existing downloads. [Installation, package
schema, benchmark reproduction and county scaling](docs/parcel-storage.md).
Check that PR's final hosted test result before installing its preview artifacts.

- [Android installation, import, coverage, architecture and test scope](docs/offline-android.md)
- [Data sources, attribution and source limitations](docs/data-use.md)
- [Configurable public region boundaries](regions/corridor.json)
- `pipeline/`: source extraction, Tilemaker profile, packaging and GIS validation
- `android/`: native app and instrumentation
- [Preserved first-prototype documentation](docs/offline-android-prototype.md)

Ownership and legal access remain **Unknown**. A road, trail or park polygon is
not permission to enter. Generated owner datasets and private home coordinates
are never committed. Check actual CI evidence and privately verify coverage using GPS.

The tested 0.3.1 preview, APK/package downloads and measured before/after results
are linked in [parcel performance results](docs/parcel-performance.md).
