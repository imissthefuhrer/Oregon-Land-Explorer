# Complete private county import

The v0.6 Android importer accepts one complete county ZIP containing the signed
APK, outdoor basemap, all 16 Marion County parcel regions and private ownership.
Real owner data and the finished owner-inclusive ZIP belong exclusively in the
user's private repository. Public builds contain code and the unchanged original
core maps; real countywide records are supplied only to private acceptance runs.

## Android installation

Download the verified ZIP from the private repository while signed into GitHub
in the phone's browser. Extract it in Files and install its APK **over** the
existing expanded app. Do not uninstall or clear storage. The stable application
ID and persistent signing key preserve installed maps and settings.

Open the updated app, choose Region → Import complete county ZIP, and select the
original downloaded ZIP in Downloads. Scroll the region menu if needed. Long-press
the ZIP and choose Open if Files tries to browse its contents. This is one map
import; no manual selection of the 18 individual map archives is required.

Wait for checking/installing 1/18 through 18/18. Existing region enable/disable
choices are retained. Region → Enable all county maps activates installed county
coverage in one action while leaving other installed packages/settings intact.
County coverage should show 16/16 parcel regions enabled, the county basemap ready
and 115870 owner records enabled. Then turn on airplane mode and tap properties
at zoom 14 or closer. Original optional aerial imagery remains available.

## Why an owner file alone was insufficient

An owner overlay supplies attributes, not county boundaries. A fresh v0.5 app
with only that file still has none of the 16 county parcel regions. Its original
map uses a different source identity; joining unrelated OBJECTIDs or guessing
accounts from tax lots would be unsafe. v0.5 also rejects the outer GitHub artifact
ZIP, whose nested `.olepack` needs extraction.

Private diagnosis imported the original **real** owner archive through Android
Files in v0.5 and displayed matching real source owners at seven county locations
after installing the required county geometry. The complete ZIP addresses those
reproduced delivery pitfalls. It does not establish exactly what occurred on the
physical phone; that remains user acceptance.

## Integrity, provenance and updates

ZIP entries and sizes are bounded, nested files are SHA256-checked, and all 18
packages undergo the existing database/PMTiles validation before a single atomic
registry commit. Failed complete imports retain the old active registry and
remove only new uncommitted directories. Existing versions support rollback.
The existing per-package limits and disk-backed PMTiles/SQLite design remain.

Ownership schema `oregon-owners-v2` uses the same exact county/TAXLOT/TAXACCT
identity and polygon hashes. It separates `retrieved_utc` from an unknown source
ownership snapshot. SQLite `owner_as_of` is NULL when no independent snapshot is
known. Original source LASTUPDATE is retained as GIS maintenance, not a transfer
or ownership-as-of date. A fingerprint-checked revision upgrade corrects the old
package's provenance using the same source input and retains its prior version.
No affirmative bulk-use or redistribution permission is invented.

The private test runner uses a CSV-derived independent reference database and
actual Files/Downloads imports, touch selection and owner text comparisons. No
real dataset is compiled into the public instrumentation APK. Acceptance requires
real-name matches in fresh and in-place upgrade scenarios, airplane mode, repeated
navigation, source-URI removal, corruption rejection and rollback. Screenshots
containing real names stay private. No countywide ownership extraction is rerun.

The app has no Internet permission or in-app server; installed data stays local.
An ownership record or mapped road/trail does not establish public access.
