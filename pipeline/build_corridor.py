"""Hosted Tilemaker preparation, package production, and independently decoded GIS report."""
import argparse
import gzip
import json
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path
from collections import Counter
import mapbox_vector_tile
from pmtiles.reader import Reader, MmapSource, all_tiles
from .imagery import tile_list, tile_xy
from .packages import finish, build_parcels, split_baseline, sha


def run(*args):
    subprocess.run(list(map(str, args)), check=True)


def validate_vector(path, config):
    counts = Counter(); names = set(); total = 0
    with path.open('rb') as f:
        source = MmapSource(f); reader = Reader(source)
        for (z,x,y), blob in all_tiles(source):
            decoded = mapbox_vector_tile.decode(gzip.decompress(blob) if blob[:2] == b'\x1f\x8b' else blob)
            assert decoded, f'Empty tile {z}/{x}/{y}'
            total += 1
            if z == config['maxzoom']:
                for layer, data in decoded.items():
                    counts[layer] += len(data['features'])
                    names.update(f['properties'].get('name', '') for f in data['features'])
        for z,x,y in tile_list(config['bbox'], config['minzoom'], config['maxzoom']):
            assert reader.get(z,x,y), f'Uncovered corridor tile {z}/{x}/{y}'
        targets=[]
        for target in config['targets']:
            found=Counter(); labels=set()
            for z,x,y in tile_list(target['bbox'], 15,15):
                blob=reader.get(z,x,y)
                d=mapbox_vector_tile.decode(gzip.decompress(blob) if blob[:2]==b'\x1f\x8b' else blob)
                for layer,data in d.items():
                    for feature in data['features']:
                        props=feature['properties']; found[layer+':'+props.get('class','')]+=1
                        if props.get('name'): labels.add(props['name'])
            assert any(k.startswith('roads:') for k in found), target
            targets.append({**target,'tile_feature_counts_with_edge_duplicates':dict(found),'names':sorted(labels)})
    assert all(counts[k]>0 for k in ['roads','water','waterway','land','place'])
    return {'result':'PASS','tiles':total,'maxzoom_features_with_edge_duplicates':dict(counts),
            'targets':targets,'limits':'Tile counts include edge duplicates. OSM completeness and current trail closures are not independently established.'}


def main():
    p=argparse.ArgumentParser();p.add_argument('--config',default='regions/corridor.json');p.add_argument('--baseline',required=True)
    args=p.parse_args(); config=json.loads(Path(args.config).read_text()); out=Path('output'); out.mkdir(exist_ok=True)
    work=Path('work/vector');work.mkdir(parents=True,exist_ok=True)
    # Official bulk download; never any request to OSM's public tile infrastructure.
    url='https://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf'
    source=work/'oregon.osm.pbf'
    if not source.exists(): urllib.request.urlretrieve(url,source)
    with (work/'source-info.json').open('w') as info:
        subprocess.run(['osmium','fileinfo','-e','-j',str(source)],stdout=info,check=True)
    # Buffer and smart multipolygon completion protect boundary roads/park grounds.
    w,s,e,n=config['bbox']; buffer=[w-.04,s-.04,e+.04,n+.04]
    run('osmium','extract','-b',','.join(map(str,buffer)),'-s','smart',source,'-o',work/'extract.osm.pbf','--overwrite')
    tileconfig=json.loads(Path('pipeline/vector/config.json').read_text())
    tileconfig['settings']['bounding_box']=config['bbox']
    (work/'config.json').write_text(json.dumps(tileconfig))
    root=out/config['id'];root.mkdir(exist_ok=True)
    run('tilemaker','--input',work/'extract.osm.pbf','--output',root/'basemap.pmtiles','--config',work/'config.json','--process','pipeline/vector/process.lua','--bbox',','.join(map(str,config['bbox'])))
    report=validate_vector(root/'basemap.pmtiles',config)
    (out/'vector-validation.json').write_text(json.dumps(report,indent=2))
    (root/'source-metadata.json').write_text(json.dumps({'url':url,'sha256':sha(source),'osmium':json.loads((work/'source-info.json').read_text()),'tool':'Tilemaker v3.2.0','schema':'Oregon outdoor v1','license':'https://opendatacommons.org/licenses/odbl/1-0/'}))
    finish(root,{k:v for k,v in config.items() if k not in ['parcel_regions']},'vector',tile_count=report['tiles'],attribution='© OpenStreetMap contributors • ODbL 1.0 • source extract: Geofabrik')
    for region in config['parcel_regions']:
        build_parcels({**region,'revision':config['revision']},out)
    split_baseline(args.baseline,out)
    # Only vector and original parcels are core assets; broader regions remain separate downloads.
    assets=Path('android/app/src/main/assets'); (assets/'packs').mkdir(parents=True,exist_ok=True)
    for id in [config['id'],'gervais-original-parcels']:
        shutil.copyfile(out/(id+'.olepack'),assets/'packs'/(id+'.olepack'))
    tests=Path('android/app/src/androidTest/assets/test-packs');tests.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(out/'silver-falls.olepack',tests/'silver-falls.olepack')
    font=work/'fonts.zip'
    urllib.request.urlretrieve('https://github.com/openmaptiles/fonts/releases/download/v2.0/noto-sans.zip',font)
    with zipfile.ZipFile(font) as z:
        for name in z.namelist():
            if name.startswith('Noto Sans Regular/') and name.split('/')[-1] in ['0-255.pbf','256-511.pbf','512-767.pbf','768-1023.pbf','1024-1279.pbf','1280-1535.pbf','8192-8447.pbf']:
                target=assets/'glyphs'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
    urllib.request.urlretrieve('https://raw.githubusercontent.com/notofonts/noto-fonts/main/LICENSE',assets/'glyphs'/'OFL.txt')
    sizes={p.name:p.stat().st_size for p in sorted(out.glob('*.olepack'))}
    (out/'storage.json').write_text(json.dumps({'packages_bytes':sizes,'expanded_payload_bytes':{p.name:sum(f.stat().st_size for f in p.iterdir()) for p in out.iterdir() if p.is_dir() and (p/'manifest.json').exists()},'baseline_apk_bytes':34878608,'baseline_pack_bytes':1618225},indent=2))
    print(json.dumps(sizes,indent=2))

if __name__=='__main__': main()
