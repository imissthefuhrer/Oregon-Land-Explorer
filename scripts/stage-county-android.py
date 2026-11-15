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
    # The independent transport oracles consume the exact unchanged core archives.
    target=Path('output')/name;target.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(core/(name+'.olepack')) as archive:
        for member in archive.namelist():
            assert Path(member).name==member
            (target/member).write_bytes(archive.read(member))
for file in core.glob('*.olepack'):shutil.copyfile(file,tests/'test-packs'/file.name)
for file in county.glob('*.olepack'):shutil.copyfile(file,tests/'county-packs'/file.name)
catalog=json.loads((county/'county-catalog.json').read_text())
# Full-resolution coverage polygons stay in the downloadable GIS catalog. The
# phone's package list needs only IDs, counts, source metadata and region bounds.
catalog['packages']=[{k:v for k,v in p.items() if k!='coverage_geometry'} for p in catalog['packages']]
catalog['county_bbox']=json.loads((county/'source-audit.json').read_text())['boundary_bbox']
(assets/'county-catalog.json').write_text(json.dumps(catalog,separators=(',', ':')))
shutil.copyfile(county/'county-boundary.json',assets/'county-boundary.json')
with zipfile.ZipFile(core/'app-debug.apk') as z:
    for name in ['willamette-silver-outdoors','gervais-original-parcels']:
        assert z.read('assets/packs/'+name+'.olepack')==(core/(name+'.olepack')).read_bytes(), 'Core fallback package differs from the pinned v0.3.2 APK'
    for name in z.namelist():
        if name.startswith('assets/glyphs/') and not name.endswith('/'):
            target=assets/name.removeprefix('assets/');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
