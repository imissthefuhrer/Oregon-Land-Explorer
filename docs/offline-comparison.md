# Expanded map delivery and measured comparison

Verified functional commit `846ce69`, built from the accepted PR #3 commit
`384fa256`. [Successful hosted run](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43554821037).
[Download the preview APK and packages](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/expanded-v0.2).
[Android installation/import instructions](offline-android.md).
The original app remains installed independently and is archived unchanged in
[verified-prototype-v1](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/verified-prototype-v1).

## Actual storage

| Item | First prototype | Expanded |
|---|---:|---:|
| APK, bytes | 34,878,608 | 54,848,771 |
| APK, MiB | 33.26 | 52.31 |
| Configured basemap rectangle | 2.618 km² | 2,438.2 km² |
| Basemap PMTiles payload, bytes | 1,527,226 aerial | 21,195,859 vector |
| Basemap payload, MiB | 1.46 | 20.21 |
| Stored basemap zooms | 13–17 aerial | 8–15 vector; display through 19 |

The expanded rectangle is about **931 times** the prototype's area. The APK is
larger because it bundles the continuous vector corridor, local fonts and the
original parcel extract. Expanded parcel regions and aerial imagery are separate
imports. This compares actual files with different map formats and detail levels;
it does not claim a measured full-corridor aerial size or universal compression ratio.

| Download | Archive bytes | Archive MiB | Installed payload bytes |
|---|---:|---:|---:|
| `gervais-countryside.olepack` | 8,935,553 | 8.52 | 31,418,026 |
| `gervais-original-aerial.olepack` | 1,449,161 | 1.38 | 1,528,424 |
| `gervais-original-parcels.olepack` | 169,689 | 0.16 | 710,210 |
| `silver-falls.olepack` | 2,162,966 | 2.06 | 7,367,234 |
| `willamette-silver-outdoors.olepack` | 21,148,329 | 20.17 | 21,198,837 |

The APK already contains `willamette-silver-outdoors` and
`gervais-original-parcels`; importing those separately is unnecessary on first
installation. Import `gervais-countryside` and `silver-falls` for broader owners
and property boundaries. Aerial is optional and covers the original small Gervais
rectangle. Installed figures cover package payloads, not Android's total app/cache
accounting. Browser downloads can be removed after installation/import. Both
ABIs from the prototype (ARM64 and x86_64) remain included.

APK SHA-256: `038af026e4fdf64fe7eee372657bbe9954dd12320442e5caf782d86a4b704e05`.
Every distributed archive hash and every internal payload hash were checked;
the bundled archives in the downloaded APK match the region downloads exactly.

## Source coverage and limitations

Basemap: official Geofabrik Oregon bulk PBF, OSM snapshot
`2026-11-13T21:22:20Z`;
source SHA-256 `bcc52e9faa133a1c9cf0d0745b930e6b7d836195b39a56e152df53f7afa7e7ed`. Tilemaker v3.2.0 compiled
**4,577 decoded vector tiles**, with continuous tile coverage at every
stored zoom. No OSM public tile-server downloads were used. Roads, tracks,
paths, woods, parks, ponds, waterways, buildings and local labels are present.
The OSM-derived database remains under ODbL 1.0, credited continuously on the map.

- **Gervais countryside**: park polygons Centennial Park, Frizbee, Basketball, and Football Park, Gervais Community Park, Gervis Field Park, Hermanson Park I, Legion Park, Library Park, Locomotive Park, Nichol Sanctuary, North Front Street Park, Saint Louis Fish Ponds County Park, Senior Estates Park, Settlemier Park, Trillium Park, Valor Wildlife Study Area, Woodburn Downtown Plaza; no selected reference names missing after explicit naming-variant reconciliation.
- **St. Louis Ponds**: park polygons Saint Louis Fish Ponds County Park; no selected reference names missing after explicit naming-variant reconciliation.
- **Willamette Mission State Park**: park polygons Maud Williamson State Recreation Site, Spring Valley Access State Park, Willamette Mission State Park; no selected reference names missing after explicit naming-variant reconciliation.
- **Silver Falls State Park**: park polygons Santiam State Forest, Silver Falls State Park; no selected reference names missing after explicit naming-variant reconciliation.

St. Louis Ponds is checked around ODFW's site at approximately
`45.114,-122.932`, with native water polygons and visible paths; the shortcut
opens the pond complex. The [ODFW source](https://nrimp.dfw.state.or.us/RE/pdf/2853.pdf)
is a public destination reference, not a private home location.

Name variants are explicitly reported, including Willamette Vision
Education/Educational Trail, Howard Creek Loop/Loop Trail, Buck Mountain
Loop/Loop Trail, and Racket/Rackett Ridge. No selected reference road/trail names
are missing after reconciliation. This is a selected-name and geometry audit,
not an exhaustive field survey or verification of all current closures, trail
conditions or accessibility. No legal public access is inferred from ownership.

Parcels retain the existing assessor-derived ArcGIS service and its item/layer
metadata. The item is credited to Marion County and published by
`ikintz_cityofstayton`; exact tax-roll vintage and independent publisher authority
remain unverified. Records across the river in other counties are unavailable
from this Marion source. See [data-use review](data-use.md).

| Parcel package | Source-selected records | Nonempty owners | Source-selected candidates outside exact bbox | Audited topology repair IDs |
|---|---:|---:|---:|---|
| gervais-countryside | 20,966 | 20,966 | 1,965 | 4580, 16235 |
| gervais-original-parcels | 803 | 803 | 17 | None |
| silver-falls | 3,415 | 3,415 | 557 | 36333, 51111, 71387, 90048 |

Every source-selected record is retained and reconciled by stable OBJECTID.
Outside candidates are reported rather than silently dropped; selection is
limited to declared coverage. The **six topology repairs** retain original
geometry/fingerprints and display fingerprints, reasons, area differences and
linework checks. They can remove invalid overlapping internal edges and are
flagged in property popups. They do not establish true surveyed boundaries.
The original 803-parcel payload and original aerial bytes are unchanged; their
original extraction dates remain distinct from the repackaging date.

## Performance on the same hosted emulator

Android API 35, x86_64, Pixel 2 profile, Mesa llvmpipe software OpenGL. Three
force-stopped activity launches per APK, with OS file caches retained, followed
by identical scripted swipes. Maps use their respective default extents. These
are activity-display times, not fully loaded map latency or phone GPS performance.

| Measurement | Prototype | Expanded |
|---|---:|---:|
| Activity display, three launches (ms) | 1261, 965, 874 | 759, 1042, 873 |
| Median activity display (ms) | 965 | 873 |
| After-panning total PSS, KiB | 94,893 | 131,611 |

With both expanded parcel packages loaded, the instrumentation memory capture is
saved in `expanded-all-regions-memory.txt`: **613,805 KiB PSS
(599.4 MiB)**. This is substantially
higher than the bundled-only measurement: the bounded detail packs currently
use full GeoJSON geometry in memory. Disable unneeded parcel packs to reduce
loaded data. Larger/countywide expansion should tile parcel geometry rather
than scale this approach indefinitely. Android UI frame statistics are provided
as raw evidence, not presented as a full native-map GPU frame-rate benchmark.

## Executed checks

- **12 GIS tests passed.** Every vector tile decoded; all requested
  source batches reconciled; every parcel geometry/fingerprint and package checksum
  independently checked. Original source dates/payloads preserved.
- **All three Android instrumentation scenarios passed** in airplane mode with
  no validated network. INTERNET permission is absent from the actual APK.
- Real document-picker import from Downloads; both detailed regions installed;
  actual roads, park/water polygons, visible label pixels and visible rust trail
  pixels checked at destinations; native screenshots inspected.
- Real source polygon tap with matching owner/tax lot and Unknown access;
  parcel visibility, outside-region warning and activity recreation retained.
- Android LocationManager test-provider fix, GPS marker and recenter verified.
- Optional exact OSIP 2024 raster imported and displayed, with selectable parcels
  still available over it.
- Malformed ZIP/path/checksum rejection, preservation after failed import,
  monotonic revisions, rollback, safe deletion by exact ID and last-basemap guard.
- Android compilation, APK audit and lint passed; nonblocking lint warnings remain.

SwiftShader was found to make symbols and dashed trails invisible despite
successful feature queries, matching [MapLibre upstream #4625](https://github.com/maplibre/maplibre-native/pull/4625).
The hosted test renderer was changed to Mesa; the accepted MapLibre 12.0.1
application engine was preserved. Native pixel assertions prevent a false pass
based only on feature-query results.

**Remaining physical checks:** install Expanded alongside the prototype, grant
precise location, verify real GNSS outdoors and home coverage privately, inspect
all three destinations, and assess battery/GPU/memory on the phone. The original
phone acceptance applies to PR #3, not automatically to this expanded APK.
No private home coordinate was requested, recorded or published.
