"""Cloud-only publication of verified ownerless county data. Never replaces a release."""
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path


def gh(*args):
    return subprocess.check_output(['gh',*map(str,args)],text=True).strip()


def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()


repo=os.environ['GITHUB_REPOSITORY'];gis=os.environ['COUNTY_GIS_RUN'];apk_run=os.environ['COUNTY_APK_RUN'];audit_run=os.environ['COUNTY_AUDIT_RUN']
assert gis.isdigit() and apk_run.isdigit() and audit_run.isdigit()
app_run=None
for run in [gis,apk_run,audit_run]:
    info=json.loads(gh('api',f'repos/{repo}/actions/runs/{run}'))
    assert info['conclusion']=='success', f'Build {run} is not successful'
    if run==apk_run:app_run=info
root=Path('output/release');root.mkdir(parents=True,exist_ok=True)
gh('run','download',gis,'-n','marion-county-offline-maps','-D',root)
gh('run','download',apk_run,'-n','oregon-land-explorer-county-apk','-D','work/release-app')
gh('run','download',apk_run,'-n','county-final-coverage-catalog','-D','work/release-coverage')
gh('run','download',apk_run,'-n','county-android-test-evidence','-D','work/release-evidence')
gh('run','download',audit_run,'-n','county-source-inventory-comparison','-D','work/release-audit')
for source in Path('work/release-coverage').glob('*.json'):shutil.copyfile(source,root/source.name)
for source in Path('work/release-app').glob('*.apk'):shutil.copyfile(source,root/source.name)
for source in Path('work/release-audit').glob('*.json'):shutil.copyfile(source,root/source.name)
subprocess.run(['python','-m','pipeline.county_phone_validation','--source',str(root)],check=True)
summaries=list(Path('work/release-evidence').rglob('county-performance-summary.json'));assert len(summaries)==1
performance=json.loads(summaries[0].read_text());assert performance['result']=='PASS'
shutil.copyfile(summaries[0],root/'county-performance-summary.json')
validation=json.loads(Path('work/release-app/apk-validation.json').read_text())
app=root/'oregon-land-explorer-v0.4.0.apk'
assert sha(app)==validation['sha256'] and validation['internet_permission']=='ABSENT'
shutil.copyfile('work/release-app/apk-validation.json',root/'apk-validation.json')
with zipfile.ZipFile(root/'county-test-evidence.zip','w',zipfile.ZIP_DEFLATED) as z:
    for file in Path('work/release-evidence').rglob('*'):
        if file.is_file():z.write(file,file.relative_to('work/release-evidence'))
catalog=json.loads((root/'county-catalog.json').read_text());sizes=json.loads((root/'county-storage.json').read_text())
overlap=next(s for s in performance['county_scenarios'] if s['scenario']=='overlapping-regions')
instructions='''Install the signed APK over the existing v0.3.2 app. Do not uninstall.
Download marion-county-all-maps.zip in the phone browser and extract it with Android Files.
Open the extracted Maps folder. In Oregon Land Explorer choose Region > Import .olepack from Downloads.
Navigate to Maps, long-press a file, then select all 17 .olepack files and open them.
Wait for validation. Every package activates independently; failed imports preserve installed versions.
Use Region > County coverage and Manage installed packages to check coverage and enable states.
Keep the original owner-enabled Gervais/Silver Falls packages installed. County public downloads withhold owner names.
Enable airplane mode and verify GPS/recenter and home coverage privately. The new APK has no INTERNET permission.
Optional original Gervais OSIP 2024 aerial remains available in the verified-v0.3.2 release.

Boundaries are approximate, not surveys. Ownership does not establish public access; check restrictions locally.
Archive the original .olepack downloads. Updates retain a previous installed version and need staging space.
The unchanged v0.3.2 fallback APK is preserved separately; Android blocks ordinary APK downgrades, and v0.3.2 cannot read the new namespaced county parcel packages. Do not uninstall to attempt a rollback.
'''
(root/'INSTALL.txt').write_text(instructions)
with zipfile.ZipFile(root/'marion-county-all-maps.zip','w',zipfile.ZIP_STORED) as bundle:
    for file in sorted(root.glob('*.olepack')):bundle.write(file,'Maps/'+file.name)
    for file in sorted(root.glob('*.json')):bundle.write(file,'Reports/'+file.name)
    bundle.writestr('INSTALL.txt',instructions)
    bundle.writestr('SHA256SUMS.txt',''.join(f'{sha(p)}  Maps/{p.name}\n' for p in sorted(root.glob('*.olepack'))))
(root/'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(root.iterdir()) if p.is_file() and p.name!='SHA256SUMS.txt'))
body=f'''Full Marion County offline outdoor basemap and {sizes['parcel_count']:,} public parcel boundaries in {sizes['parcel_packages']} independent schema 3 PMTiles/SQLite packages. Countywide owner names are withheld by the official public download and remain gated pending written permission. Existing owner-enabled regions are preserved.

Install the APK over v0.3.2, download and extract **marion-county-all-maps.zip**, then import the 17 files in its **Maps** folder using Android's multiple-file picker. Detailed instructions are in INSTALL.txt. No desktop tools or in-app server are needed.

Basemap download: {sizes['basemap_download_bytes']/1048576:.1f} MiB. Parcel downloads: {sizes['parcel_download_bytes']/1048576:.1f} MiB; parcel installed payload: {sizes['parcel_installed_bytes']/1048576:.1f} MiB. APK: {app.stat().st_size/1048576:.1f} MiB. Keep additional free space for browser downloads, staging and previous versions.

Hosted API 35 Pixel 2 / Mesa: 600 pan/zoom operations with every county package and overlapping original regions; settled PSS {overlap['after_navigation_pss_mib']:.1f} MiB, sampled range {overlap['sampled_pss_min_max_mib'][0]:.1f}–{overlap['sampled_pss_min_max_mib'][1]:.1f} MiB. Original v0.3.2 on the same emulator: {performance['same_emulator_v032_both_regions_after_navigation_pss_mib']:.1f} MiB. See county-performance-summary.json for startup, exact-selection latency, native allocations, file descriptors, sampling limits and all scenarios. Physical-phone verification of this new build remains outstanding.

Checks include all 115,870 packaged source identities and original attribute fingerprints, holes/multipart geometry, independent decoded vector tiles, seven actual place-specific selections, 14 GPS recenters, recreation/backgrounding, package update/rollback/removal, and airplane mode. County owner values are absent. Full reports and screenshots are in county-test-evidence.zip; source discrepancies and coverage geometry are in the catalogs/reports. OSM completeness and current closures are not independently guaranteed.

Outdoor data: © OpenStreetMap contributors, ODbL 1.0, official OpenStreetMap France bulk extract. Parcel disclaimer/credits: Marion County Assessors Office / IT-GIS, retained in every package. Boundaries are approximate, not surveys; access remains unknown.

APK source `{app_run['head_sha']}`. [GIS build](https://github.com/{repo}/actions/runs/{gis}) · [Android tests](https://github.com/{repo}/actions/runs/{apk_run}) · [Source comparison](https://github.com/{repo}/actions/runs/{audit_run}) · [Review PR #7](https://github.com/{repo}/pull/7) · [Unchanged v0.3.2 fallback](https://github.com/{repo}/releases/tag/verified-v0.3.2).
'''
notes=Path('work/county-release-notes.md');notes.write_text(body)
assets=[str(p) for p in sorted(root.iterdir()) if p.is_file()]
print(gh('release','create','marion-county-v0.4.0',*assets,'--target',app_run['head_sha'],'--prerelease',
    '--title','Marion County offline v0.4.0 — public boundaries, owner production gated','--notes-file',notes))
