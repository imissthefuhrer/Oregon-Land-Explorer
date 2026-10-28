# Oregon 2024 aerial basemap diagnosis

Status on 2026-10-28: **verified on a GitHub-hosted runner**. Metadata and
three nonblank sample tiles returned HTTP 200. Chromium decoded 20 aerial tiles,
reported zero JavaScript/network errors, and passed parcel interactions. The
rendered screenshot was visually inspected: imagery is visible, tile boundaries
are seamless, and parcel polygons follow the mapped fields and river bend.

[Successful live run and downloadable HTML/diagnostics](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/40737445224)
(source commit `979200c5fb3235bab55dbfd504c98d928b3e8d34`).

## Findings

The fix is in the existing `.github/workflows/parcel-sample.yml`, so future parcel
artifacts include the aerial integration automatically. The older uploaded
`parcel-sample (1).yml` now delegates to that workflow to avoid diverging previews.
The standalone `Oregon_Land_Explorer_2024_Aerial.html` was not available to inspect;
the repository's uploaded Esri Leaflet 3.0.19 implementation provided a reproducible
reference for diagnosing its export requests.

The [official ImageServer metadata](https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer?f=pjson)
was successfully read through the web connector. Its inspected grid is recorded in
`osip-2024-tile-scheme.json`:

- Projection: EPSG:3857 (WKID 102100).
- Cache: `singleFusedMapCache: true`, 256 × 256 tiles, MIXED image format.
- Origin: **x = -20037700, y = 30241100** in projected meters.
- LODs: 0–21, with published resolutions that differ slightly from standard XYZ.
- Advertised visible scale range: levels 1–19 (`minScale: 295828764`, `maxScale: 1128`).

`L.esri.imageMapLayer` requests `exportImage`; it does not consume this tile cache.
Caching alone does not make dynamic image export inappropriate: the service also
advertises the Image capability. However, the pinned Esri Leaflet 3.0.19 generated
this reversed export bbox in Chromium:

```text
xmin=-13691371.842555165
ymin=5667108.159462717
xmax=-13685639.065433776
ymax=5663286.308048458
```

Here `ymin > ymax`. The pinned version's
[`RasterLayer._calculateBbox` source](https://cdn.jsdelivr.net/npm/esri-leaflet@3.0.19/src/Layers/RasterLayer.js)
uses pixel-style `Bounds.getBottomLeft()`/`getTopRight()` on projected Cartesian
coordinates, producing that reversed Y order. **Live testing shows this server
normalizes the bbox:** both the reversed and correctly ordered requests returned
HTTP 200 JSON responses, and both referenced images returned HTTP 200 and decoded
as nonblank 512 × 512 JPEGs. The returned extents and image statistics were equal.
Therefore the reversal is not established as the cause of the blank map, and
`imageMapLayer` is compatible with the tested service. Cached tiles are a working
choice here, not a requirement imposed by lack of dynamic export support.

The reproducible failures in this Codex environment were access failures: the
original library CDN was denied, causing `L is not defined`, and the imagery host
was denied at the proxy. The unavailable standalone HTML prevents attributing its
exact failure to one code path. The service itself responds correctly from the
GitHub-hosted runner. Do not label it broken based on the local proxy failures.

The fix chooses the published cached tiles, using its prebuilt images directly. A plain
Leaflet `{z}/{y}/{x}` URL on the default CRS would also be wrong: EPSG:3857 does not
guarantee a standard tile matrix. The map retains the EPSG:3857 projection, but uses
the service origin in its pixel transformation and exact LOD resolutions for scale.
Fractional zoom and inverse scale interpolate between the published resolutions.
GeoJSON remains in longitude/latitude and the parcel rendering/popup code is kept.
This CRS is specific to this cache; a future additional basemap must use a compatible
grid or be adapted separately.

For projected point `(x, y)` at level `z`, expected addresses are:

```text
column = floor((x - origin.x) / (256 * resolution[z]))
row    = floor((origin.y - y) / (256 * resolution[z]))
URL    = ImageServer/tile/z/row/column
```

The preview embeds Leaflet 1.9.4 and its MIT license, avoiding a runtime CDN
dependency when opening the HTML. Aerial tiles still require internet access.
There are no OpenStreetMap tile requests. Tile errors, a loading timeout, a failed
request link, and retry are visible without removing parcels or ownership popups.

## Verification performed

| Check | Observed result |
| --- | --- |
| Official metadata through web connector | Retrieved actual tileInfo and spatial reference |
| Real parcel query | HTTP 200; 10 polygons; all 10 owner fields populated |
| Original preview in Chromium | `unpkg.com` denied; `L is not defined` |
| Original with exact Leaflet/Esri assets supplied by test harness | Reversed bbox observed; metadata/export requests fail with `net::ERR_TUNNEL_CONNECTION_FAILED` |
| Fixed preview in Chromium | Zero JavaScript errors; 10 rendered parcel paths |
| Parcel preservation | Full GeoJSON equals the original generator's result |
| Popups | All 10 tax-lot, acreage and owner values checked; an actual pointer click opens the first parcel |
| Tile grid | Independent projection/formula checks at levels 1, 10, 13, 16, 18, 19; scale inversion checked at fractional zoom |
| Actual generated tile URLs | Correct `/tile/level/row/column` order, including the nonstandard rows |
| Failure/retry | Visible error, request link and retry; retry issues fresh tile requests |
| Live metadata | HTTP 200; exact grid matches the inspected snapshot |
| Live sample tiles at levels 13, 15, 19 | Each HTTP 200 `image/jpeg`; 256 × 256; nonuniform pixel values |
| Original-reversed and corrected export requests | Both HTTP 200 JSON; referenced images HTTP 200, nonblank 512 × 512 JPEGs |
| Live Chromium imagery rendering | **PASS: 20 decoded tiles; zero JavaScript/network errors** |
| Rendered screenshot | Visually reviewed with aerial imagery and aligned parcel boundaries |

Local HTTPS requests through the managed proxy receive **HTTP 403 at CONNECT**
for `imagery.oregonexplorer.info`, which is absent from its network allowlist.
That 403 comes from the environment, not from ArcGIS. The browser likewise fails
the tunnel before receiving an imagery HTTP response. No CORS conclusion can be
drawn from this failure. TLS verification has not been disabled.

## Repeat verification

No Codex environment changes are required to use the GitHub-hosted checks.
Example tile requested by this parcel view:

```text
https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer/tile/15/20094/5191
```

Run **Marion County Parcel Sample** and download its `marion-parcel-sample` artifact.
Open `map.html` directly. Its GeoJSON and Leaflet code are embedded; only aerial
requests need internet access.

**OSIP Aerial Imagery Live Checks** runs automatically on relevant pull requests
and supports manual dispatch after it is on the default branch. GitHub-hosted
runners build the same preview through the existing parcel workflow, compare live
metadata to the inspected grid, request tiles at three levels, report HTTP status
codes and ArcGIS/network/decode errors, and probe both reversed and corrected
export bounding boxes. Pillow verifies image dimensions and detects uniform tiles.
Chromium verifies real image responses, parcel popups and a pointer click. The run
summary reports results; the `osip-aerial-diagnostics` artifact includes HTML,
GeoJSON, service responses, decoded tile samples, browser network logs and a
screenshot. Export probes are informational because the preview uses cached tiles.
A failed service check does not skip browser diagnostics or artifact upload.
The preview artifact contains `map.html` and `parcels.geojson`; the diagnostics
artifact includes the screenshot as `work/aerial-preview.png`.

For repeatable Chromium checks:

```sh
npm install --no-save --package-lock=false playwright
npx playwright install chromium
node scripts/verify-aerial.cjs path/to/map.html path/to/parcels.geojson
```

Use `CHROMIUM_EXECUTABLE=/usr/bin/chromium` when Chromium is already installed.
The verifier writes diagnostics and a screenshot under `work/` and exits with a
failure unless an actual HTTP 200 image response is decoded by the browser. It still fails in the locally restricted Codex environment; it passed on the
GitHub-hosted runner. No synthetic imagery was used to claim success.
