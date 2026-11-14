"""Verify built APK contains the exact audited region and no internet permission."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

apk=Path(sys.argv[1])
assets=Path('android/app/src/main/assets/packs')
packages=[]
with zipfile.ZipFile(apk) as archive:
    for pack in assets.glob('*.olepack'):
        assert archive.read('assets/packs/'+pack.name)==pack.read_bytes()
        with zipfile.ZipFile(pack) as data:
            m=json.loads(data.read('manifest.json'))
            for name,digest in m['sha256'].items():
                assert hashlib.sha256(data.read(name)).hexdigest()==digest
            packages.append(m['id'])
    assert archive.read('assets/glyphs/Noto Sans Regular/0-255.pbf')
    style=json.loads(archive.read('assets/outdoor-style.json'))
    assert style['glyphs'].startswith('asset://') and 'sprite' not in style
sdk=Path(os.environ['ANDROID_HOME'])
aapt=sorted((sdk/'build-tools').glob('*/aapt'))[-1]
permissions=subprocess.check_output([str(aapt),'dump','permissions',str(apk)],text=True)
assert 'android.permission.INTERNET' not in permissions, permissions
report={'apk_bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
        'package_integrity':'PASS','internet_permission':'ABSENT','bundled_packages':packages}
Path('output/apk-validation.json').write_text(json.dumps(report,indent=2))
Path('output/SHA256SUMS.txt').write_text(report['sha256']+'  '+apk.name+'\n')
print(json.dumps(report,indent=2))
