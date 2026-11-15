"""Resumable Marion County open-data extraction. Never requests ownership by default.

Run on a hosted runner. Production owner access requires a private repository and an
explicit authorization reference; that mode never uploads data in the public workflow.
Raw successful batches are immutable checkpoints, not silently reused across snapshots.
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from shapely import make_valid
from shapely.geometry import shape, mapping, box
from shapely.ops import unary_union
from shapely.validation import explain_validity
from shapely.prepared import prep

from .packages import finish, sha
from .parcels import fingerprint

OPEN = 'https://services1.arcgis.com/sYGZnQPdJ0azuLyn/arcgis/rest/services/Parcels_OpenDataView/FeatureServer/0'
COUNTY = 'https://gis.co.marion.or.us/arcgis/rest/services/Public/Parcels/MapServer/0'
MIRROR = 'https://services3.arcgis.com/SXXjryU22GsO8OEC/arcgis/rest/services/Parcels/FeatureServer/0'
BOUNDARY = 'https://gis.co.marion.or.us/arcgis/rest/services/Public/CityCountyBoundaries/MapServer/3'
ITEM = 'https://www.arcgis.com/sharing/rest/content/items/9e496e9558ae476e9b65e1cf51b2594a'
FIELDS = ['OBJECTID', 'TAXLOT', 'TAXACCT', 'ACRES', 'LASTUPDATE']
MAX_RECORDS = 12000  # below existing per-package budgets; dense urban cells split further


def request(url, **params):
    """Bounded backoff. ArcGIS errors and truncated responses are never success."""
    encoded = urllib.parse.urlencode({'f': 'json', **params})
    for attempt in range(5):
        try:
            # ArcGIS supports form POST for long object-ID lists. Avoid web-server
            # query-string limits while respecting the layer's record limit.
            req = urllib.request.Request(url if len(encoded)>1800 else url+'?'+encoded,
                data=encoded.encode() if len(encoded)>1800 else None,
                headers={'User-Agent': 'OregonLandExplorer/0.4 county audit'})
            with urllib.request.urlopen(req, timeout=90) as response:
                value = json.load(response)
            if 'error' in value:
                raise ValueError(str(value['error']))
            return value
        except (OSError, ValueError) as error:
            if attempt == 4:
                raise RuntimeError(f'ArcGIS request failed: {url}: {error}') from error
            time.sleep(min(2 ** attempt, 16))


def write(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')))
    temp.replace(path)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def ids(source):
    result = request(source + '/query', where='1=1', returnIdsOnly='true')
    values = sorted(result.get('objectIds') or [])
    count = request(source + '/query', where='1=1', returnCountOnly='true')['count']
    if not values or len(values) != len(set(values)) or len(values) != count:
        raise ValueError(f'Inventory mismatch: {source}: {len(values)}/{count}')
    return values


def audit_sources(output):
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    boundary = request(BOUNDARY + '/query', where='1=1', outFields='OBJECTID,NAME,STCOFIPS,COUNTYAREA,LASTUPDATE', outSR=4326, returnGeometry='true', f='geojson')
    assert len(boundary['features']) == 1
    polygon = shape(boundary['features'][0]['geometry'])
    assert polygon.is_valid and not polygon.is_empty
    write(output/'county-boundary.json', boundary)
    sources = {}
    for name, source in [('mirror', MIRROR), ('county', COUNTY), ('open_data', OPEN)]:
        layer = request(source)
        inventory = ids(source)
        write(output/(name+'-inventory.json'), inventory)
        sources[name] = {'url': source, 'count': len(inventory), 'id_set_sha256': digest(inventory),
            'extent': layer.get('extent'), 'fields': layer['fields'], 'editingInfo': layer.get('editingInfo'),
            'capabilities': layer.get('capabilities'), 'maxRecordCount': layer.get('maxRecordCount'),
            'advancedQueryCapabilities': layer.get('advancedQueryCapabilities'), 'copyrightText': layer.get('copyrightText')}
    item = request(ITEM)
    write(output/'open-data-item.json', item)
    # Compare by TAXLOT + TAXACCT, NEVER by OBJECTID. No owner values in reports.
    comparisons = []
    for name, lon, lat in [('Salem', -123.035,44.942), ('Gervais',-122.897,45.108), ('Silverton',-122.783,45.005),
                           ('Silver Falls',-122.657,44.878), ('Gates',-122.416,44.755), ('Detroit',-122.151,44.735)]:
        sample = request(OPEN+'/query', geometry=f'{lon},{lat}', geometryType='esriGeometryPoint', inSR=4326,
                         spatialRel='esriSpatialRelIntersects', outFields=','.join(FIELDS), outSR=4326, f='geojson')
        records = []
        for feature in sample.get('features', []):
            p = feature['properties']; matches = {}
            where = "TAXLOT='" + str(p['TAXLOT']).replace("'", "''") + "' AND TAXACCT='" + str(p['TAXACCT']).replace("'", "''") + "'"
            for label, source in [('mirror', MIRROR), ('county', COUNTY)]:
                response = request(source+'/query', where=where, outFields=','.join(FIELDS), outSR=4326, f='geojson')
                matches[label] = [{'object_id': f['properties']['OBJECTID'], 'attributes': f['properties'],
                    'geometry_sha256': digest(f['geometry']), 'hausdorff_degrees': shape(f['geometry']).hausdorff_distance(shape(feature['geometry']))} for f in response.get('features', [])]
            records.append({'open_data': p, 'geometry_sha256': digest(feature['geometry']), 'matches': matches})
        comparisons.append({'place': name, 'point': [lon,lat], 'records': records})
    report = {'observed_at': datetime.now(timezone.utc).isoformat(), 'sources': sources,
        'boundary_source': BOUNDARY, 'boundary_bbox': list(polygon.bounds), 'boundary_sha256': digest(boundary),
        'source_comparisons': comparisons, 'open_data_terms': item.get('licenseInfo'),
        'rights': {'public_packages': 'Official county open-data view, geometry and allowlisted non-owner fields only. County disclaimer retained.',
                   'owner_production': 'BLOCKED: written County GIS permission not supplied. User confirmed gate on 2026-11-15.'},
        'identity_policy': 'Dataset namespace plus SHA256 of compact JSON [TAXLOT,TAXACCT]. OBJECTID is only a source-local row ID; no cross-source equality assumed.'}
    write(output/'source-audit.json', report)
    return polygon


def repair(feature):
    """Same no-invented-boundary repair policy as the verified regional pipeline."""
    original = feature['geometry']; geometry = shape(original); original_hash = fingerprint(feature)
    if geometry.is_empty or geometry.geom_type not in ('Polygon', 'MultiPolygon'):
        raise ValueError(f'Invalid source geometry: {feature["properties"]["OBJECTID"]}')
    if geometry.is_valid:
        return geometry, original_hash
    fixed = make_valid(geometry, method='structure', keep_collapsed=False)
    outside = fixed.boundary.difference(geometry.boundary.buffer(1e-7)).length
    if fixed.is_empty or not fixed.is_valid or fixed.geom_type not in ('Polygon','MultiPolygon') or outside > 1e-7:
        raise ValueError(f'Unsafe geometry repair: {feature["properties"]["OBJECTID"]}')
    feature['geometry'] = mapping(fixed)
    feature['properties']['geometry_repair_note'] = 'Invalid source polygon; display topology repaired. Verify against original source.'
    return fixed, {'source_geometry': original, 'source_sha256': original_hash, 'display_sha256': fingerprint(feature),
        'reason': explain_validity(geometry), 'method': 'Shapely make_valid structure; keep_collapsed=False',
        'display_boundary_outside_source_buffer_degrees': outside}


def partition(features, boundary):
    """Geographic seeds; adaptively split dense cells. Full polygons are never cut."""
    config=json.loads((Path(__file__).resolve().parent.parent/'regions/marion-county.json').read_text())
    assert 1<=config['max_records_per_package']<=12000, 'Keep the county preparation budget; split large regions instead'
    maximum=min(MAX_RECORDS,config['max_records_per_package'])
    seeds=[(s['id'],s['name'],s['bbox']) for s in config['partition_seeds']]
    assert len({s[0] for s in seeds})==len(seeds)
    for i,seed in enumerate(seeds):
        bounds=seed[2];assert len(bounds)==4 and bounds[0]<bounds[2] and bounds[1]<bounds[3]
        for other in seeds[:i]:assert box(*bounds).intersection(box(*other[2])).area<1e-12, 'Processing seed interiors must not overlap'
    groups = {s[0]: [] for s in seeds};points={};prepared=prep(boundary)
    for feature in features:
        g = shape(feature['geometry']); point = (g if prepared.covers(g) else g.intersection(boundary)).representative_point()
        points[feature['properties']['OBJECTID']]=point
        candidates = [s for s in seeds if box(*s[2]).covers(point)]
        if not candidates:
            raise ValueError(f'Parcel outside processing seeds: {point}')
        groups[candidates[0][0]].append(feature)
    result = []
    def split(id, name, bounds, rows):
        if not rows:
            return
        if len(rows) > maximum:
            w,s,e,n = bounds; axis = 0 if (e-w)*.71 > n-s else 1
            # Fixed geographic midpoint retains comprehensible coverage, not record slicing.
            mid = ((w+e)/2 if axis == 0 else (s+n)/2)
            halves = ([w,s,mid,n],[mid,s,e,n]) if axis == 0 else ([w,s,e,mid],[w,mid,e,n])
            buckets = [[],[]]
            for f in rows:
                p = points[f['properties']['OBJECTID']]
                buckets[int((p.x if axis == 0 else p.y) >= mid)].append(f)
            if max(len(b) for b in buckets) == len(rows) and max(e-w,n-s)<.001:
                raise ValueError('Unpartitionable dense source cluster; review source')
            for i in range(2): split(id+str(i+1),name+' '+str(i+1),halves[i],buckets[i])
        else:
            result.append({'id':'marion-'+id, 'name':name+' parcels (owners withheld)', 'partition_bbox':bounds,
                           'coverage_geometry':mapping(boundary.intersection(box(*bounds))), 'features':rows})
    for id,name,bounds in seeds:
        split(id,name,bounds,groups[id])
    coverage = unary_union([box(*s[2]) for s in seeds])
    assert boundary.difference(coverage).area < 1e-12
    return result


def extract(output, checkpoint, owners=False):
    output = Path(output); checkpoint = Path(checkpoint)
    if owners and (os.environ.get('OLE_PRIVATE_AUTHORIZED') != 'true' or not os.environ.get('OLE_AUTHORIZATION_REFERENCE')):
        raise ValueError('Owner extraction requires private authorized execution and written permission reference')
    boundary = audit_sources(output)
    source = COUNTY if owners else OPEN
    layer = request(source); inventory = ids(source)
    snapshot = digest({'ids':inventory,'editingInfo':layer.get('editingInfo'),'fields':FIELDS,'owners':owners})
    cache = checkpoint/snapshot; cache.mkdir(parents=True,exist_ok=True)
    fields = FIELDS + (['OWNERNAME'] if owners else [])
    def batch(start):
        wanted = inventory[start:start+500]; path=cache/f'{start:08d}.json'
        if path.exists():
            value=json.loads(path.read_text())
        else:
            value=request(source+'/query', objectIds=','.join(map(str,wanted)), outFields=','.join(fields), returnGeometry='true', outSR=4326, f='geojson')
        returned=[f['properties']['OBJECTID'] for f in value.get('features',[])]
        if value.get('exceededTransferLimit') or len(returned)!=len(set(returned)) or set(returned)!=set(wanted):
            raise ValueError(f'Incomplete checkpoint batch {start}: {len(returned)}/{len(wanted)}')
        if not path.exists(): write(path,value)
        return path
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        paths=list(pool.map(batch,range(0,len(inventory),500)))
    if ids(source)!=inventory or request(source).get('editingInfo')!=layer.get('editingInfo'):
        raise ValueError('Source changed during extraction; checkpoint retained; retry new snapshot')
    features=[]; audits={}; outside=[]; stable=set(); attributes={}; all_seen=set();prepared=prep(boundary)
    for path in paths:
        for f in json.loads(path.read_text())['features']:
            p=f['properties']; oid=str(p['OBJECTID'])
            assert oid not in all_seen; all_seen.add(oid)
            if not owners and set(p)-set(FIELDS): raise ValueError('Unexpected fields in public download')
            if not p.get('TAXLOT') or not p.get('TAXACCT'): raise ValueError(f'Missing stable identifier: {oid}')
            identity=digest([p['TAXLOT'],p['TAXACCT']])
            if identity in stable: raise ValueError(f'Conflicting TAXLOT/TAXACCT: {oid}')
            stable.add(identity); attributes[oid]=digest(p)
            p['owner_data_status']='authorized private extract' if owners else 'Withheld from county public download; owner information unavailable'
            g,audit=repair(f)
            if not prepared.intersects(g): outside.append(int(oid)); continue
            audits[oid]=audit; features.append(f)
    assert all_seen==set(map(str,inventory))
    catalog=[]
    for group in partition(features,boundary):
        if owners:
            group['id']=group['id'].replace('marion-','marion-owner-',1)
            group['name']=group['name'].replace('(owners withheld)','(authorized owner data)')
        rows=group.pop('features'); root=output/group['id']; root.mkdir(exist_ok=True)
        write(root/'parcels.geojson',{'type':'FeatureCollection','features':rows})
        write(root/'source-record-hashes.json',{str(f['properties']['OBJECTID']):audits[str(f['properties']['OBJECTID'])] for f in rows})
        write(root/'source-metadata.json',{'source':source,'layer':layer,'terms':request(ITEM).get('licenseInfo'),
            'snapshot':snapshot, 'attribute_fingerprints':{str(f['properties']['OBJECTID']):attributes[str(f['properties']['OBJECTID'])] for f in rows},
            'authorization_reference':os.environ.get('OLE_AUTHORIZATION_REFERENCE') if owners else None})
        bounds=[shape(f['geometry']).bounds for f in rows]
        bbox=[min(b[0] for b in bounds),min(b[1] for b in bounds),max(b[2] for b in bounds),max(b[3] for b in bounds)]
        test=rows[len(rows)//2]; point=shape(test['geometry']).representative_point()
        geometry=group.pop('coverage_geometry')
        m=finish(root,{**group,'bbox':bbox,'revision':int(os.environ.get('OLE_DATA_REVISION','1')),'minzoom':12,'maxzoom':16},'parcels',
            parcel_source=source,source_namespace='marion-county-owner' if owners else 'marion-open',
            identity_strategy='taxlot-taxacct-sha256-v1',extracted_at=datetime.now(timezone.utc).isoformat(),
            source_updated_at=datetime.fromtimestamp(layer['editingInfo']['dataLastEditDate']/1000,timezone.utc).isoformat() if (layer.get('editingInfo') or {}).get('dataLastEditDate') else None,
            attribution='Marion County Assessors Office / IT-GIS; approximate, not a survey. County disclaimer in source-metadata.json.',
            parcel_count=len(rows),owner_count=sum(bool(f['properties'].get('OWNERNAME')) for f in rows),
            owner_data_status='authorized' if owners else 'withheld',legal_access='Unknown',
            selection_test={'id':test['properties']['OBJECTID'],'latitude':point.y,'longitude':point.x})
        catalog.append({**{k:v for k,v in m.items() if k!='sha256'},'coverage_geometry':geometry})
        # Intermediate schema-2 archives are deliberately not published or imported.
        (output/(group['id']+'.olepack')).unlink()
    union_ids=[f['properties']['OBJECTID'] for f in features]
    assert len(union_ids)==sum(r['parcel_count'] for r in catalog)==len(set(union_ids))
    write(output/'county-catalog.json',{'schema_version':1,'county':'41047','boundary_source':BOUNDARY,
        'boundary_sha256':sha(output/'county-boundary.json'),'source':source,'snapshot':snapshot,
        'owner_data_status':'withheld' if not owners else 'authorized private', 'packages':catalog})
    write(output/'county-reconciliation.json',{'result':'PASS','source':source,'snapshot':snapshot,
        'source_reported_count':len(inventory),'downloaded_unique_ids':len(all_seen),
        'intersecting_official_county':len(features),'outside_official_county_ids':outside,
        'packaged_unique_ids':len(union_ids),'missing_ids':[],'duplicate_ids':[],
        'partition_coverage':'Official boundary is contained by processing seed union; complete source polygons assigned exactly once, never clipped to partitions.',
        'absence_policy':'Gaps in assessor polygons are not classified as extraction failures. No owner or legal access is inferred from missing records.',
        'source_ids_sha256':digest(inventory),'attribute_fingerprints_sha256':digest(attributes)})
    print(json.dumps({'source_count':len(inventory),'county_count':len(features),'packages':len(catalog)}),flush=True)


def main():
    p=argparse.ArgumentParser(); p.add_argument('action',choices=['audit','extract','package']);p.add_argument('--output',default='output/county');p.add_argument('--checkpoint',default='work/county-checkpoints');p.add_argument('--shard',type=int,default=0);p.add_argument('--shards',type=int,default=1);p.add_argument('--owners',action='store_true'); a=p.parse_args()
    if a.action=='audit': audit_sources(a.output)
    elif a.action=='extract': extract(a.output,a.checkpoint,a.owners)
    else:
        from .parcel_tiles import optimize
        catalog=json.loads((Path(a.output)/'county-catalog.json').read_text())
        for i,pack in enumerate(catalog['packages']):
            if i%a.shards==a.shard:
                print('Optimizing '+pack['id'],flush=True)
                optimize(Path(a.output)/pack['id'],revision=pack['revision'])


if __name__=='__main__': main()
