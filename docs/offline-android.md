# Offline Android prototype

## Preserved foundation

PR #1 merged as `0ba8aaec`; PR #2 was open/draft at implementation start.
This branch starts at PR #2 commit `4696b657`. Its exact embedded-image builder
was moved from YAML into `scripts/build-preview.py`; the HTML output and the
source query are preserved. The old browser verifier assumed live cached tiles
and referenced the removed `osipCRS`; it now tests the embedded JPEG with HTTP
requests blocked. No prototype data has been committed.

## Cloud-only build and install

Open the **Offline Android APK** run on this PR. The pipeline retrieves the small
Gervais area, validates it, embeds it, builds Kotlin and lint checks, audits the
APK, and runs Android API 35 instrumentation. No workstation setup is required.
Download `oregon-land-explorer-debug` from the run's Artifacts section while
signed into GitHub. Extract the ZIP, open `app-debug.apk` on Android, and allow
installation from that browser/file manager when prompted. Android 8+ is required;
the APK includes arm64 (including Pixel 7) and x86_64 (emulator).
This is a debug-signed personal prototype. A future CI debug keystore may change;
if Android refuses an update due to signing mismatch, uninstall the prototype
first (this erases its local data). Establish a private stable signing key before
saving user-created field data.

1. Open the app in airplane mode. Wait for the local Gervais aerial map.
2. Pan/pinch; tap orange parcels. Confirm owner, tax lot and assessed acreage.
3. Toggle Parcels; reopen the app offline; try portrait and landscape.
4. Tap GPS and allow precise location. Outdoors, wait for a satellite fix.
   The initial fix may take longer without assisted GPS. Approximate-only location
   may use the Android network provider and is not a reliable offline substitute.
5. If outside this small region, the app warns that the area isn't downloaded.
   Tap Region to return. A warning also appears when the visible viewport crosses
   the configured edge. Parcels at the boundary retain their full source geometry;
   selection outside the configured region is disabled.
6. Info shows the extraction timestamp, sources, bounds limitations and attribution.

Real GPS, phone GPU behavior, battery use and physical-device usability require
user testing. A hosted emulator is not evidence of physical-device verification.

## Data pipeline

`python -m pipeline.build --region regions/gervais.json --output output/region`
Optional `--bbox WEST SOUTH EAST NORTH` overrides the configured region.
Dependencies are pinned in `pipeline/requirements.txt`. CI uses Python 3.12,
JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin 2.1.20 and MapLibre Native 12.0.1.

The parcel query first obtains all intersecting IDs and a separate count, uses
100-ID batches, reconciles each batch exactly, rejects invalid geometries, and
checks the ID set again after extraction. ArcGIS spatial candidates whose returned
geometry does not intersect the exact WGS84 region are explicitly recorded in the
manifest and retained, so no source-selected record is silently dropped. It retains null attributes and never
joins owner names to polygons by list position. Per-record fingerprints bind
source geometry to OBJECTID, TAXLOT, ACRES and OWNERNAME. This detects accidental
packaging swaps; it cannot establish the underlying assessor record's correctness.
Source metadata is retained. ArcGIS is not a transactional snapshot: attribute
changes during extraction remain possible even with a stable ID set.

Imagery uses the proven PR #2 `exportImage` service in EPSG:3857. Every requested
256px image has exact standard XYZ bounds; OSIP's nonstandard cached tile origin
is NOT treated as XYZ. Zooms 13–17 provide a small multiresolution pyramid. At
higher display zooms the app magnifies stored pixels; it does not imply new detail.
Every JPEG is decoded and checked for blankness. Original source URLs and the UTC
extraction timestamp are in `manifest.json`. A successful image decode alone does
not prove aerial/parcel geodetic accuracy; inspect alignment on the phone too.

## Package and mapping engine choice

An `.olepack` is a ZIP containing `manifest.json`, `aerial.pmtiles`,
`parcels.geojson`, `source-record-hashes.json`, and `source-metadata.json`.
Schema 1 specifies WGS84 GeoJSON and a JPEG PMTiles v3 Web Mercator pyramid.
Manifest SHA-256 checksums cover payload files. The app copies the bundled region
to app-private storage and verifies checksums before reading it.

MapLibre explicitly documents local PMTiles support since Android 11.7.0:
https://maplibre.org/maplibre-native/android/examples/data/PMTiles/
The app reads `pmtiles://file:///.../aerial.pmtiles` directly, with no localhost
server and no remote style, glyph or sprite dependencies. PMTiles files are
prepared externally; MapLibre's offline-region download/cache API is not used.
MBTiles is not assumed to be a drop-in format for that API.

The merged manifest removes INTERNET permission, including library declarations.
`ACCESS_NETWORK_STATE` only reports connectivity. Android LocationManager provides
GPS without Google Play Services, login, token or backend.

## Scope of automated evidence

- Unit tests exercise >1 parcel batch, missing records, safety limits, coordinate
  grid consistency and blank/error imagery rejection.
- Package verification checks all expected imagery tiles and geometry/attribute
  fingerprints and checksums.
- APK audit reads the actual ZIP payload and uses `aapt` to verify permissions.
- Instrumentation cold launches with Wi-Fi/mobile data disabled; verifies a native
  photographic snapshot with parcel layers hidden; physically taps a source
  polygon's interior point and checks its owner and tax lot; checks outside-area
  messaging and activity recreation. Screenshots and JUnit/lint reports are artifacts.
- Passing instrumentation does not validate GPS hardware, offline GNSS acquisition,
  all permission scenarios, every Android device, or countywide performance.

## Expansion after device acceptance

1. Marion County: partition into bounded independent regions; benchmark archive
   sizes and native memory; use vector-tiled parcel geometry rather than one huge
   GeoJSON. Reconcile IDs/counts per partition and use stable identifiers to handle
   boundary duplicates. Establish restart/resume and source-update detection.
2. Other counties: introduce explicit adapters for each schema, owner availability,
   date semantics and use terms. Never infer compatible fields by spelling alone.
3. Public ownership: add separately attributed BLM surface-management, USFS and
   Oregon agency ownership layers after checking authoritative metadata and dates.
   Classifications need source-backed codes. Do not infer public status by owner
   substring. Legal access requires a separate layer with evidence, restrictions,
   seasonality and update date; default is Unknown.
4. Region updates: schema is independent of APK; add Android Storage Access
   Framework ZIP import, size/path/zip-bomb limits, full validation in staging,
   atomic activation and rollback. Current app loads the bundled region only;
   user-facing import/download is NOT implemented in this first milestone.
5. Terrain: add an appropriately licensed raster PMTiles basemap as a separate
   optional package, preserving independent attribution and source dates.
6. Owner search: after permissions review, add a local SQLite/FTS index keyed by
   stable county parcel ID, with clear missing-name and stale-data behavior.

Do not download statewide maximum resolution. Cap requests and area before any
larger job. Generated ownership datasets, APKs, packages and source snapshots are
ignored by Git and delivered only as short-lived Actions artifacts. Because this
repository is public, its Actions artifacts are not a private distribution channel;
only this small authorized personal prototype is packaged here. Use private
artifact distribution for broader owner datasets after rights review.
