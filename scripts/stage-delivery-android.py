"""Reuse the signed v0.5 core bytes; real private data never enters this build."""
import json
import shutil
import zipfile
from pathlib import Path

assets=Path('android/app/src/main/assets');tests=Path('android/app/src/androidTest/assets/test-packs')
tests.mkdir(parents=True,exist_ok=True)
for p in Path('work/v032').glob('*.olepack'):shutil.copyfile(p,tests/p.name)
with zipfile.ZipFile('work/v05/oregon-land-explorer-v0.5.0.apk') as apk:
    for name in apk.namelist():
        if name.startswith(('assets/packs/','assets/glyphs/')) and not name.endswith('/') or name in ['assets/county-catalog.json','assets/county-boundary.json']:
            dest=assets/name.removeprefix('assets/');dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(apk.read(name))
for p in (assets/'packs').glob('*.olepack'):
    dest=Path('output')/p.stem;dest.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(p) as archive:
        for name in archive.namelist():
            assert Path(name).name==name
            (dest/name).write_bytes(archive.read(name))
assert len(list((assets/'packs').glob('*.olepack')))==2
assert not list(assets.rglob('owners.sqlite'))
