# Private offline Marion County owners

Owner identification is the purpose of this work. The public v0.4 county geometry
contains 115,870 parcels but does **not** supply countywide names. This change adds
a private attribute package; it does not claim to have acquired that missing dataset.
The original owner-enabled regions and all county PMTiles/SQLite remain intact.

## Acquisition findings — reviewed November 15, 2026

The [Stayton-hosted FeatureServer](https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0)
does expose OWNERNAME, TAXLOT, TAXACCT, ACRES and LASTUPDATE. Its
[ArcGIS item](https://www.arcgis.com/home/item.html?id=bc90901732a4443bbfa1f949cc9cc205)
credits Marion County assessor/GIS; publisher is `ikintz_cityofstayton`.
The published license text is an accuracy/use disclaimer, not an explicit grant
for countywide private bulk acquisition. Public API accessibility alone does not
resolve that question. No new countywide OWNERNAME extraction was performed.
No explicit ban on personal offline use was found in that mirror item's terms
either. The uncertainty is the authorized countywide acquisition method and
conditions, rather than a finding that private owner identification is forbidden.

The official [county parcel service](https://gis.co.marion.or.us/arcgis/rest/services/Public/Parcels/MapServer/0)
also exposes OWNERNAME. The sanctioned
[Open Data View](https://www.arcgis.com/home/item.html?id=9e496e9558ae476e9b65e1cf51b2594a)
has removed ownership and says a written request is required for that information.
The [assessor download page](https://www.co.marion.or.us/AO/Pages/datacenter.aspx)
says owner names and mailing addresses have been absent from its comprehensive
download since February 2015. This establishes a request route; it does **not**
establish that authorized private offline use is impossible.

The [county website disclaimer](https://www.co.marion.or.us/Pages/disclaimer.aspx)
allows reviewing/retrieving publicly available information and disclaims accuracy;
it does not specifically settle countywide owner acquisition. The
[official data request form](https://apps.co.marion.or.us/AssessorDataRequest/Default.aspx)
lists $65/hour with a one-hour minimum. No request or paid order has been submitted.
Ask County GIS/Assessor which authorized export or bulk service applies and what
private storage/hosted processing conditions attach. Public redistribution rights
are a separate issue and are not needed for this private design.

Previous complete **non-owner** inventory comparison found 115,385 mirror records
versus 115,870 current county/Open Data records; 115,218 stable pairs overlap,
115,209 of those have different OBJECTIDs, 652 are current-only, 167 mirror-only,
and 285 shared pairs differ in acreage. Mirror record updates end August 2025;
current records extend to August 2026. The older mirror is neither complete nor
automatically current. See PR #7's `county-source-inventory-comparison.json` evidence.

## Exactly what activates production

Provide either an authorized county/provider export **or** explicit confirmation
of a permitted countywide personal-use API acquisition method, plus the actual
dataset/export. Confirmation should cover private offline Android storage and
private GitHub-hosted processing. Nothing here requests permission to publish names.
Preserve the response/agreement privately; reference it in `authorization.json`.

Export columns (case-sensitive):

| Field | Requirement |
|---|---|
| TAXLOT | Exact text identifier, preserving leading zeros |
| TAXACCT | Exact text account identifier, preserving leading zeros |
| OWNERNAME | Text or explicit null/empty if withheld/unavailable |
| COUNTY | `41047`, or set `county` in authorization metadata |
| OWNER_AS_OF | Optional ISO date/UTC timestamp; otherwise documented snapshot date |
| ACRES | Optional source acreage; differences block assigning the name |
| SOURCE_ID | Optional audit value, never a join key |

A provider's differently named fields must be mapped explicitly to this schema;
do not infer identifiers from OBJECTID. CSV uses UTF-8. GeoJSON FeatureCollection
properties and GeoPackage/SQLite tables are also supported. Time-bearing dates
must have an explicit timezone; date-only values mean midnight UTC.

Example `authorization.json` (replace every placeholder with documented facts):

```json
{
  "county": "41047",
  "source": "County-provided export / actual dataset reference",
  "data_as_of": "2026-11-01T00:00:00Z",
  "authorization_reference": "Private copy of actual county response or agreement",
  "private_offline_use_authorized": true,
  "acquisition_method": "county_export"
}
```

Other accepted acquisition methods are `authorized_provider_export` and
`explicit_api_permission`. The latter accepts an export acquired using that
documented method; this workflow does not itself harvest an API. An assertion in
JSON cannot create rights. Do not set the authorization flag while permission is pending.

Suggested request text, for the user to submit after reviewing the official form:

> I request a current Marion County parcel ownership export for my personal,
> noncommercial offline Android property-identification app. Please include TAXLOT,
> TAXACCT, OWNERNAME, source/snapshot date, and optionally ACRES and per-record update
> dates, with null/withheld names identified. I will not publicly distribute the
> ownership database. Please confirm permitted private offline storage and private
> GitHub Actions processing, any retention/use restrictions and the acquisition
> method/fees. If an existing ArcGIS service is authorized for countywide personal
> extraction, please identify that service and its allowed query method/rate.

## Browser-only private preparation and Android import

Use the user's **private** data repository, never this public repository's releases,
issues, pull request attachments or artifacts for production owner data.

1. In that private repository's Releases page, create a private release such as
   `owners-input-2026-11`. Upload `owners.csv` (or `owners.geojson`, `owners.gpkg`,
   `owners.sqlite`) and `authorization.json` as release attachments. Ownership input
   does not need to enter Git history. Only upload it after authorization is established.
2. Open Actions → **Prepare private offline ownership export** → Run workflow.
   Select the private input tag, export filename/table and increasing package
   revision. It verifies the repository is private, validates authorization metadata,
   downloads the unchanged public county base packages and builds the overlay.
3. Review `owner-validation.json`, the per-parcel association audit and unmatched
   source keys in the **private-marion-ownership** artifact. Conflict/stale/null/missing
   counts are explicit; pipeline integrity passing does not mean all owners exist.
4. On the phone, use a signed-in browser to download that private artifact. Android
   Files can extract its ZIP. Install the updated signed APK over the existing app;
   do not uninstall or clear storage. Keep the existing county map packages installed.
5. Region → Import .olepack from Downloads → choose `marion-private-owners.olepack`.
   Use the ordinary package manager to enable/disable, update, roll back or remove
   ownership without changing any parcel region. County coverage lists names and
   source date separately from boundary coverage.
6. Enable airplane mode. Tap a parcel: the app reads local exact geometry and an
   indexed local owner row, displaying owner, tax lot, account, acreage, provenance
   and source vintage. No Internet permission, HTTP server or runtime network is used.

The raw export is converted in private hosted CI; the phone imports the resulting
`.olepack`, not a raw CSV. Private artifacts expire after seven days; retain your
authorized input/output privately as its terms permit. GitHub access controls protect
downloads; this is not additional database encryption on an unlocked phone.
The prepared private repository is
[Oregon-Land-Explorer-Private-Data](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data).
Its optional `synthetic_smoke_test` mode generates visibly invented names and
requires no ownership input. Keep that option off when preparing authorized data.

## Matching, updates and integrity

Identity is `(41047, exact trimmed TAXLOT, exact trimmed TAXACCT)`. No fuzzy matching,
tax-lot-only fallback, or cross-service OBJECTID join is allowed. Every installed
county base parcel has one status row, including records with no source match.
Identical duplicates are counted; distinct rows with the same identity conflict and
never assign an owner. Unmatched export keys are reported separately. Names are
as-of statements; successful matching cannot independently prove a sale has not
occurred after the source snapshot.
Reports retain source snapshot age. LASTUPDATE is the GIS maintenance date;
it does not independently prove the date of the last ownership transfer.

An ownership row binds to SHA256 of the exact base polygon. Changed installed
geometry or a parcel LASTUPDATE newer than the owner record blocks presenting the
name as validated. Older names may appear separately as historical/unverified.
Null/conflicting newest data never falls back to an older package's name. Packages
use checksums, deep SQLite validation, atomic activation, increasing revision/source
watermarks and the existing rollback mechanism. Removing an overlay leaves all
115,870 boundaries intact. Geometry remains schema 3 PMTiles/SQLite; attribute-only
ownership is schema 2 kind `owners` with an indexed `owners.sqlite` database.

Only a selected parcel is decoded. Owner lookup uses a short-lived read-only SQLite
connection with a 1 MiB cache and mmap disabled. Installing county owners adds no
MapLibre sources or polygon collections. Existing 384 MiB per-payload / 512 MiB
package limits are unchanged.

Public CI uses unmistakable `SYNTHETIC TEST ONLY` names for all 115,870 actual parcel
keys. Those names exist only in instrumentation fixtures; they are not bundled in
the phone APK. Original permitted owner regions are reused for actual-name regression
tests. No test can establish the correctness of a still-unavailable production export.
