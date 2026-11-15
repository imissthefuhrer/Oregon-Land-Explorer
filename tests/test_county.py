"""County safety, namespace migration and uninterrupted polygon/attribute associations."""
import copy
import json
import sqlite3
import pytest
from shapely.geometry import box, mapping, shape
from pipeline import county
from pipeline.parcel_tiles import build_database, build_tiles, validate_optimized, source_identity
from pipeline.parcels import fingerprint
from test_parcel_tiles import inputs


def test_namespaced_identity_does_not_alias_legacy_objectid(tmp_path):
    data,_,manifest=inputs()
    p=data['features'][0]['properties'];p['TAXACCT']='TEST ACCOUNT'
    audit={'17':fingerprint(data['features'][0])}
    original=copy.deepcopy(manifest)
    manifest.update(identity_strategy='taxlot-taxacct-sha256-v1',source_namespace='marion-open')
    key=source_identity(p,manifest)
    assert key!=source_identity(p,original)
    changed={**p,'OBJECTID':999}
    assert source_identity(changed,manifest)==key
    assert source_identity({**p,'TAXLOT':'different'},manifest)!=key
    assert source_identity(p,{**manifest,'source_namespace':'marion-county-owner'})!=key
    (tmp_path/'source-record-hashes.json').write_text(json.dumps(audit))
    fields=['OBJECTID','TAXLOT','TAXACCT','ACRES','LASTUPDATE']
    (tmp_path/'source-metadata.json').write_text(json.dumps({'attribute_fingerprints':{'17':county.digest({k:p.get(k) for k in fields})}}))
    build_database(tmp_path/'parcels.sqlite',data,audit,manifest)
    build_tiles(tmp_path/'parcels.pmtiles',data,manifest)
    assert validate_optimized(tmp_path,manifest)['tile_identities']=='PASS'
    with sqlite3.connect(tmp_path/'parcels.sqlite') as db:
        assert db.execute('SELECT source_id FROM parcels').fetchone()[0]==key


def test_partition_retains_crossing_multipart_geometry_once(monkeypatch):
    monkeypatch.setattr(county,'MAX_RECORDS',2)
    boundary=box(-123.10,45.03,-122.80,45.20)
    rows=[]
    for i in range(8):
        x=-123.05+i*.025
        rows.append({'properties':{'OBJECTID':i},'geometry':mapping(box(x,45.09,x+.04,45.11))})
    groups=county.partition(rows,boundary)
    assert len(groups)>1 and all(len(g['features'])<=2 for g in groups)
    returned=[f for g in groups for f in g['features']]
    assert sorted(f['properties']['OBJECTID'] for f in returned)==list(range(8))
    assert {f['properties']['OBJECTID']:f['geometry'] for f in returned}=={f['properties']['OBJECTID']:f['geometry'] for f in rows}


def test_owner_production_gate_precedes_any_network_request(tmp_path,monkeypatch):
    monkeypatch.delenv('OLE_PRIVATE_AUTHORIZED',raising=False)
    monkeypatch.setattr(county,'request',lambda *a,**k:pytest.fail('Network reached before permission gate'))
    with pytest.raises(ValueError,match='private authorized'):
        county.extract(tmp_path,tmp_path/'checkpoints',owners=True)


def test_inventory_rejects_missing_duplicate_and_truncated_ids(monkeypatch):
    def broken(url,**params):return {'objectIds':[1,1]} if params.get('returnIdsOnly') else {'count':2}
    monkeypatch.setattr(county,'request',broken)
    with pytest.raises(ValueError,match='Inventory mismatch'):county.ids('test://fixture')


def test_geographic_coverage_cannot_silently_drop_outside_seed(monkeypatch):
    boundary=box(-121.0,44.0,-120.9,44.1)
    with pytest.raises(ValueError,match='outside processing seeds'):
        county.partition([{'properties':{'OBJECTID':1},'geometry':mapping(boundary)}],boundary)
