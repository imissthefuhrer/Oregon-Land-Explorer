import hashlib
import json
import sys
from pathlib import Path
from pmtiles.reader import Reader, MmapSource
from shapely.geometry import shape
from .imagery import tile_list, validate_jpeg
from .parcels import fingerprint


def validate(root):
    root = Path(root)
    manifest = json.loads((root/'manifest.json').read_text())
    for name, digest in manifest['sha256'].items():
        if Path(name).name != name or hashlib.sha256((root/name).read_bytes()).hexdigest() != digest:
            raise ValueError(f'Package checksum mismatch: {name}')
    data = json.loads((root/'parcels.geojson').read_text())
    audit = json.loads((root/'source-record-hashes.json').read_text())
    ids = [str(f['properties']['OBJECTID']) for f in data['features']]
    assert len(ids) == len(set(ids)) == manifest['parcel_count']
    assert set(ids) == set(audit)
    for f in data['features']:
        assert fingerprint(f) == audit[str(f['properties']['OBJECTID'])], 'Geometry/attribute association changed'
        geom = shape(f['geometry'])
        assert geom.is_valid and not geom.is_empty and geom.geom_type in ('Polygon', 'MultiPolygon')
        assert f['properties']['legal_access'] == 'Unknown'
    with (root/'aerial.pmtiles').open('rb') as file:
        reader = Reader(MmapSource(file))
        count = 0
        for z,x,y in tile_list(manifest['bbox'], manifest['minzoom'], manifest['maxzoom']):
            tile = reader.get(z,x,y)
            assert tile, f'Missing imagery tile {z}/{x}/{y}'
            validate_jpeg(tile)
            count += 1
        assert count == manifest['tile_count']
    return {'result':'PASS', 'parcels':len(ids), 'tiles':count, 'owner_count':manifest['owner_count'],
            'checks':['checksums', 'source geometry/attribute fingerprints', 'valid polygons', 'every expected tile decoded and nonblank']}


if __name__ == '__main__':
    print(json.dumps(validate(sys.argv[1]), indent=2))
