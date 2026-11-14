# Parcel storage preview: measured results

Preview 0.3.2 replaces whole-region GeoJSON with local vector PMTiles and indexed
SQLite. The same original 803, countryside 20,966 and Silver Falls 3,415 records
were converted from checksum-pinned PR #4 extracts. No new ownership extraction
or geographic expansion was performed.

- [Download tested APK and all five packages](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43634333637/artifacts/14129164093)
- [Executed test reports, screenshots and raw memory/timing evidence](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43634333637/artifacts/14130440151)
- [Successful build/test run](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43634333637)
- [Installation, format, runtime design and county scaling](parcel-storage.md)
- [Machine-readable measured comparison](parcel-performance-results.json)
- [Unchanged expanded-v0.2 fallback](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/expanded-v0.2)

Artifacts require GitHub login and expire December 14, 2026. The APK was built
from `c886dea36a6d6a841681f4c548905cd67466f952`; later documentation/CI-only
commits do not change that binary. Its SHA-256 is
`65ba81dfab48ca6a99e6ff61037948abc3bfeac99e3d5cc258f4c6d696c21153`.

## Method and memory

The unchanged PR #4 APK (source `e61cbef`) and new APK ran consecutively on the
same GitHub-hosted API 35 x86_64 Pixel 2 emulator, Mesa software rendering with
Vulkan disabled. The identical instrumentation benchmark was compiled for both
versions. Connectivity was disabled and INTERNET permission was absent. All
three parcel archives were installed; enabled parcel sets changed per scenario.
Each scenario made 15 property selections and exercised background/resume and
activity restoration. Original, countryside and Silver made 12 camera changes
each; both expanded regions made 600, repeating positions/zooms and sampling
memory every 12 changes. Final readings follow the same settled return view.

| Enabled parcels | PR #4 PSS MiB | Optimized PSS MiB | Reduction |
|---|---:|---:|---:|
| Original Gervais | 144.0 | 117.3 | 18.6% |
| Gervais countryside | 491.5 | 122.4 | 75.1% |
| Silver Falls | 220.2 | 122.4 | 44.4% |
| Both expanded regions, extended trace | 2058.5 | 127.1 | 93.8% |

The historical PR #4 599.4 MiB result was a shorter test. It is not the baseline
used in the percentage above. The new extended trace exposed additional growth
in the old native tile reader. The new implementation meets the under-200 MiB
target in this emulator test; a physical-phone result is still required.

For both regions, final allocated Java memory decreased from 122.5 to 7.8 MiB;
native allocated memory decreased from 1914.0 to 33.0 MiB. Allocations and PSS
measure different things and should not be added together. PSS includes the
instrumentation runner. Raw dumps retain other Android memory categories.

The 50 optimized navigation samples ranged from 122.1 to 132.0 MiB PSS. First
and last were 127.2 and 125.7 MiB. Linear PSS slope over the latter half was
-1.06 MiB/min; native allocation first/last was 30.62/30.17 MiB and Java was
8.34/7.84 MiB. This trace shows bounded variation rather than sustained growth;
it does not establish absence of every possible leak or long-duration phone
behavior. The first implementation's approximately +8 MiB/min trend was fixed
before this delivery by replacing the built-in PMTiles request reader, described
in the architecture document. Both versions retain MapLibre 12.0.1 rendering.

## Interaction and startup

| Scenario | Exact lookup → UI median ms | Complete-frame median ms, old → new | Popup median ms, old → new |
|---|---:|---:|---:|
| Original | 6 | 347 → 336 | 761 → 761 |
| Countryside | 7 | 527 → 333 | 745 → 776 |
| Silver Falls | 4 | 225 → 123 | 743 → 776 |
| Both | 6 | 357 → 345 | 759 → 759 |

Popup timings include UI Automator polling/dialog overhead. Countryside and
Silver had small end-to-end regressions despite the 4–7 ms app lookup medians;
the raw metrics are retained rather than presented as a universal speedup.
Native frame callback means were 17.62/24.35, 18.81/22.72, 20.33/24.71, 27.64/29.92 FPS
(old/new). These paced navigation bursts on software rendering are not a
hardware-phone FPS guarantee.

| Scenario | Activity ready ms, old → new | First complete frame ms, old → new |
|---|---:|---:|
| Original | 1215 → 1204 | 1565 → 1541 |
| Countryside | 2864 → 874 | 3411 → 1239 |
| Silver Falls | 1369 → 831 | 1594 → 1000 |
| Both | 3012 → 1008 | 3394 → 1353 |

Those are ActivityScenario launches within each variant's instrumentation process.
Separate airplane-mode force-stopped launches measured Android `am start`
TotalTime: baseline 809/870/759 ms, optimized 624/828/845 ms, and optimized
immediately after the in-place legacy-data upgrade 717/598/741 ms. These measure
first activity display, not full map readiness; OS file caches were not flushed.

## Disk and package tradeoff

| File | New download MiB | Installed payload MiB |
|---|---:|---:|
| APK | 52.64 | — |
| Original parcels | 0.46 | 1.11 |
| Countryside parcels | 18.23 | 44.57 |
| Silver Falls parcels | 4.58 | 10.85 |
| Outdoor basemap, unchanged | 20.17 | 20.22 |
| Optional aerial, unchanged | 1.38 | 1.46 |

The old countryside and Silver downloads were approximately 8.52 and 2.06 MiB.
The new packages deliberately retain exact SQLite polygons for selection plus
separate tiled render geometry. Larger disk use buys bounded runtime decoding;
compressed size is not evidence of RAM use. Post-upgrade internal data measured
156.49 MiB, including retained package versions and legacy derived indexes,
excluding the installed APK. The archive manifests and storage report provide
exact bytes. Preserved previous versions explain storage above active payloads.

## Executed verification and limits

The successful run passed 15 GIS tests, six database/local-transport device tests,
both complete benchmark variants, and three existing Android regression tests.
Android compilation, lint, unit-test task and APK permission/content checks
also completed. The unit-test task had no separate JVM unit test cases.

Validation decoded every parcel tile and reconciled every identity, source/display
fingerprint, SQLite row and reassembled maximum-zoom polygon. Render-grid sliver
differences were explicitly reported for 41 countryside and 11 Silver parcels;
all exact components remain in SQLite. No records were silently removed.
Database tests covered holes/multipolygons/edges, owner lookup, repeated imports,
overlapping identities with moved newer geometry, rollback watermarks and corrupt
SQLite rejection even with recomputed checksums. Independent PMTiles tests covered
Hilbert addressing, real tile hashes, nested directories and canceled request
queue/cache limits. UI tests covered real DocumentsUI imports, parcel fill/outline
rendering and owner dialogs, labels, roads/trails, optional aerial, visibility,
mock GPS/recenter, airplane mode and activity restoration. Five repeated
recreations each include backgrounding, a stopped-surface memory trim and resume.
A cold recreation race in the early 0.3.1 preview was corrected by applying native
cache policy after a completed render and guarding stopped-surface trims; this
0.3.2 run verifies that correction.

Physical Android phone memory, real GNSS, Android 8 and ARM devices have not been
verified by this API 35 x86_64 run. Countywide viewport density and very many
simultaneous package-source overhead remain to be measured before expanding
coverage. The scaling plan is in the architecture document. Ownership/access
warnings and the existing personal-use data safeguards remain in force.

To reproduce, run **Offline Android APK** from the branch. Every run executes a
fresh database/transport preflight and both complete benchmark variants, followed
by functional UI tests. Temporary reuse of unchanged benchmark evidence during CI
repair has been removed. **Parcel performance report** can independently
summarize the raw evidence for run `43634333637` without rebuilding an APK.
