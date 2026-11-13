import copy
import io
import pytest
from PIL import Image
from pipeline.parcels import extract, fingerprint
from pipeline.imagery import tile_bounds, tile_list, HALF_WORLD, validate_jpeg


def feature(i):
    return {'type':'Feature','properties':{'OBJECTID':i,'TAXLOT':str(i),'OWNERNAME':'Synthetic test','ACRES':1},
        'geometry':{'type':'Polygon','coordinates':[[[0,0],[1,0],[1,1],[0,1],[0,0]]]}}


def service(count=205, missing=False):
    calls=[]
    def request(url, p):
        calls.append(p)
        if p.get('returnIdsOnly'): return {'objectIds':list(range(count))}
        if p.get('returnCountOnly'): return {'count':count}
        ids=list(map(int,p['objectIds'].split(',')))
        if missing: ids=ids[:-1]
        return {'type':'FeatureCollection','features':[feature(i) for i in ids]}
    return request, calls


def test_batches_reconcile_and_keep_associations():
    request,calls=service()
    data,audit=extract([0,0,1,1],300,request)
    assert len(data['features'])==205
    assert len([c for c in calls if 'objectIds' in c])==3
    for f in data['features']: assert fingerprint(f)==audit[str(f['id'])]
    changed=copy.deepcopy(data['features'][0]);changed['properties']['OWNERNAME']='wrong owner'
    assert fingerprint(changed)!=audit['0']


def test_missing_record_fails():
    request,_=service(missing=True)
    with pytest.raises(ValueError,match='Missing'): extract([0,0,1,1],300,request)


def test_limits():
    request,_=service()
    with pytest.raises(ValueError,match='safety'): extract([0,0,1,1],10,request)


def test_standard_grid_not_osip_cache_grid():
    assert tile_bounds(0,0,0)==(-HALF_WORLD,-HALF_WORLD,HALF_WORLD,HALF_WORLD)
    a=tile_bounds(17,20786,47100); b=tile_bounds(17,20787,47100)
    assert abs(a[2]-b[0])<1e-8
    assert len(list(tile_list([-122.910,45.100,-122.890,45.115],13,17)))<300


def test_blank_image_fails():
    buf=io.BytesIO(); Image.new('RGB',(256,256),'black').save(buf,format='JPEG')
    with pytest.raises(ValueError,match='Blank'): validate_jpeg(buf.getvalue())
    with pytest.raises(ValueError): validate_jpeg(b'{"error":500}')


def test_spatial_candidates_are_reported_not_lost():
    base,_=service(count=1)
    def request(url,p):
        result=base(url,p)
        if 'features' in result:
            result['features'][0]['geometry']['coordinates']=[[[2,2],[3,2],[3,3],[2,3],[2,2]]]
        return result
    data,audit=extract([0,0,1,1],10,request)
    assert len(data['features'])==1 and len(audit)==1
    assert data['features'][0]['properties']['intersects_region'] is False


def test_invalid_geometry_fails_without_repair():
    base,_=service(count=1)
    def request(url,p):
        result=base(url,p)
        if 'features' in result:
            result['features'][0]['geometry']['coordinates']=[[[0,0],[1,1],[1,0],[0,1],[0,0]]]
        return result
    with pytest.raises(ValueError,match='Invalid polygon'): extract([0,0,1,1],10,request)
