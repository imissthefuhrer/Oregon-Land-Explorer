# Marion County verified GIS results

Hosted GIS delivery: https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43696977024
Parcel preparation: https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43694300475
Basemap preparation: https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43695638883

All **115,870** official public-download parcel records intersect the official county polygon. The final packaged ID union reconciles exactly: **0 missing, 0 duplicate, 0 outside-county records**. All source attributes, including account identifiers and original update timestamps, reconcile to the extraction fingerprints. All county owner values are absent; the public download deliberately withholds them. Existing small owner-enabled regions remain separate and unchanged.

The official boundary reports 1,190.93 square miles. Basemap coverage is checked against that polygon at every stored zoom (8–15): **6,018 county-intersecting tiles**, **15,659 total stored tiles** including the surrounding rectangle. All tiles decode; maximum uncompressed tile size is 643,641 bytes, below the unchanged 4 MiB reader budget. Twenty-five source tiles contain no encoded layers; these are explicitly reported source emptiness, not failed downloads.

Each parcel package passed schema 3 / SQLite user_version 2 integrity, exact source identity/attribute associations, spatial-grid checks, complete maxzoom identities, and independent tile-piece reassembly including holes and multipart geometry. Polygons are assigned once and remain complete across processing partitions. Legitimate assessor gaps are not treated as unowned land or extraction failures.

## Source comparison

Hosted full inventory comparison: https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43694060470

| Observation | Verified result |
|---|---:|
| Older City of Stayton-hosted mirror records | 115,385 |
| County MapServer records | 115,870 |
| Official ownerless Open Data View records | 115,870 |
| Shared mirror/current taxlot-account pairs | 115,218 |
| Present only in current county/open data | 652 |
| Present only in older mirror | 167 |
| Shared pairs with different OBJECTIDs | 115,209 |
| Shared mirror/current pairs with changed acreage | 285 |
| Duplicate or missing taxlot/account identities in each source | 0 |

The county and Open Data View inventories match after a TAXLOT/TAXACCT join, including OBJECTIDs and acreage in this observation. That is an observed comparison, not an assumption that services share an ID namespace. Representative mirror geometries differ by approximately 0.000015 degrees from the current source; differences can include projection/vintage effects and do not establish physical boundary movement. County/Open Data View representative differences were zero or near numerical precision.

Extended extent/date audit: https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43701539925 .
The current county/Open Data View query extent in WGS84 is
`[-123.17979456, 44.68383668, -121.73311984, 45.28448735]`.
The mirror's transformed extent envelope is
`[-123.20036475, 44.65980101, -121.72880236, 45.30429850]`.
Its published extent uses EPSG:2913; the county service uses EPSG:3857.
Transformed envelopes are conservative summaries and do not prove actual polygon
coverage. Completeness uses the official polygon and exact source ID reconciliation.

Original `LASTUPDATE` values have no nulls in the observed inventories. Mirror
record dates range from 2025-02-16 to 2025-08-13; current county/Open Data View
record dates range from 2025-02-16 to 2026-08-28. The mirror service edit stamp is
2025-09-15, and the Open Data View edit stamp is 2026-11-09. The county MapServer
does not publish an equivalent layer edit stamp. These are distinct observations,
not proof of a current tax roll or owner-data vintage.

Countywide owner production is blocked pending written permission. The user confirmed no permission on 2026-11-15. The official catalog says ownership information is removed and requires a written request: https://www.arcgis.com/home/item.html?id=9e496e9558ae476e9b65e1cf51b2594a . No owner values or addresses were requested for the county public packages or source inventory comparisons.

## Storage

All county downloads: **187.74 MiB**. Expanded county payloads: **359.15 MiB**. These figures exclude the APK, existing installed maps, SDK caches, browser downloads, staging and retained update versions.

The county basemap is 40,511,302 bytes (38.63 MiB), versus the v0.3.2 corridor basemap's 21,148,329 bytes (20.17 MiB). Parcel downloads total 156,347,271 bytes (149.10 MiB); installed parcel payloads total 335,650,958 bytes (320.10 MiB). The verified v0.3.2 APK was 55,194,919 bytes (52.64 MiB).

| Package | Parcels | Download MiB | Installed MiB |
|---|---:|---:|---:|
| marion-salem-west11 | 306 | 0.61 | 1.40 |
| marion-salem-west121 | 11,760 | 16.36 | 37.80 |
| marion-salem-west1221 | 3,220 | 4.45 | 10.01 |
| marion-salem-west1222 | 11,181 | 12.17 | 25.43 |
| marion-salem-west221 | 8,993 | 9.09 | 18.25 |
| marion-salem-west222 | 9,481 | 11.39 | 24.79 |
| marion-salem-east1 | 9,708 | 12.60 | 27.35 |
| marion-salem-east211 | 10,149 | 11.48 | 24.55 |
| marion-salem-east212 | 5,958 | 6.85 | 14.48 |
| marion-salem-east22 | 713 | 1.00 | 1.94 |
| marion-gervais | 11,708 | 16.09 | 34.30 |
| marion-silverton | 11,485 | 15.20 | 32.69 |
| marion-silver-falls | 7,942 | 12.76 | 27.78 |
| marion-santiam-valley | 11,171 | 15.23 | 31.73 |
| marion-gates | 1,469 | 2.45 | 4.81 |
| marion-detroit | 626 | 1.36 | 2.79 |

Full partition polygons, complete-geometry query bounds, checksums and source dates are in the downloadable `county-catalog.json`. Geographic seeds and the 12,000-record preparation cap are configurable; the existing Android file, geometry, decode and package safety limits were not increased.

## Basemap source and known limits

Actual bulk URL: https://download.openstreetmap.fr/extracts/north-america/us-west.osm.pbf
Bulk SHA256: `b8ab3c7ca0bd9d28959b8a8be83d3d54a7055f0f01f4a525abfc142b84604129`

Geofabrik routing failed repeatedly from GitHub runners. The successful build explicitly used the official OpenStreetMap France US West bulk data, then clipped the county rectangle with Osmium and used the unchanged Tilemaker v3.2.0 outdoor profile. Attribution and ODbL 1.0 are retained. No public OSM tile servers or countywide aerial exports were used. Local Noto fonts and the MapLibre 12.0.1 transport remain unchanged.

Roads, tracks, paths, water, woods, parks, buildings and labels are present. The previous expected-name/variant audits for Willamette Mission and Silver Falls returned no unmatched names in this source. These are name audits, not an independent census of real-world roads/trails, current closures, or lawful access. No specific missing road/trail geometry was established; OSM omissions remain possible.

Actual parcel selection fixtures cover Salem, Gervais, Silverton, Silver Falls, Gates, Detroit and eastern forest. They use source polygons at or near public place references, never the user’s private home coordinates. The Android test also selects a real record from every package.

## Android verification

The separate corridor regression build passed compilation, lint, 20 GIS unit checks, six database/transport tests, the existing paired extended-navigation benchmark, and three UI regression tests. Its extended both-region result was 129.6 MiB PSS. The previous v0.3.2 handoff reported 127.1 MiB. Regression run: https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43694304777 .

Final all-county measurements are pending the dedicated County Android verification run. The original v0.3.2 APK is archived unchanged under `verified-v0.3.2`; Android blocks ordinary APK downgrades and v0.3.2 cannot read the new namespaced county parcel databases. Do not uninstall an app containing your only installed data to attempt a rollback.
