# Complete private county import

**Verified November 16, 2026.** [Private complete download and real-owner evidence](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/releases/tag/marion-complete-v0.6.0)
requires the owning GitHub login. The ZIP is 266,586,721 bytes (254.24 MiB),
SHA256 `f9fd295233a6ba365dd572529ab3bcc388ff4e66a3d98a1d6addc92ef68fd8b1`.
The release server digest matches the exact bytes imported in acceptance.

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

## Executed real-data verification

[Private acceptance run 43809257900](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/runs/43809257900)
passed on a GitHub-hosted API 35 Pixel 2 x86_64 emulator with Mesa rendering:

- All 115,870 names matched the original private CSV by county/TAXLOT/TAXACCT;
  every association's original database value and base polygon SHA256 were checked.
  Zero missing keys, duplicates, owner mismatches or geometry-hash mismatches.
  Original GIS maintenance dates agreed for all 115,870 records. No county source
  records were downloaded again.
- The original owner archive was imported through Files in v0.5. Its missing
  geometry when installed alone and rejection of the outer artifact ZIP were
  reproduced. With county maps installed, all seven real owner popups passed.
- The complete ZIP was imported through the actual Files/Downloads picker in both
  fresh and upgraded v0.6.0 installations. Real owner text matched a separate
  CSV-derived reference at Salem, Gervais, Silverton, Silver Falls, Gates, Detroit
  and the eastern forest in **both** scenarios. Synthetic names were not used.
- All county geometry hashes and prior settings survived the APK upgrade.
  Disabled regions stayed disabled until the explicit enable-all action. Deleted
  download URIs did not affect installed data. Background/resume, recreation,
  a corrupt final inner package with recomputed checksums, atomic failure rollback,
  owner-version rollback and anti-downgrade checks passed.
- 600 offline pan/zoom operations with repeated real owner selection passed.
  Sampled PSS was 116.35–155.21 MiB (median 129.10); maximum sampled native
  allocations 52.99 MiB, Java allocations 36.77 MiB, descriptors 193–195.

Fresh ZIP import took 95.908 seconds; upgrade import took 89.674 seconds on that
emulator. APK size is 56,239,056 bytes (53.63 MiB); real owner archive 13,476,363
bytes (12.85 MiB), installed owner payload 32,967,879 bytes (31.44 MiB). All county
map payloads total 409,567,498 bytes (390.59 MiB). Temporary staging and retained
versions require additional space. These are instrumented emulator results,
not physical-phone memory or timing guarantees; transient import PSS was not sampled.

The full [public code regression run](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43804078043)
passed 24 GIS tests, compilation/lint and eight existing owner/GPS/aerial/picker/database
tests. Later signed runner builds changed instrumentation only and checked that
production Android sources remained identical. Final private publication
[43811014924](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/runs/43811014924)
checked the real acceptance reports and published the unchanged tested ZIP.

Test development encountered selectors for transformed/off-screen controls, an
unrelated emulator Quickstep ANR and a picker helper that backed out after ZIP
selection. Those were corrected before the successful full run. Actual data/package
failures remain visible and a bad final package was explicitly tested. Physical
phone installation, GPS and private home coverage remain the user's final acceptance.
