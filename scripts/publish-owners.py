"""Publish only a successful hosted test build. Never upload an owner database."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

def gh(*args):return subprocess.check_output(['gh',*args],text=True)

run=os.environ['VERIFIED_RUN'];assert re.fullmatch(r'[0-9]+',run)
repo='imissthefuhrer/Oregon-Land-Explorer'
info=json.loads(gh('api',f'repos/{repo}/actions/runs/{run}'))
assert info['conclusion']=='success' and info['head_branch']=='feat/private-offline-owners'
assert info['path']=='.github/workflows/owner-android.yml'
tag=json.loads(gh('api',f'repos/{repo}/git/ref/tags/private-owners-v0.5.0'))
assert tag['object']['type']=='commit' and tag['object']['sha']==info['head_sha'], 'Release tag must identify the exact verified app source'
root=Path('work/owner-publish');apk=root/'apk';evidence=root/'evidence';delivery=root/'delivery'
delivery.mkdir(parents=True)
gh('run','download',run,'--name','oregon-land-explorer-owner-apk','--dir',str(apk))
gh('run','download',run,'--name','owner-workflow-evidence','--dir',str(evidence))
file=apk/'oregon-land-explorer-v0.5.0.apk'
report=json.loads((apk/'apk-validation.json').read_text())
assert hashlib.sha256(file.read_bytes()).hexdigest()==report['sha256'] and report['internet_permission']=='ABSENT'
assert report['bundled_packages']==['gervais-original-parcels','willamette-silver-outdoors'] or set(report['bundled_packages'])=={'gervais-original-parcels','willamette-silver-outdoors'}
shutil.copyfile(file,delivery/file.name)
for name in ['owner-county-reconciliation.json','apk-validation.json']:
    shutil.copyfile(next(evidence.rglob(name)),delivery/name)
performance=next(evidence.rglob('owner-workflow-performance.json'))
assert json.loads(performance.read_text())['update_rollback_removal']=='PASS'
shutil.copyfile(performance,delivery/performance.name)
shutil.copyfile(next(evidence.rglob('owner-v04-before-update-memory.json')),delivery/'owner-v04-before-update-memory.json')
assert not any(p.suffix in ['.sqlite','.olepack','.csv','.geojson'] for p in evidence.rglob('*') if p.is_file())
shutil.make_archive(str(delivery/'owner-test-evidence'),'zip',evidence)
instructions='''Oregon Land Explorer v0.5.0 — private offline ownership support

Install oregon-land-explorer-v0.5.0.apk OVER the existing app. Keep app data;
do not uninstall or clear storage. Existing map packages/settings are retained.
Android may require allowing APK installation from your browser/Files app.

All 115,870 county boundary packages and county basemap remain available at:
https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/marion-county-v0.4.0

This APK supports private owners, but no newly acquired countywide names are
included. Original owner-enabled regions remain usable. Synthetic test names
are instrumentation-only and are NOT included in this APK or this release.

After obtaining an authorized ownership export, prepare it using the private
repository Actions workflow, download its private artifact on your phone,
extract the ZIP in Android Files and import marion-private-owners.olepack via
Region > Import .olepack from Downloads. Keep the county boundary packages
installed. County coverage lists ownership separately from parcel coverage.

Private workflow:
https://github.com/imissthefuhrer/Oregon-Land-Explorer-Private-Data/actions/workflows/private-owner-export.yml
Full source findings, input schema and browser-only instructions:
https://github.com/imissthefuhrer/Oregon-Land-Explorer/blob/feat/private-offline-owners/docs/PRIVATE-OWNERSHIP.md

Enable airplane mode and tap properties. Validated names are shown with tax
lot/account/acreage and provenance. Missing, null, conflicting or stale names
are explicit. Ownership does not establish legal access or permission to enter.

The original phone-tested v0.3.2 fallback remains unchanged:
https://github.com/imissthefuhrer/Oregon-Land-Explorer/releases/tag/verified-v0.3.2
Android normally refuses version downgrades; do not uninstall to force one.
'''
(delivery/'INSTALL.txt').write_text(instructions)
(delivery/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in sorted(delivery.iterdir()) if p.is_file()))
notes=root/'release-notes.md'
notes.write_text(f'''Adds private offline ownership imports and exact county/TAXLOT/TAXACCT popup lookup without changing the 115,870 county parcel geometries. Install over the existing app with its persistent signing identity. See INSTALL.txt.

**Countywide production owner data is still awaiting an authorized export/acquisition method.** This release contains the app and test reports, not a new countywide ownership database. Existing owner-enabled regions remain intact.

Hosted verification: https://github.com/{repo}/actions/runs/{run} — full synthetic 115,870-key matching, Android build/lint, upgrade preservation, airplane-mode selection across county regions, 600 pan/zoom operations, update/rollback/removal and original owner/GPS/aerial/import regressions. Emulator results are not physical-phone verification.

Private preparation and authorization details: https://github.com/{repo}/blob/feat/private-offline-owners/docs/PRIVATE-OWNERSHIP.md
''')
gh('release','create','private-owners-v0.5.0',*[str(p) for p in sorted(delivery.iterdir())],
   '--repo',repo,'--target',info['head_sha'],'--title','Oregon Land Explorer v0.5.0 — private offline owners',
   '--notes-file',str(notes),'--prerelease','--verify-tag')
