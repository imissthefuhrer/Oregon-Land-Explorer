# Marion County expansion

This branch starts at PR #6 handoff `a338e58`, preserving the phone-tested v0.3.2
disk-backed implementation. The unchanged signed fallback is archived at
https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/verified-v0.3.2
with APK SHA256 `65ba81dfab48ca6a99e6ff61037948abc3bfeac99e3d5cc258f4c6d696c21153`.
The application ID remains `org.oregonlandexplorer.expanded` and the existing
Actions signing secrets remain the signing identity. Install updates over the
existing app; uninstalling removes private installed data.

## Data and rights

The county's official open-data catalog explicitly removes ownership information
and requires a written request for it:
https://www.arcgis.com/home/item.html?id=9e496e9558ae476e9b65e1cf51b2594a
The catalog links to the `Parcels_OpenDataView` Query/Extract service. County
public packages use only OBJECTID, TAXLOT, TAXACCT, ACRES, LASTUPDATE and geometry
from that view. They contain no owner names, mailing addresses, situs addresses,
valuation fields or ownership classifications. The county's published disclaimer
is retained in every package. These approximate assessor polygons are not surveys
and must not be used for precision navigation, measurement or establishing access.

The user confirmed on 2026-11-15 that written county owner-data permission has not
been obtained. **Countywide owner production and publication are blocked.** The
existing approved small owner-enabled packages are preserved, including their
original identities and source dates. The newer county polygons do not inherit
old owner attributes: source namespaces and snapshots remain distinct. Where
overlapping source versions exist, the app offers separate source records.

The two requested owner-capable services are inventoried and compared using
non-owner attributes and geometries at representative places. Their OBJECTIDs
are never joined. Metadata update dates are distinguished from LASTUPDATE values
on source records. Public query availability is not a countywide owner license.
No request to County GIS has been sent.

The bulk OSM outdoor basemap is independent of parcels. It is available under
ODbL 1.0, with © OpenStreetMap contributors attribution and Geofabrik source
timestamp/hash. No OSM public tile servers or countywide aerial exports are used.

Geofabrik direct connections failed repeatedly from GitHub runners during this
milestone. The hosted workflow also offers the explicitly selected official
OpenStreetMap France US West bulk extract:
https://download.openstreetmap.fr/extracts/north-america/us-west.osm.pbf
This uses the same ODbL data model, Osmium clipping, Tilemaker profile and offline
style. It is a larger preparation download, not a larger area installed on the
phone. No provider is silently substituted: the selected URL, source timestamp,
checksum and provider attribution accompany the resulting county package.

## Hosted preparation

Run **Full Marion County GIS** on this branch in Actions. No desktop GIS or
Android SDK installation is needed. The workflow has independent extraction and
Tilemaker v3.2.0 basemap jobs; six hosted shards build the parcel packages.

Extraction inventories the complete selected source, downloads 500-ID batches
using supported ArcGIS form queries, and checkpoints successful responses by the
source ID/edit-metadata snapshot. Each request has five bounded attempts. Batches
must return exactly the requested unique IDs. A changed source inventory or edit
stamp aborts packaging. Checkpoints are saved even after failure; rerunning
resumes the matching snapshot. Source timestamps and full retained-attribute
fingerprints are recorded. No retry substitutes a different source.

The official county polygon defines inclusion. Complete source polygons
intersecting it are assigned once to geographic seed regions, with recursive
geographic splits for urban density above 12,000 records. Polygons, holes and
multipart pieces remain intact; package seams never create property boundaries.
The app's query bounds contain the entire assigned geometry, including parts
crossing a processing partition. Catalog partition polygons and query bounds are
separate. Installing only one package still permits selection of all its complete
polygons. Installing all packages covers the complete extracted source ID set.

New identity keys use `41047:marion-open:<sha256>` derived from compact JSON
`[TAXLOT,TAXACCT]`. Missing/conflicting stable IDs abort rather than attaching
attributes to a guessed parcel. OBJECTID remains only the source-local SQLite
row ID, spatial-grid join and audit lookup. Legacy keys `41047:OBJECTID` remain
unchanged. A private authorized owner source has a different namespace.

The verified schema 3 / SQLite user_version 2 layout and existing package safety
limits remain unchanged. Every tile is independently decoded and checked against
exact SQLite identities; maximum-zoom tile pieces are reassembled to check holes,
multipart geometry and the existing render-grid error budget. Original invalid
geometry and repair evidence are retained under the existing no-invented-boundary
policy. Any exceeded safety budget is a failure requiring a smaller geographic
package or an explicitly reviewed source defect, not a raised limit.

The final delivery job requires every catalog package, verifies hashes and
counts, and generates `county-catalog.json`, `county-reconciliation.json`,
`county-storage.json`, `SHA256SUMS.txt` and installation instructions. A failed
shard cannot produce a delivery falsely labelled complete. Gaps genuinely absent
from the assessor data are distinguished from missing downloaded records; parcel
coverage never establishes ownership or legal public access.

## Phone installation

Keep v0.3.2 installed. Install the updated signed APK over it. Download
`marion-outdoors.olepack` plus the desired parcel files using the phone browser.
In **Region → Import .olepack from Downloads**, select a file, or long-press and
select several files. Packages install independently with validation, checksums,
revision checks, atomic activation and retained rollback versions. A rejected
file preserves its previously installed data. Imports need temporary room for
staging and the previous version as well as final payload storage.

**Region → County coverage** distinguishes the county basemap, enabled parcel
packages, disabled packages and missing packages. Owner-withheld is explicit in
the parcel popup. **Manage installed packages** supports enabling, disabling,
rollback and removal of additional regions. The original core packages remain.

The county basemap is a separate download. When installed and enabled it replaces
rendering of any fully covered older corridor basemap without deleting that data
or changing enable settings. Optional original Gervais OSIP 2024 aerial remains
independent. After import, enable airplane mode and verify home coverage privately
with GPS. No home coordinates enter the catalog or repository.

## Authorized private owner workflow

Do not run countywide owner extraction or upload it in this public repository.
After obtaining written permission for the intended private offline use, use a
private copy of the pipeline on a hosted runner. Set `OLE_PRIVATE_AUTHORIZED=true`
and `OLE_AUTHORIZATION_REFERENCE` to the written agreement reference, then run
`python -m pipeline.county extract --owners` and the same packaging/validation
commands in that private environment. The public workflow has no owners input
and never sets these variables. An authorization reference does not grant rights
that the underlying agreement lacks. Keep production artifacts private and retain
the source's disclaimer and permission scope. Do not put permission documents,
credentials or owner records into public commits, caches or Actions logs.

## Verification status

Hosted results and measurements will be recorded after successful builds. Earlier
v0.3.2 results are a comparison baseline, not proof of countywide performance.
Physical-phone results for this new build must not be inferred from emulator tests.
