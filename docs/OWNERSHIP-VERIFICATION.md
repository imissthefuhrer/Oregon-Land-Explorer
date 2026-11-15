# Private offline owners — executed verification

November 15, 2026. The ownership feature is implemented, but a newly authorized
countywide production owner dataset has **not** been acquired. All 115,870 existing
county parcel boundaries remain intact. Original owner-enabled regions retain
their real names. New full-county owner tests use visibly synthetic names only.

## Download and review

- [v0.5.0 APK, checksums, installation instructions and permanent evidence](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/private-owners-v0.5.0)
- [PR #8](https://github.com/imissthefuhrer/Oregon-Land-Explorer/pull/8), based on PR #7 head `10d42ac`
- [Full ownership Android verification](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43739873375), APK source `e473af0`
- [Focused local selection/owner timing](https://github.com/imissthefuhrer/Oregon-Land-Explorer/actions/runs/43741865919), same production Android sources; added instrumentation only
- [Private workflow and acquisition instructions](PRIVATE-OWNERSHIP.md)
- [Unchanged phone-tested v0.3.2 fallback](https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/verified-v0.3.2)

The published APK is 56,222,668 bytes (53.62 MiB), SHA-256:
`4da14cf3fd1dcc9fc7ceff96467eef2bbc96090010b27723b6a249cd3c230867`.
The GitHub release asset's digest matches the tested artifact. Same application ID
`org.oregonlandexplorer.expanded`, persistent signing identity, version code 7.
APK checks confirm no INTERNET permission, original embedded package bytes and
local glyphs. The old APK/release remains unchanged; Android normally blocks
downgrades, so do not uninstall to force one.

## What actually ran

All compilation, GIS/package generation and device tests ran on GitHub-hosted
Ubuntu runners. Android uses API 35, Pixel 2 x86_64, Mesa software rendering via
Xvfb. Only v0.3.2 has user-confirmed physical-phone testing.

- 24 GIS tests passed, including exact stable-key matching with deliberately
  unrelated source IDs, leading zeros, duplicate/conflicting records, null/missing
  names, changed acreage, stale dates, authorization gates and GIS export parsing.
- Independently checked **every one of 115,870 synthetic name/key/polygon-hash
  associations**. Zero unmatched keys or duplicate identities in that fixture.
  Exact original county archive checksums are retained in the report; no geometry
  is rewritten to add names.
- 11 Android instrumentation tests passed in the full run: a v0.4 preparation and
  600-operation baseline; in-place APK upgrade with settings and all county hashes
  preserved; full-county ownership; six existing database/tile tests; regression
  setup; two original UI/import tests. Gradle compilation/lint passed; its unit-test
  task had no Kotlin unit sources, so it is not counted as executed unit tests.
- In airplane mode with Wi-Fi/mobile data off, ownership selection passed at every
  parcel region's real interior point and seven actual source polygons near Salem,
  Gervais, Silverton, Silver Falls, Gates, Detroit and the eastern forest.
- Popups display owner, tax lot, account, acreage and provenance. Deliberately
  unrelated OBJECTIDs do not change the joined name. Changed geometry/account or
  a newer parcel date suppresses an unchecked name.
- Enable/disable, explicit-null update, rollback, revision watermark, corrupted
  database rejection with a recomputed checksum, ownership removal, activity
  recreation and background/resume passed. Removing ownership preserves all
  county geometry. Original real owner lookup, Android document picker, GPS,
  optional aerial imagery and coverage regressions passed.
- A separate focused Android test passed 210 complete selections and 210 indexed
  owner lookups with the map active in airplane mode. See raw [timings](reports/owner-exact-lookup-performance.json).

The private repository's synthetic preparation succeeded in runs
[43739718944](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/runs/43739718944)
and [43740423353](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/runs/43740423353).
The final private pipeline run
[43742641046](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/runs/43742641046)
also passed the four updated owner tests, including missing parcel timestamps,
and produced 115,870 matched synthetic associations with zero unmatched keys.
A private-runner environment assumption in a test was corrected before that rerun.
A deliberately unapproved metadata-only fixture in
[43742212519](https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/runs/43742212519)
was rejected before owner-export download; zero artifacts were created. Its red
workflow status is the expected negative-test outcome, not a production data run.

## Storage and memory

| Measurement | Observed |
|---|---:|
| APK increase over v0.4.0 | 16,384 bytes |
| APK increase over v0.3.2 | 1,027,749 bytes |
| Synthetic 115,870-row owner overlay download | 11,547,826 bytes / 11.01 MiB |
| Synthetic overlay installed payload | 36,863,837 bytes / 35.16 MiB |
| Owner import including validation | 7.302 seconds |
| v0.4 baseline navigation PSS, min / median / max | 115.3 / 126.1 / 143.9 MiB |
| v0.5 with owners navigation PSS, min / median / max | 121.1 / 134.7 / 148.8 MiB |
| v0.5 maximum sampled native allocations | 45.06 MiB |
| v0.5 maximum sampled Java allocations | 45.64 MiB |
| v0.5 sampled file descriptors | 191–197 |
| Full county + owner activity ready | 3.265 seconds |

Each navigation scenario performed 600 pan/zoom operations, across urban/rural,
park and forest views. The owner-enabled navigation loop took 186.439 seconds.
The v0.4 baseline also had the original core parcel region enabled; the ownership
scenario enabled the 16 county parcel regions. These are observed scenario
comparisons, not an isolated causal measurement of ownership overhead. Historical
v0.3.2 extended-navigation PSS was about 127 MiB with its two expanded regions;
its geography differs from the county run. Every observed county navigation sample
was below 200 MiB. PSS includes instrumentation; transient import peak was not sampled.
The report's final post-removal memory value is not an active-navigation measurement.

Existing county downloads remain 38.63 MiB basemap plus 149.10 MiB parcels;
installed county payload remains approximately 359.15 MiB. Real owner-package
sizes will depend on actual names and match status and have not been measured.
The overlay adds no MapLibre geometry sources. SQLite uses read-only, short-lived
indexed connections with a 1 MiB cache and mmap disabled. Neither all county
geometries nor all owner names are decoded into application RAM.

Three later cold activity launches took 917 / 989 / 1,001 ms. Those launches were
after the regression cleanup with county regions disabled and owners removed;
they do not measure full owner-enabled map readiness.

## Actual local lookup timing

| Place | Complete exact selection + owner, median ms | Owner SQLite lookup, median ms |
|---|---:|---:|
| Salem | 6.871 | 0.319 |
| Gervais | 7.936 | 0.281 |
| Silverton | 2.924 | 0.262 |
| Silver Falls | 24.384 | 0.297 |
| Gates | 10.179 | 0.356 |
| Detroit | 5.096 | 0.295 |
| Eastern forest | 3.315 | 0.260 |

Across 210 samples: complete selection median **6.898 ms**, 95th percentile
**26.644 ms**, maximum **43.512 ms**; ownership-only median **0.295 ms**, 95th
percentile **0.419 ms**, maximum **6.428 ms**. Samples include the first lookup;
disk caches were not forcibly flushed. Selection includes candidate queries,
polygon decoding, exact hit testing, geometry hashing and ownership lookup.
Timers exclude assertions, camera settling and popup polling. These are emulator
local-work timings, not full physical-touch response times.

The earlier popup-check durations of 2.05–2.14 seconds include a deliberate
two-second overlapping-parcel polling wait and multiple negative lookup checks.
Its 1.11–1.27 second viewport measurements include a one-second settling delay.
Neither metric should be presented as isolated database or renderer latency.

## Remaining production blocker

No new countywide real ownership database was collected or publicly published.
The mirror exposes OWNERNAME, but its reviewed disclaimer does not unambiguously
settle countywide personal-use acquisition; the county open-data view requests a
written ownership-data request. This is not a finding that private offline use
is forbidden. Production requires an authorized export with exact TAXLOT/TAXACCT,
OWNERNAME and documented source date, or confirmation of an authorized bulk API
method plus a resulting export. Private hosted processing/offline phone storage
must fit the source conditions. See the request draft and browser-only workflow
in [PRIVATE-OWNERSHIP.md](PRIVATE-OWNERSHIP.md).

The synthetic tests validate engineering, not the accuracy of unavailable real
county owner records. Production unmatched/conflicting/missing/stale counts cannot
be reported until that authorized dataset is available. Names remain as-of source
statements; matching cannot prove that a later ownership transfer has not occurred.
