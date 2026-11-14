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
            record=audit[str(f['properties']['OBJECTID'])]
            if isinstance(record,dict):
                assert fingerprint(f)==record['display_sha256']
                original={**f,'geometry':record['source_geometry']}
                assert fingerprint(original)==record['source_sha256']
                assert not shape(original['geometry']).is_valid
                assert record['display_boundary_outside_source_buffer_degrees']<=1e-7
                assert g.boundary.difference(shape(original['geometry']).boundary.buffer(1e-7)).length<=1e-7
                assert f['properties']['geometry_repair_note']
            else:
                assert fingerprint(f)==record
            assert f['properties']['legal_access']=='Unknown'
        outside=[f['id'] for f in data if not f['properties']['intersects_region']]
        assert outside==m['spatial_candidate_ids_outside_region']
        result.update(spatial_candidate_ids_outside_region=outside,parcel_count=len(ids),owners=m['owner_count'],fingerprints='PASS',valid_polygons='PASS',source_topology_repairs=m.get('geometry_repairs',[]))
    return result

if __name__=='__main__':
    results=[validate(p) for p in sorted(Path('output').iterdir()) if p.is_dir() and (p/'manifest.json').exists()]
    Path('output/packages-validation.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
