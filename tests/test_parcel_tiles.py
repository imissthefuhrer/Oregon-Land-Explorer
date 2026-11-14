"""Synthetic geometry is used only in tests, never delivered as ownership data."""
import copy
import json
import sqlite3
import pytest
from pipeline.parcels import fingerprint
from pipeline.parcel_tiles import build_database, build_tiles, validate_optimized, COUNTY


def inputs():
    f={'type':'Feature','id':17,'properties':{'OBJECTID':17,'OWNERNAME':'Synthetic owner',
       'TAXLOT':'Synthetic lot','ACRES':2,'legal_access':'Unknown'},
       'geometry':{'type':'MultiPolygon','coordinates':[
           [[[-122.91,45.10],[-122.89,45.10],[-122.89,45.12],[-122.91,45.12],[-122.91,45.10]],
            [[-122.905,45.105],[-122.905,45.11],[-122.90,45.11],[-122.90,45.105],[-122.905,45.105]]],
           [[[-122.88,45.10],[-122.879,45.10],[-122.879,45.101],[-122.88,45.101],[-122.88,45.10]]]]}}
    data={'type':'FeatureCollection','features':[f]}
    manifest={'name':'Synthetic only','parcel_count':1,'parcel_source':'synthetic://test',
              'attribution':'Synthetic test only','extracted_at':'2026-01-01T00:00:00Z'}
    return data,{'17':fingerprint(f)},manifest


def test_sqlite_exact_holes_identity_attributes_and_spatial_index(tmp_path):
    from shapely.geometry import shape, Point
    data,audit,m=inputs(); path=tmp_path/'parcels.sqlite'
    build_database(path,data,audit,m)
    with sqlite3.connect(path) as db:
        key,owner,taxlot,g=db.execute('SELECT parcel_key,owner,taxlot,geometry FROM parcels').fetchone()
        assert (key,owner,taxlot)==(COUNTY+':17','Synthetic owner','Synthetic lot')
        assert json.loads(g)==data['features'][0]['geometry']
        assert not shape(json.loads(g)).covers(Point(-122.903,45.108))
        assert shape(json.loads(g)).covers(Point(-122.8795,45.1005))
        assert db.execute("SELECT id FROM parcel_bounds WHERE west<=? AND east>=? AND south<=? AND north>=?",(-122.90,-122.90,45.10,45.10)).fetchall()==[(17,)]
        plan=db.execute('EXPLAIN QUERY PLAN SELECT * FROM parcels WHERE parcel_key=?',(key,)).fetchall()
        assert any('INDEX' in row[-1] for row in plan)


def test_tiles_decode_and_reconcile_across_tile_boundaries(tmp_path):
    data,audit,m=inputs()
    (tmp_path/'source-record-hashes.json').write_text(json.dumps(audit))
    build_database(tmp_path/'parcels.sqlite',data,audit,m)
    assert build_tiles(tmp_path/'parcels.pmtiles',data,m)>10
    report=validate_optimized(tmp_path,m)
    assert report['parcel_count']==1 and report['tile_identities']=='PASS'
    # Corrupt exact geometry/association while keeping a syntactically valid DB.
    with sqlite3.connect(tmp_path/'parcels.sqlite') as db:
        db.execute("UPDATE parcels SET properties=replace(properties,'Synthetic owner','Wrong owner')")
    with pytest.raises(AssertionError): validate_optimized(tmp_path,m)


def test_duplicate_missing_mismatched_and_invalid_source_rejected(tmp_path):
    data,audit,m=inputs()
    bad=copy.deepcopy(data);bad['features'].append(bad['features'][0])
    with pytest.raises(ValueError,match='parcel IDs'): build_database(tmp_path/'duplicate.sqlite',bad,audit,m)
    with pytest.raises(ValueError,match='parcel IDs'): build_database(tmp_path/'missing.sqlite',data,{},m)
    bad=copy.deepcopy(data);bad['features'][0]['properties']['OWNERNAME']='Mismatched'
    with pytest.raises(ValueError,match='fingerprint'): build_database(tmp_path/'wrong.sqlite',bad,audit,m)
