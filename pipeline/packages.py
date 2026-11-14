"""Schema 2 packages: independent vector, parcel or aerial payloads, never executable styles."""
import hashlib
import json
import shutil
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from . import parcels
from .http import get_json


def sha(path):
    with open(path, 'rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def finish(root, config, kind, **extra):
    root = Path(root)
    manifest = {**config, 'schema_version': 2, 'kind': kind, 'revision': config.get('revision', 1),
                'extracted_at': datetime.now(timezone.utc).isoformat(), **extra,
                'sha256': {p.name: sha(p) for p in sorted(root.iterdir()) if p.name != 'manifest.json'}}
    (root/'manifest.json').write_text(json.dumps(manifest, indent=2))
    with zipfile.ZipFile(root.parent/(config['id']+'.olepack'), 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(root.iterdir()):
            z.write(p, p.name)
    return manifest


def build_parcels(config, output):
    root = Path(output)/config['id']
    root.mkdir(parents=True)
    data, audit = parcels.extract(config['bbox'], config['max_parcels'], repair_invalid=True)
    (root/'parcels.geojson').write_text(json.dumps(data, separators=(',', ':')))
    (root/'source-record-hashes.json').write_text(json.dumps(audit))
    metadata = {'layer': get_json(parcels.SOURCE, {'f': 'json'}),
                'item': get_json('https://www.arcgis.com/sharing/rest/content/items/bc90901732a4443bbfa1f949cc9cc205', {'f': 'json'})}
    (root/'source-metadata.json').write_text(json.dumps(metadata))
    from shapely.geometry import shape, box
    test = next(f for f in data['features'] if f['properties']['intersects_region'])
    p = shape(test['geometry']).intersection(box(*config['bbox'])).representative_point()
    return finish(root, config, 'parcels', parcel_source=parcels.SOURCE,
                  attribution='Marion County assessor-derived parcels, hosted by City of Stayton; approximate, not a survey',
                  parcel_count=len(data['features']), owner_count=sum(bool(f['properties'].get('OWNERNAME')) for f in data['features']),
                  geometry_repairs=[{'id':int(id),**{k:v for k,v in record.items() if k!='source_geometry'}} for id,record in audit.items() if isinstance(record,dict)],
                  spatial_candidate_ids_outside_region=[f['id'] for f in data['features'] if not f['properties']['intersects_region']],
                  legal_access='Unknown', selection_test={'id': test['id'], 'longitude': p.x, 'latitude': p.y})


def split_baseline(source, output):
    """Reuse the physically verified v1 payload without refetching or overwriting it."""
    with zipfile.ZipFile(source) as z:
        old = json.loads(z.read('manifest.json'))
        for kind, names in [('parcels', ['parcels.geojson','source-record-hashes.json','source-metadata.json']),
                            ('aerial', ['aerial.pmtiles'])]:
            config = {k: old[k] for k in ['bbox','minzoom','maxzoom']}
            config.update(id='gervais-original-'+kind, name='Original Gervais '+kind, revision=1)
            root = Path(output)/config['id']; root.mkdir(parents=True)
            for name in names:
                data = z.read(name)
                assert hashlib.sha256(data).hexdigest() == old['sha256'][name]
                (root/name).write_bytes(data)
            finish(root, config, kind, extracted_at=old['extracted_at'],
                   packaged_at=datetime.now(timezone.utc).isoformat(), **{k: old[k] for k in ['parcel_count','tile_count','owner_count','selection_test','attribution','parcel_source','imagery_source','spatial_candidate_ids_outside_region']})
