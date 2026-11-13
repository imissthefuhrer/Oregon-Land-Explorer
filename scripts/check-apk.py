"""Verify built APK contains the exact audited region and no internet permission."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk=Path(sys.argv[1])
manifest=Path('output/region/manifest.json')
m=json.loads(manifest.read_text())
with zipfile.ZipFile(apk) as archive:
    assert archive.read('assets/region/manifest.json')==manifest.read_bytes()
    for name,digest in m['sha256'].items():
        assert hashlib.sha256(archive.read('assets/region/'+name)).hexdigest()==digest
sdk=Path(os.environ['ANDROID_HOME'])
aapt=sorted((sdk/'build-tools').glob('*/aapt'))[-1]
permissions=subprocess.check_output([str(aapt),'dump','permissions',str(apk)],text=True)
assert 'android.permission.INTERNET' not in permissions, permissions
report={'apk_bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
        'package_integrity':'PASS','internet_permission':'ABSENT','parcels':m['parcel_count'],'tiles':m['tile_count']}
Path('output/apk-validation.json').write_text(json.dumps(report,indent=2))
Path('output/SHA256SUMS.txt').write_text(report['sha256']+'  '+apk.name+'\n')
print(json.dumps(report,indent=2))
with open(os.environ.get('GITHUB_STEP_SUMMARY','output/summary.md'),'a') as summary:
    summary.write(f"## APK built and audited\n\nSize: {apk.stat().st_size/1048576:.1f} MiB. {m['parcel_count']} parcels, {m['tile_count']} aerial tiles.\n\nInternet permission absent. Asset checksums match the audited package. Emulator results are reported separately.\n")
