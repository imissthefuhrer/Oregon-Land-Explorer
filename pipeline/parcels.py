import hashlib
import json
from shapely.geometry import shape, box
from .http import get_json

SOURCE = 'https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0'
FIELDS = ['OBJECTID', 'TAXLOT', 'ACRES', 'OWNERNAME']


def fingerprint(feature):
    """Bind the four source attributes to the exact source geometry."""
    payload = {'geometry': feature['geometry'], 'properties': {k: feature['properties'].get(k) for k in FIELDS}}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def extract(bounds, limit, request=get_json):
    spatial = {'where': '1=1', 'geometry': ','.join(map(str, bounds)),
               'geometryType': 'esriGeometryEnvelope', 'inSR': 4326,
               'spatialRel': 'esriSpatialRelIntersects', 'f': 'json'}
    ids_response = request(SOURCE + '/query', dict(spatial, returnIdsOnly='true'))
    ids = sorted(ids_response.get('objectIds') or [])
    count = request(SOURCE + '/query', dict(spatial, returnCountOnly='true'))['count']
    if not ids or len(ids) != len(set(ids)) or len(ids) != count:
        raise ValueError(f'Object-ID/count mismatch or empty region: {len(ids)} IDs / {count} count')
    if count > limit:
        raise ValueError(f'{count} parcels exceeds configured safety limit {limit}')
    features = []
    audit = {}
    for offset in range(0, len(ids), 100):
        batch = ids[offset:offset + 100]
        result = request(SOURCE + '/query', {'objectIds': ','.join(map(str, batch)),
            'outFields': ','.join(FIELDS), 'returnGeometry': 'true', 'outSR': 4326, 'f': 'geojson'})
        if result.get('exceededTransferLimit') or result.get('type') != 'FeatureCollection':
            raise ValueError('Truncated or invalid parcel response')
        rows = result['features']
        returned = [f['properties']['OBJECTID'] for f in rows]
        if len(returned) != len(set(returned)) or set(returned) != set(batch):
            raise ValueError(f'Missing or duplicate records in batch {offset}; missing IDs {sorted(set(batch)-set(returned))}')
        for feature in rows:
            geometry = shape(feature['geometry'])
            if geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty or not geometry.is_valid:
                raise ValueError(f'Invalid polygon for OBJECTID {feature["properties"]["OBJECTID"]}; no silent geometry repair')
            intersects = geometry.intersects(box(*bounds))
            if not intersects:
                print(f'ArcGIS spatial candidate outside exact region: OBJECTID={feature["properties"]["OBJECTID"]}, bounds={geometry.bounds}', flush=True)
            feature['properties']['intersects_region'] = intersects
            audit[str(feature['properties']['OBJECTID'])] = fingerprint(feature)
            feature['id'] = feature['properties']['OBJECTID']
            # No name-based guesses about public ownership or legal access.
            feature['properties']['ownership_class'] = 'Unknown'
            feature['properties']['legal_access'] = 'Unknown'
            features.append(feature)
    final_ids = request(SOURCE + '/query', dict(spatial, returnIdsOnly='true')).get('objectIds') or []
    if set(final_ids) != set(ids):
        raise ValueError('Source changed during extraction; rerun for a consistent region')
    return {'type': 'FeatureCollection', 'features': features}, audit
