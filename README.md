# Oregon Land Explorer

Identify property owners while exploring offline on Android. Native Kotlin and
MapLibre render local outdoor maps and property boundaries; indexed SQLite serves
owner details without loading whole regions into RAM. No runtime Internet
permission, login or in-app server. OSIP 2024 aerial imagery remains optional.

**Current ownership-capable APK:** [v0.5.0 download, checksums, installation instructions
and test evidence](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/private-owners-v0.5.0).
Install over the existing expanded app; its application ID and signing identity
are unchanged. Do not uninstall or clear storage. Existing maps, original
owner-enabled regions and settings are retained. v0.5.0 has hosted Android 15
emulator verification; v0.3.2 is the version verified by the user on a physical phone.

**Full Marion County maps:** [county vector basemap and 16 parcel packages](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/marion-county-v0.4.0)
cover all 115,870 available county parcel boundaries. These public county packages
exclude owner names. They remain byte-for-byte unchanged by the ownership update.
The smaller Gervais/corridor maps remain bundled; ARM64 and x86_64, Android 8+.

**Countywide real ownership data is still pending an authorized source/export.**
The app now imports private attribute-only owner packages, matched by exact
county/TAXLOT/TAXACCT and bound to each polygon's hash and update date. No
cross-service OBJECTID joins or guessed owners. Missing, conflicting, withheld,
stale and changed associations are explicit. Original owner-enabled packages
retain their names. Public test fixtures use visibly synthetic names; those
fixtures are not bundled in the phone APK.

[Private browser-only preparation and Android import](docs/PRIVATE-OWNERSHIP.md)
explains source terms, required authorization/export fields, private GitHub Actions
and activation. No development software is required on the user's computer.
Production ownership databases must never be uploaded to this public repository,
its issues, releases or public artifacts.

[Executed ownership tests, storage sizes and latency/memory measurements](docs/OWNERSHIP-VERIFICATION.md)
include full county synthetic matching, airplane-mode selection, upgrade/rollback,
600 pan/zoom operations and original real-owner/GPS/aerial regressions.

**Phone-tested fallback:** [unchanged v0.3.2](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/verified-v0.3.2).
Android normally blocks downgrades; do not uninstall to force one.

The [disk-backed parcel update (PR #6)](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/6)
uses local vector PMTiles plus indexed SQLite, retaining exact on-demand property
selection and compatibility with existing downloads. [Installation, package
schema, benchmark reproduction and county scaling](docs/parcel-storage.md).
The county expansion builds on that verified implementation in PR #7; private
ownership support is reviewed in [PR #8](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/8).

- [Android installation, import, coverage, architecture and test scope](docs/offline-android.md)
- [Data sources, attribution and source limitations](docs/data-use.md)
- [Configurable public region boundaries](regions/corridor.json)
- `pipeline/`: source extraction, Tilemaker profile, packaging and GIS validation
- `android/`: native app and instrumentation
- [Preserved first-prototype documentation](docs/offline-android-prototype.md)

**Legal public access remains unknown** unless separately established. An owner
name, road, trail or park polygon is not permission to enter. Production owner
datasets and private home coordinates are never committed. Privately verify
coverage using GPS.

The tested 0.3.2 preview, APK/package downloads and measured before/after results
are linked in [parcel performance results](docs/parcel-performance.md).
