"""Independently reconcile every regional parcel and its original source fingerprint."""
import json
import zipfile
from pathlib import Path
from shapely.geometry import shape
from .parcels import fingerprint
from .packages import sha


def validate(root):
    m=json.loads((root/'manifest.json').read_text())
    for name,digest in m['sha256'].items():
        assert Path(name).name==name and sha(root/name)==digest
    with zipfile.ZipFile(root.parent/(m['id']+'.olepack')) as archive:
        assert set(archive.namelist())==set(m['sha256'])|{'manifest.json'}
        for p in root.iterdir(): assert archive.read(p.name)==p.read_bytes()
    result={'id':m['id'],'kind':m['kind'],'bbox':m['bbox'],'checksums':'PASS'}
    if m['kind']=='parcels':
        data=json.loads((root/'parcels.geojson').read_text())['features']
        audit=json.loads((root/'source-record-hashes.json').read_text())
        ids=[str(f['properties']['OBJECTID']) for f in data]
        assert len(ids)==len(set(ids))==m['parcel_count'] and set(ids)==set(audit)
        for f in data:
            g=shape(f['geometry']);assert not g.is_empty and g.is_valid and g.geom_type in ['Polygon','MultiPolygon']
            assert fingerprint(f)==audit[str(f['properties']['OBJECTID'])]
            assert f['properties']['legal_access']=='Unknown'
        result.update(parcel_count=len(ids),owners=m['owner_count'],fingerprints='PASS',valid_polygons='PASS')
    return result

if __name__=='__main__':
    results=[validate(p) for p in sorted(Path('output').iterdir()) if p.is_dir() and (p/'manifest.json').exists()]
    Path('output/packages-validation.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
