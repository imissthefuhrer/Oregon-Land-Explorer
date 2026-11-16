# Oregon Land Explorer

Identify property owners while exploring offline on Android. Native Kotlin and
MapLibre render local outdoor maps and property boundaries; indexed SQLite serves
owner details without loading whole regions into RAM. No runtime Internet
permission, login or in-app server. OSIP 2024 aerial imagery remains optional.

**Current complete private delivery:** [v0.6.0 APK, basemap, all 16 parcel regions
and real county owners in one ZIP](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/releases/tag/marion-complete-v0.6.0).
Sign into the owning GitHub account in the phone browser. Owner-inclusive data
and real-owner screenshots are private; they are not published in this repository.
Install over the existing expanded app; its application ID and signing identity
are unchanged. Do not uninstall or clear storage. Existing maps, original
owner-enabled regions and settings are retained. v0.6.0 has hosted Android 15
emulator verification; v0.3.2 is the version verified by the user on a physical phone.

**Full Marion County maps:** [county vector basemap and 16 parcel packages](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/marion-county-v0.4.0)
cover all 115,870 available county parcel boundaries. These public county packages
exclude owner names. They remain byte-for-byte unchanged by the ownership update.
The smaller Gervais/corridor maps remain bundled; ARM64 and x86_64, Android 8+.

**Real private county ownership has passed end-to-end Android acceptance:**
115,870 source associations were checked; actual owner popups passed in seven
county areas on fresh and in-place upgrade installations in airplane mode.
The existing retrieved dataset was reused with no new county extraction.
Source retrieval time is separate from an unknown ownership snapshot date.
The app imports private attribute-only owner packages, matched by exact
county/TAXLOT/TAXACCT and bound to each polygon's hash and update date. No
cross-service OBJECTID joins or guessed owners. Missing, conflicting, withheld,
stale and changed associations are explicit. Original owner-enabled packages
retain their names. Public tests remain separate from the real-data acceptance
performed exclusively in private CI.

[Complete ZIP phone instructions and executed real-data results](docs/complete-county-import.md)
explain installation, matching, preserved settings, source provenance and testing.
No development software is required on the user's computer.
Production ownership databases must never be uploaded to this public repository,
its issues, releases or public artifacts.

[Executed ownership tests, storage sizes and latency/memory measurements](docs/OWNERSHIP-VERIFICATION.md)
document the earlier v0.5 milestone's synthetic matching, airplane-mode selection, upgrade/rollback,
600 pan/zoom operations and original real-owner/GPS/aerial regressions.

**Phone-tested fallback:** [unchanged v0.3.2](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/verified-v0.3.2).
Android normally blocks downgrades; do not uninstall to force one.

The [disk-backed parcel update (PR #6)](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/6)
uses local vector PMTiles plus indexed SQLite, retaining exact on-demand property
selection and compatibility with existing downloads. [Installation, package
schema, benchmark reproduction and county scaling](docs/parcel-storage.md).
The county expansion builds on that verified implementation in PR #7; private
ownership support is reviewed in [PR #8](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/8).
The complete private delivery and provenance correction are reviewed in
[PR #9](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/9).

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
