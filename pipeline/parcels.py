import hashlib
import json
from shapely.geometry import shape, box, mapping
from shapely import make_valid
from shapely.validation import explain_validity
from .http import get_json

SOURCE = 'https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0'
FIELDS = ['OBJECTID', 'TAXLOT', 'ACRES', 'OWNERNAME']


def fingerprint(feature):
    """Bind the four source attributes to the exact source geometry."""
    payload = {'geometry': feature['geometry'], 'properties': {k: feature['properties'].get(k) for k in FIELDS}}
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def extract(bounds, limit, request=get_json, repair_invalid=False):
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
            source_geometry = feature['geometry']
            source_digest = fingerprint(feature)
            repair = None
            if geometry.geom_type not in ('Polygon', 'MultiPolygon') or geometry.is_empty:
                raise ValueError(f'Empty or non-polygon source OBJECTID {feature["properties"]["OBJECTID"]}')
            if not geometry.is_valid:
                if not repair_invalid:
                    raise ValueError(f'Invalid polygon for OBJECTID {feature["properties"]["OBJECTID"]}; no silent geometry repair')
                reason = explain_validity(geometry)
                fixed = make_valid(geometry, method='structure', keep_collapsed=False)
                if fixed.is_empty or not fixed.is_valid or fixed.geom_type not in ('Polygon','MultiPolygon'):
                    raise ValueError(f'Source polygon cannot be displayed safely: {feature["properties"]["OBJECTID"]}')
                # Reject a repair that moves the source boundary by more than ~0.1m.
                distance = geometry.boundary.hausdorff_distance(fixed.boundary)
                if distance > 1e-6:
                    raise ValueError(f'Repair would change boundary materially: OBJECTID {feature["properties"]["OBJECTID"]}, {distance} degrees')
                feature['geometry'] = mapping(fixed)
                feature['properties']['geometry_repair_note'] = 'Invalid source polygon; display topology repaired. Verify against original source.'
                repair = {'source_geometry': source_geometry, 'source_sha256': source_digest,
                          'reason': reason, 'method': 'Shapely make_valid structure; keep_collapsed=False',
                          'boundary_hausdorff_degrees': distance}
                geometry = fixed
                print(f'Audited source topology repair: OBJECTID={feature["properties"]["OBJECTID"]}, {reason}, boundary displacement={distance}', flush=True)
            intersects = geometry.intersects(box(*bounds))
            if not intersects:
                print(f'ArcGIS spatial candidate outside exact region: OBJECTID={feature["properties"]["OBJECTID"]}, bounds={geometry.bounds}', flush=True)
            feature['properties']['intersects_region'] = intersects
            audit[str(feature['properties']['OBJECTID'])] = dict(repair, display_sha256=fingerprint(feature)) if repair else fingerprint(feature)
            feature['id'] = feature['properties']['OBJECTID']
            # No name-based guesses about public ownership or legal access.
            feature['properties']['ownership_class'] = 'Unknown'
            feature['properties']['legal_access'] = 'Unknown'
            features.append(feature)
    final_ids = request(SOURCE + '/query', dict(spatial, returnIdsOnly='true')).get('objectIds') or []
    if set(final_ids) != set(ids):
        raise ValueError('Source changed during extraction; rerun for a consistent region')
    return {'type': 'FeatureCollection', 'features': features}, audit
