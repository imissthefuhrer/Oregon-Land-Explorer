"""Repeatable before/after input: convert unchanged PR #4 release packages.

Run with --source work/expanded --output output. Does not fetch new ownership
records; build_corridor still performs the verified ArcGIS extraction for updates.
"""
import argparse
import json
import shutil
import zipfile
from pathlib import Path
from .packages import sha
from .parcel_tiles import optimize


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=Path('output'))
    args=parser.parse_args(); args.output.mkdir(exist_ok=True)
    archives=sorted(args.source.glob('*.olepack'))
    assert len(archives)==5, 'Expected exact five PR #4 region archives'
    inputs={}
    for archive in archives:
        with zipfile.ZipFile(archive) as z:
            m=json.loads(z.read('manifest.json')); root=args.output/m['id'];root.mkdir()
            assert set(z.namelist())==set(m['sha256'])|{'manifest.json'}
            for name in z.namelist():
                assert Path(name).name==name
                (root/name).write_bytes(z.read(name))
            for name,digest in m['sha256'].items(): assert sha(root/name)==digest
        inputs[archive.name]=sha(archive)
        if m['kind']=='parcels': optimize(root)
        else: shutil.copyfile(archive,args.output/archive.name)
    assets=Path('android/app/src/main/assets')
    (assets/'packs').mkdir(parents=True,exist_ok=True)
    for name in ['willamette-silver-outdoors','gervais-original-parcels']:
        shutil.copyfile(args.output/(name+'.olepack'),assets/'packs'/(name+'.olepack'))
    tests=Path('android/app/src/androidTest/assets/test-packs');tests.mkdir(parents=True,exist_ok=True)
    for name in ['gervais-countryside','silver-falls','gervais-original-parcels','gervais-original-aerial']:
        shutil.copyfile(args.output/(name+'.olepack'),tests/(name+'.olepack'))
    # Exact previously tested local glyphs, also covered by the pinned release APK hash.
    with zipfile.ZipFile(args.source/'oregon-land-explorer-expanded.apk') as z:
        for name in z.namelist():
            if name.startswith('assets/glyphs/') and not name.endswith('/'):
                target=assets/name.removeprefix('assets/');target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(z.read(name))
    storage={'source_packages_sha256':inputs,'packages_bytes':{p.name:p.stat().st_size for p in args.output.glob('*.olepack')},
             'expanded_payload_bytes':{p.name:sum(f.stat().st_size for f in p.iterdir()) for p in args.output.iterdir() if p.is_dir() and (p/'manifest.json').exists()}}
    (args.output/'storage.json').write_text(json.dumps(storage,indent=2))
    print(json.dumps(storage,indent=2))


if __name__=='__main__': main()
