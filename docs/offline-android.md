# Expanded offline Android maps

This implementation starts at PR #3 commit `384fa25653ce2348589b583e926da7d60ffcabd4`,
which the user installed and tested on a physical phone. The original branch and
its APK are preserved. [Prototype architecture/evidence](offline-android-prototype.md).

## Android installation and import

1. Open the [v0.2 preview release](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/expanded-v0.2)
   in the phone's browser. Download and install `oregon-land-explorer-expanded.apk`.
   Allow installation from that browser/file manager when Android prompts.
   ARM64 and x86_64 are included; minimum Android 8 is declared, and API 35 is tested.
   The Actions artifact is also available as a ZIP containing `app-debug.apk`.
2. Open **Oregon Land Explorer Expanded**. It installs beside the tested prototype;
   do not uninstall the original app. Future expanded-app APKs use a persistent
   signing key held in repository Actions secrets, with a local private backup.
3. The outdoor corridor and the original 803-parcel Gervais extract are bundled.
   No additional download is needed to open the continuous road/trail basemap.
4. Download `gervais-countryside.olepack` and `silver-falls.olepack` from that
   release into Downloads; keep these archives intact. For broader property details, choose **Region → Import .olepack from Downloads**
   and select `gervais-countryside.olepack`. Repeat with `silver-falls.olepack`.
   The Android document picker also supports other document providers. The app
   copies files to private storage; Downloads can subsequently be removed.
5. Optional: import `gervais-original-aerial.olepack`, then select **Region → Show
   installed optional aerial**. This is the exact verified OSIP 2024 payload,
   covering only the original small Gervais rectangle. Outdoor maps remain below
   it. Select **Use outdoor basemap** to switch back. Parcels remain selectable.
6. **Region → Manage installed packages** enables/disables individual packages,
   shows their coverage, restores the retained previous version after updates,
   and deletes downloaded packages after confirmation. Bundled core maps are retained.
7. Turn on airplane mode. Visit each destination from the Region menu, pan and
   zoom, and tap property boundaries. GPS requests Android precise-location
   permission. Use it outdoors to verify your home coverage privately. Neither
   coordinates nor location history are uploaded or written to repository files.

The app has **no INTERNET permission**, server, login, remote styles, sprites or
font requests. A browser needs connectivity only to download new files. Installed
regions open without connectivity. `SHA256SUMS.txt` covers distributed archives
and APK; the importer independently verifies all manifest payload SHA-256 hashes.
Hashes detect corruption; they do not authenticate an unknown package publisher.
Import trusted downloads. Raw `.pmtiles` alone are intentionally unsupported:
wrap them in a versioned `.olepack` with bounds, attribution and checksums.

## Initial geography

All bounds are WGS84 `[west, south, east, north]`, not home coordinates.
Edit [regions/corridor.json](../regions/corridor.json) to produce another bounded
extract using the same hosted workflow. Build configuration is public; private
home coordinates must never be committed.

| Package | Bounds | Contents |
|---|---|---|
| `willamette-silver-outdoors` | `[-123.10,44.80,-122.48,45.25]` | Continuous outdoor basemap, stored zooms 8–15, displayed through 19 |
| `gervais-countryside` | `[-123.10,45.015,-122.80,45.22]` | Marion parcels around Gervais, St. Louis Ponds and Willamette Mission, including approach roads' surroundings |
| `silver-falls` | `[-122.76,44.80,-122.48,45.02]` | Marion parcels around Silver Falls grounds and approaches |
| `gervais-original-parcels` | `[-122.910,45.100,-122.890,45.115]` | Exact PR #3 parcel geometries and owner records; bundled |
| `gervais-original-aerial` | same original bounds | Optional exact PR #3 aerial pyramid, stored zooms 13–17 |

Regional rectangles include complete source-selected taxlots, with a full
geometry/attribute fingerprint audit. Duplicate Marion OBJECTIDs in overlapping
packs are resolved in favor of the later extraction, without duplicating popups.
New extracts may contain invalid source topology. The regional builder uses
Shapely's explicit `make_valid(structure)` only when the resulting polygon is
valid and its boundary follows the original source linework (within a `1e-7`
degree buffer, about 1 cm). This may remove invalid internal edges where source
parts overlap; it is not a claim that every original edge is a true property edge.
Original invalid geometry, source fingerprint, display fingerprint, reason, area
change and removed/internal-edge Hausdorff distance remain in the audit. Repaired properties have a popup notice and
are listed in the manifest/test report. This does not establish the true legal
boundary. The original PR #3 payload is never repaired or refetched. By default
the original extractor still rejects invalid geometry; repair is explicitly
requested only by the expanded regional builder.

Basemap and parcel coverage warnings are separate. A vector map outside the
parcel rectangles does not imply owner information is installed there. The
Marion source contains no neighboring-county property records, including across
the Willamette River. Vector coverage extends to both sides of the river.

## Outdoor map and source limitations

Geofabrik's official Oregon OSM PBF is downloaded once in hosted CI. Osmium
extracts a buffered region and completes multipolygons; Tilemaker **v3.2.0**
compiles the original `oregon-outdoor-v1` Lua profile directly to vector PMTiles.
No requests are made to OSM's public raster tile servers. The extract SHA-256,
OSM header timestamp, tool version, bounds and layer counts are retained.

The style draws roads, forest tracks, hiking paths, cycleways, streams, ponds,
woodland, park polygons, farms, buildings and labels. Text glyphs ship locally
(Noto Sans, SIL OFL 1.1; Latin/Greek/Cyrillic and general punctuation ranges).
Geometry retained at zoom 15 overzooms cleanly for hiking/parcel inspection;
overzooming adds no new source information. This is an outdoor map, without
contours, hillshade, turn-by-turn routing or live closure data.

The validator decodes every generated vector tile, checks continuous tile
coverage at all stored zooms, and reports feature classes and names at each
target's surrounding area. Name checks compare selected trail names with OPRD
maps. An unmatched name may be an unnamed/misspelled path; it is not proof of a
missing physical trail. Published maps and source geometry need field checks.

- [Silver Falls official whole-park map](https://stateparks.oregon.gov/index.cfm?do=main.loadFile&load=_siteFiles%2Fpublications%2F%2F2024-silver-falls-map-board-web.103937.pdf)
- [Silver Falls Trail of Ten Falls map](https://stateparks.oregon.gov/index.cfm?do=main.loadFile&load=_siteFiles%2Fpublications%2F%2F2025-silver-falls---tenfalls-southdistrict.074640.pdf&pubDate=20250712)
- [Willamette Mission official map](https://stateparks.oregon.gov/index.cfm?do=main.loadFile&load=_siteFiles%2Fpublications%2FWillamette_Mission_brochure_LOW_RES105832.pdf)

Mapped `access=no/private` is shown in purple; other trails use rust dashes,
tracks use brown dashes. These are source tags, not legal-access determinations.
Owner names, park shading and a path's presence do not establish permission to
enter. Ownership classification and legal public access stay **Unknown**.

## Package validation and updates

Schema 2 ZIPs contain `manifest.json` and a fixed payload set for one kind:
`basemap.pmtiles` (outdoor vector), `aerial.pmtiles` (optional JPEG) or
`parcels.geojson` plus source audit/metadata. Schema 1 imports remain supported,
including the original `.olepack`. Packages cannot supply arbitrary styles,
remote URLs, fonts or executable code.

The importer rejects unknown schemas/kinds, invalid IDs/bounds/revisions, duplicate
or non-root filenames, missing/unlisted payloads, bad hashes, unsupported PMTiles
headers/types/offsets/zooms, excessive expansion and malformed parcel structures.
Limits: 8 entries, 512 MiB total expansion, 384 MiB per PMTiles, 64 MiB GeoJSON,
35,000 parcels and 3 million vertices; manifests are capped at 128 KiB.
A corrupt archive does not activate. Before publishing a data update, increment
`revision` in `regions/corridor.json`; rebuilding the same revision does not
authorize replacement of different installed bytes. A normal update requires a strictly higher
revision; identical reimports are harmless. Rollback is an explicit user action.

Validated versions are immutable. Installation stages and syncs files, renames the
complete directory, then commits an `AtomicFile` registry. A process interruption
leaves either the previous or complete new registry. Prior versions remain for
rollback; installed data are not overwritten by APK assets. Explicit deletion
removes a downloaded package and its retained versions. No broad storage permission
is required. Installed checksums are rechecked before maps are loaded.

## Hosted verification and comparison

The workflow produces APK/packages, `SHA256SUMS.txt`, `storage.json`,
`vector-validation.json`, `packages-validation.json`, and separate test evidence.
It builds/lints Android, audits actual APK payloads/permissions, checks every parcel
source fingerprint and polygon, and runs API 35 instrumentation in airplane mode.
The emulator tests exercise native rendering, original parcel owner popups,
Android document import, multiple regions, GPS test-provider recentering,
optional aerial selection, recreation, checksum/path rejection and update rollback.

`performance.json` compares the original APK and expanded APK on the same emulator:
three force-stopped activity launches, default-map idle memory, scripted panning
and Android UI frame statistics. Activity launch time is not completed tile-render
latency; UI statistics are not a full native map GPU benchmark. Extents differ.
Physical-phone GPU performance, GNSS acquisition and home coverage still require
phone verification. The first prototype's physical-phone acceptance does not
verify this expanded build. Final measured results are recorded separately after CI.

The St. Louis Ponds shortcut uses ODFW's documented site near
`45.114,-122.932`, rather than the St. Louis settlement farther north.
[ODFW site-location record](https://nrimp.dfw.state.or.us/RE/pdf/2853.pdf).
This is a public destination coordinate, not a home location.

### Emulator graphics

MapLibre 12.0.1 is preserved from the verified prototype. SwiftShader can make
its symbols and dashed paths transparent even when feature queries succeed:
[upstream renderer report](https://github.com/maplibre/maplibre-native/pull/4625).
Hosted tests use Mesa llvmpipe through Xvfb and the emulator's host OpenGL backend.
Tests additionally inspect rendered label/trail pixels, not just feature counts.
No test-only renderer workaround or font/style fallback is injected into the APK.
