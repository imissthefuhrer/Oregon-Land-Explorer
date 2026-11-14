# Parcel storage preview: measured results

Preview 0.3.1 replaces whole-region GeoJSON with local vector PMTiles and indexed
SQLite. The same original 803, countryside 20,966 and Silver Falls 3,415 records
were converted from checksum-pinned PR #4 extracts. No new ownership extraction
or geographic expansion was performed.

- [Download tested APK and all five packages](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43630083953/artifacts/14128241895)
- [Executed test reports, screenshots and raw memory/timing evidence](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43630083953/artifacts/14128981332)
- [Successful build/test run](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43630083953)
- [Installation, format, runtime design and county scaling](parcel-storage.md)
- [Machine-readable measured comparison](parcel-performance-results.json)
- [Unchanged expanded-v0.2 fallback](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/expanded-v0.2)

Artifacts require GitHub login and expire December 14, 2026. The APK was built
from `bcdaa8d0c8b818dede9f618cf3dfaaf52a19fd24`; later documentation/CI-only
commits do not change that binary. Its SHA-256 is
`91600f64fb9ca7b1e61cbd4da2aefaa861be59156465c386dfbd78cc6e0d655f`.

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
| Original Gervais | 137.5 | 121.6 | 11.6% |
| Gervais countryside | 482.1 | 121.0 | 74.9% |
| Silver Falls | 214.8 | 119.7 | 44.3% |
| Both expanded regions, extended trace | 2061.3 | 129.6 | 93.7% |

The historical PR #4 599.4 MiB result was a shorter test. It is not the baseline
used in the percentage above. The new extended trace exposed additional growth
in the old native tile reader. The new implementation meets the under-200 MiB
target in this emulator test; a physical-phone result is still required.

For both regions, final allocated Java memory decreased from 123.4 to 7.5 MiB;
native allocated memory decreased from 1913.5 to 32.2 MiB. Allocations and PSS
measure different things and should not be added together. PSS includes the
instrumentation runner. Raw dumps retain other Android memory categories.

The 50 optimized navigation samples ranged from 123.1 to 131.7 MiB PSS. First
and last were 126.1 and 128.0 MiB. Linear PSS slope over the latter half was
−1.43 MiB/min; native allocation first/last was 29.76/29.39 MiB and Java was
8.19/7.43 MiB. This trace shows bounded variation rather than sustained growth;
it does not establish absence of every possible leak or long-duration phone
behavior. The first implementation's approximately +8 MiB/min trend was fixed
before this delivery by replacing the built-in PMTiles request reader, described
in the architecture document. Both versions retain MapLibre 12.0.1 rendering.

## Interaction and startup

| Scenario | Exact lookup → UI median ms | Complete-frame median ms, old → new | Popup median ms, old → new |
|---|---:|---:|---:|
| Original | 5 | 481 → 415 | 851 → 811 |
| Countryside | 6 | 413 → 168 | 854 → 863 |
| Silver Falls | 4 | 694 → 120 | 810 → 846 |
| Both | 5 | 406 → 168 | 824 → 902 |

Popup timings include UI Automator polling/dialog overhead. They show a small
end-to-end regression in three scenarios, despite the 4–6 ms app lookup median;
the raw metric is retained rather than presented as a universal speedup.
Native frame callback means were 16.89/20.32, 15.92/13.27, 15.63/19.96 and
23.40/26.11 FPS (old/new). Countryside's mean decreased. These paced navigation
bursts on software rendering are not a hardware-phone FPS guarantee.

| Scenario | Activity ready ms, old → new | First complete frame ms, old → new |
|---|---:|---:|
| Original | 1543 → 1408 | 2033 → 1893 |
| Countryside | 2746 → 1020 | 3162 → 1200 |
| Silver Falls | 1532 → 1226 | 2226 → 1346 |
| Both | 3156 → 1067 | 3566 → 1266 |

Those are ActivityScenario launches within each variant's instrumentation process.
Separate airplane-mode force-stopped launches measured Android `am start`
TotalTime: baseline 1023/1155/847 ms, optimized 701/932/933 ms, and optimized
immediately after the in-place legacy-data upgrade 951/843/765 ms. These measure
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
160.56 MiB, including retained package versions and legacy derived indexes,
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
mock GPS/recenter, airplane mode and activity restoration.

Physical Android phone memory, real GNSS, Android 8 and ARM devices have not been
verified by this API 35 x86_64 run. Countywide viewport density and very many
simultaneous package-source overhead remain to be measured before expanding
coverage. The scaling plan is in the architecture document. Ownership/access
warnings and the existing personal-use data safeguards remain in force.

To reproduce, run **Offline Android APK** from the branch. Its guarded reuse step
may retain the explicitly linked executed paired measurements only when app,
database/transport tests, GIS and benchmark sources match the tested commit;
functional UI tests still run. Any production or benchmark source change executes
a new pair. **Parcel performance report** can independently summarize the raw
evidence for run `43630083953` without rebuilding an APK.
