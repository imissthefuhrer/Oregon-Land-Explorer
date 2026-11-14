# Data-use review — 2026-11-13

This is a small personal prototype, not permission for bulk redistribution.

## OSIP 2024

Oregon GEO states OSIP imagery is available for all to use:
https://www.oregon.gov/eis/geo/Pages/statewide-data-initiatives.aspx
The state-hosted June 2025 funding strategy describes OSIP orthoimagery as released
into the public domain (Introduction):
https://www.oregon.gov/eis/geo/OGIC%20Documents/eis-geo-osip-longtermfundingstrategy-report-20250630.pdf
The 2024 service identifies statewide one-foot imagery acquired June–August 2024
by Surdex for the state, and credits Oregon Statewide Imagery Program:
https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer

These state sources support offline use of the small imagery extract. Attribution
is preserved. For larger regions prefer official bulk imagery products over
thousands of dynamic exports and review the exact product metadata first.

## Marion parcels

The working endpoint describes assessor taxlots joined with taxation/revenue
attributes; its source is retained, not silently replaced:
https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0
Its description does not establish a broad redistribution license or independently
verify this ArcGIS organization's publisher authority/currentness. Do not label it
a newly verified authoritative live assessor snapshot.

County GIS disclaimers state data are not surveys and accuracy/timeliness are not
warranted. The assessor portal also disclaims completeness and accuracy:
https://www.co.marion.or.us/Maps
https://mcasr.co.marion.or.us/
https://gis.co.marion.or.us/RoadIndex/Disclaimer.aspx

The county's published GIS policy says users must accept the applicable disclaimer:
https://www.co.marion.or.us/BOC/Policies/702-A.pdf
The first app displays approximate-boundary and unknown-access notices. No bulk
license is inferred from public HTTP availability. Before countywide packaging or
redistribution, verify this exact endpoint's publisher, data vintage, current
county GIS disclaimer and owner-field redistribution conditions with Marion County
GIS. No contact message has been sent. Only the requested small test is automated;
configuration safety limits prevent accidental countywide export.

## Expanded regional milestone — 2026-11-13

The requested expansion is two bounded personal-use regional extracts, not a
countywide ownership export. The exact existing parcel service's ArcGIS item was
reviewed before expansion:
https://www.arcgis.com/sharing/rest/content/items/bc90901732a4443bbfa1f949cc9cc205?f=pjson
It is public, owned by `ikintz_cityofstayton`, credits Marion County Assessor /
IT-GIS, and carries the county's accuracy/use disclaimer in `licenseInfo`.
Its item modification date is September 15, 2025; this is **not** proof of the
underlying tax roll vintage. No new authoritative/live-vintage claim is made.
The item metadata and layer metadata ship in each parcel package. The county
policy reviewed above applies to county departments and requires a disclaimer
for public data access; it does not itself establish a broad owner redistribution
license. No license is invented from the ability to query a service. Countywide
or general commercial redistribution remains outside this milestone; seek source
confirmation before that expansion. The user explicitly requested these bounded
personal packages, delivered in Actions artifacts; those artifacts are public,
not private storage. Owner data remain excluded from Git history.

### OpenStreetMap / Geofabrik

Official source: https://download.geofabrik.de/north-america/us/oregon.html
License and attribution: https://www.openstreetmap.org/copyright
Full data license: https://opendatacommons.org/licenses/odbl/1-0/

The derived outdoor database is offered under ODbL 1.0 with © OpenStreetMap
contributors attribution shown continuously on the map, and full source/license
information in Info and package metadata. The build source and original profile
are in the repository; the source extract timestamp, SHA-256 and official download
URL accompany the PMTiles database. Geofabrik bulk `.osm.pbf` is used; OSM's public
tile servers are never queried. This is independent of the separately sourced
parcel data. No parcel attributes are inserted into the OSM basemap database.

Tilemaker (https://github.com/systemed/tilemaker) and Planetiler
(https://github.com/onthegomap/planetiler) both support direct PMTiles output.
Tilemaker v3.2.0 is selected for a compact custom profile, requiring no global
coastline/landcover downloads or hosted map backend. Its source is built in CI.
The Lua profile and style here are original; they are not a copied OSM raster
style or an OpenMapTiles style. MapLibre Native remains BSD-2-Clause.

Local text fonts are Noto Sans Regular, distributed by the OpenMapTiles fonts
v2.0 release (https://github.com/openmaptiles/fonts/releases/tag/v2.0), under SIL
Open Font License 1.1. The font license is included in APK assets. No remote font
service is used. Uncommon character ranges beyond the bundled set may lack glyphs;
new source regions should audit their character requirements before distribution.
