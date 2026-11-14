# Disk-backed parcels (Android preview 0.3)

This implementation builds on merged PR #4, final commit `e61cbef`, with the
unchanged `expanded-v0.2` release as the known-good fallback. It retains MapLibre
12.0.1, the outdoor corridor, local glyphs, optional OSIP imagery, GPS, the native
interface and the absence of INTERNET permission. No geographic expansion is
included. [Existing source/data-use safeguards](data-use.md) continue to apply.

## Installation and update

Use the successful **Offline Android APK** run linked from the optimization PR.
Download its `oregon-land-explorer-optimized-preview` artifact and extract the ZIP.
The artifact contains `app-debug.apk`, five `.olepack` archives and SHA-256 sums.
Test evidence is a separate `offline-test-evidence` artifact. Artifacts require a
GitHub login and expire after 30 days; the old preview release remains available.
Do not infer successful testing from the presence of an APK artifact: check the
job's final result and the performance report.

1. Keep the existing app installed. Install the new APK as an update. Application
   ID remains `org.oregonlandexplorer.expanded`; version code is 3 and CI uses the
   existing persistent signing secret. The original prototype remains independent.
2. Import the new `gervais-countryside.olepack`, `silver-falls.olepack` and
   `gervais-original-parcels.olepack` through **Region → Import .olepack from
   Downloads**. These use the same region IDs with revision 2. The last archive
   upgrades the bundled original's geometry storage on existing installations.
   A fresh installation already bundles its optimized original parcels.
3. Existing packages and settings survive the APK update. Until replaced, old
   GeoJSON parcels use a streamed SQLite sidecar and a bounded viewport display.
   Dense legacy views explicitly request a closer zoom or a schema 3 update.
4. In **Manage installed packages**, **Restore previous version** restores the
   previous archive without deleting it. Rollback does not erase the highest
   source date/revision already seen; a subsequent ordinary update must still be
   newer. Identical reimports are harmless.
5. Optional aerial and outdoor basemap archives retain their PR #4 bytes. Existing
   versions need no replacement. Test in airplane mode, then verify actual GPS,
   coverage, navigation and memory on the physical phone.

Do not uninstall the app to resolve an update/signature error: that would remove
its internal data. Retain the installed app and inspect the signing/build evidence.
The old `expanded-v0.2` APK is preserved, but Android does not normally install an
older version code over a newer one. Package rollback is available within 0.3;
an APK downgrade is a separate Android operation and is not promised by that menu.

## Package contract

Schema 3, kind `parcels`, `parcel_schema=oregon-parcels-v1` contains exactly:

| Payload | Purpose |
|---|---|
| `parcels.pmtiles` | Local PMTiles v3, gzipped MVT; stored zooms 12–16, overzoomed through 19 |
| `parcels.sqlite` | SQLite application ID `0x4f4c4550`, user version 1; exact polygons and metadata |
| `source-record-hashes.json` | Original audited fingerprints, repair reasons and original invalid geometries |
| `source-metadata.json` | Original service/item provenance |
| `parcel-validation.json` | Independently decoded tile/SQLite reconciliation report |
| `manifest.json` | Coverage, county, source extraction date, package date, revision and payload hashes |

SQLite `parcels` has a unique `county,source_id` index and unique `parcel_key`
(`41047:OBJECTID` for this existing Marion source). It retains tax lot, owner,
acreage, all original display properties, unquantized display geometry and audit.
`parcel_bounds` is an R-Tree keyed to the row ID. `metadata` records county, source
and original extraction timestamp. A source service change must explicitly define
the ID namespace/migration; county alone cannot make unrelated source ID schemes
equivalent. Future county generators must supply that county's identifier.

MVT layers `parcels` (fill) and `boundaries` (linework) contain **only**
`parcel_key`; no owner strings or popup records are duplicated into tiles.
Repeated tile fragments retain the same compound identity. Boundary lines come
from original polygon boundaries before clipping, so tile edges cannot become
false property outlines. Holes and multipolygons are retained. Overview geometry
uses topology-preserving half-pixel simplification. Zoom 16 uses no simplification
and an 8192-unit tile grid (about 0.075 projected meters per unit, about 0.053 m on
the ground here). Grid rounding can collapse subpixel components; SQLite retains
every exact component. These are approximate assessor polygons, never a survey.

Every source-selected ID, including explicitly reported outside-envelope
candidates, must exist exactly once in SQLite and appear at maximum tile zoom.
The validator decodes all tiles and independently reassembles maximum-zoom pieces
to check that interiors and holes wider than two grid units match the source
display polygons. Subpixel sliver changes are counted and reported explicitly. Original and
repaired display fingerprints are reconciled separately. Invalid source geometry
is rejected unless the existing explicit, audited repair policy succeeds.

The document-picker importer preserves immutable versions and the atomic registry.
It checks allowed filenames, schema, checksums, expansion limits, PMTiles headers,
SQLite application/schema version, identity indexes/counts, integrity and exact
geometry envelopes before activation. SQLite connections are read-only, use a
1 MiB page-cache target and disable memory mapping. Installed hashes are rechecked
off the UI thread. Checksums detect corruption; they do not authenticate a publisher.

## Runtime and selection

MapLibre loads visible local vector tiles directly from disk. Panning does not
rebuild the parcel source. Native old-zoom tile caching is disabled and native
prefetch zoom delta defaults to zero; `PARCEL_PREFETCH_ZOOM_DELTA` is an explicit
tuning constant. It is a lower-zoom prefetch setting, not a geographic margin.
The legacy compatibility viewport has a 10% geographic margin, at most 1,000
features and 2 MiB of geometry. No whole-region FeatureCollection is retained.

Selection is enabled at zoom 14 and above. At overview zooms the app asks for a
closer inspection zoom. A tap queries spatial candidates, resolves each compound
identity against the newest enabled installed record, and performs point-in-polygon
against the exact SQLite geometry. Holes exclude the surrounding property;
disjoint parts share an identity. A moved newer boundary cannot resurrect an older
owner at the old location. Actual overlapping tax lots offer a chooser; repeated
tile fragments and regional overlap do not duplicate the same identity.

Database work uses one worker and at most one pending task. New camera movement
invalidates old callbacks and replaces queued work; rapid navigation cannot build
an unbounded task queue. Reads use short-lived connections; owner records are
loaded only for selection. At most 256 spatial candidates are allowed per tap;
exceptionally dense source overlap is surfaced as an error. Memory-trim callbacks
reach MapLibre. A missing record or missing download says coverage is unavailable,
never that a location has no owner. Ownership/public access remain Unknown.

## Reproduction

The hosted workflow first runs GIS tests, then downloads six checksum-pinned
PR #4 release files. `python -m pipeline.optimize_release --source work/expanded`
converts those exact extracts without network refetching, preserving source dates
and ownership associations. This is the comparison/delivery path.

For a later intentional source refresh, `pipeline.build_corridor` still uses the
verified ArcGIS ID/count/batch reconciliation and explicit repair policy, then
automatically produces schema 3. Increment `regions/corridor.json` revision for
an update; a newer package revision cannot authorize an older extraction date.
Generated ownership databases, geometries, archives and APKs remain ignored by Git.

The same benchmark test is compiled against unchanged PR #4 and the new app and
executed on the same API 35 x86_64 Pixel 2 Mesa emulator, with connectivity disabled.
It covers original, countryside, Silver Falls and both enabled regions, 15 taps
per scenario, 600 camera changes in the extended scenario, background/restoration,
package reimports and an in-place APK update retaining old installed data. Raw
PSS, Java and native allocation samples, frame-ready times, cold-launch shell
timings and installed payload sizes are retained. See `parcel-performance.json`.
Instrumentation includes runner memory; activity timings and software rendering
are not phone GPU or physical GNSS measurements. Minimum Android 8 remains
declared; do not claim it has been device-tested from API 35 evidence alone.

## Scaling to Marion County

1. Confirm this exact source's publisher, update vintage and owner redistribution
   terms with the county before countywide extraction; current authorization is
   the same two bounded personal-use extracts. Do not infer a license from HTTP
   availability or move owner data into Git.
2. Extract with the existing ID/count reconciliation into bounded preparation
   partitions. Keep county/source identity consistent across partitions; retain
   exact geometry, extraction dates and repair fingerprints.
3. Build separately installable PMTiles/SQLite packages with explicit coverage
   and safety limits. Partition a preparation job that exceeds the current
   100,000 tile budget rather than removing records. Runtime rendering remains
   driven by visible tiles; no countywide ownership map is held in Kotlin memory.
4. Repeat the same viewport benchmark while adding partitions, including boundary
   seams and stale-record movement. Measure tile density, package count/source
   overhead, PSS, page reads and selection latency. If very many package sources
   become material, consolidate offline regional archives at preparation time;
   avoid an unbounded per-feature deduplication table in the app.
5. Test a low-RAM Android phone and the oldest supported API before promoting a
   countywide build. Remaining costs include basemap rendering/GPU buffers,
   viewport tile density, one selected exact polygon, source handles and archive
   directories. Total package disk size is not a RAM measurement.
