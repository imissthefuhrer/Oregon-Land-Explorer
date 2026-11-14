"""Run with python -m pipeline.build --region regions/gervais.json --output output/region."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import zipfile
from . import parcels, imagery
from .http import get_json


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--region', default='regions/gervais.json')
    parser.add_argument('--output', default='output/region')
    parser.add_argument('--bbox', nargs=4, type=float, help='west south east north; overrides configured bbox')
    args = parser.parse_args()
    config = json.loads(Path(args.region).read_text())
    if args.bbox:
        config['bbox'] = args.bbox
    w,s,e,n = config['bbox']
    if not (-125 < w < e < -116 and 41 < s < n < 47 and 0 <= config['minzoom'] <= config['maxzoom'] <= 18):
        raise ValueError('Invalid Oregon bounds or zoom range (maximum 18)')
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    # Retain the actual source metadata, including copyright and edit dates.
    source_metadata = {'parcels': get_json(parcels.SOURCE, {'f':'json'}),
                       'imagery': get_json(imagery.SOURCE, {'f':'json'})}
    data, audit = parcels.extract(config['bbox'], config['max_parcels'])
    (output/'parcels.geojson').write_text(json.dumps(data))
    (output/'source-record-hashes.json').write_text(json.dumps(audit, sort_keys=True))
    (output/'source-metadata.json').write_text(json.dumps(source_metadata))
    tile_count = imagery.build(output/'aerial.pmtiles', config)
    # A real interior point for repeatable instrumented parcel selection.
    from shapely.geometry import shape
    from shapely.geometry import box
    test_feature = next(f for f in data['features'] if f['properties']['intersects_region'])
    center = shape(test_feature['geometry']).intersection(box(*config['bbox'])).representative_point()
    manifest = dict(config, schema_version=1, extracted_at=datetime.now(timezone.utc).isoformat(),
        imagery_year=2024, imagery_source=imagery.SOURCE, parcel_source=parcels.SOURCE,
        parcel_count=len(data['features']), tile_count=tile_count,
        spatial_candidate_ids_outside_region=[f['id'] for f in data['features'] if not f['properties']['intersects_region']],
        owner_count=sum(bool(f['properties'].get('OWNERNAME')) for f in data['features']),
        selection_test={'id':test_feature['id'], 'longitude':center.x, 'latitude':center.y},
        legal_access='Unknown', ownership_class='Unknown',
        attribution='Oregon Statewide Imagery Program (OSIP), 2024; Marion County assessor-derived parcels',
        redistribution='Personal test region only; confirm parcel publisher terms before broader redistribution',
        sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(output.iterdir()) if p.name != 'manifest.json'})
    (output/'manifest.json').write_text(json.dumps(manifest, indent=2))
    from .validate import validate
    print(json.dumps(validate(output), indent=2))
    with zipfile.ZipFile(output.parent/(config['id']+'.olepack'), 'w', zipfile.ZIP_DEFLATED) as pack:
        for path in output.iterdir():
            pack.write(path, path.name)


if __name__ == '__main__':
    main()
