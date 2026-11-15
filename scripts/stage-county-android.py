"""Only small verified core maps go in the application; county maps are test APK assets."""
import json
import shutil
import zipfile
from pathlib import Path

core=Path('work/v032');county=Path('work/county-delivery')
assets=Path('android/app/src/main/assets');tests=Path('android/app/src/androidTest/assets')
(assets/'packs').mkdir(parents=True,exist_ok=True)
(tests/'test-packs').mkdir(parents=True,exist_ok=True)
(tests/'county-packs').mkdir(parents=True,exist_ok=True)
for name in ['willamette-silver-outdoors','gervais-original-parcels']:
    shutil.copyfile(core/(name+'.olepack'),assets/'packs'/(name+'.olepack'))
for file in core.glob('*.olepack'):shutil.copyfile(file,tests/'test-packs'/file.name)
for file in county.glob('*.olepack'):shutil.copyfile(file,tests/'county-packs'/file.name)
shutil.copyfile(county/'county-catalog.json',assets/'county-catalog.json')
with zipfile.ZipFile(core/'app-debug.apk') as z:
    for name in z.namelist():
        if name.startswith('assets/glyphs/') and not name.endswith('/'):
            target=assets/name.removeprefix('assets/');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
