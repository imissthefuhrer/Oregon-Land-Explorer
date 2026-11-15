"""Synthetic identities exercise failure modes without acquiring county owners."""
import csv
import json
import sqlite3
import zipfile
import pytest
from pipeline import owners
from pipeline.packages import finish, sha


def fixture(tmp_path, count=9):
    root=tmp_path/'base';root.mkdir()
    with sqlite3.connect(root/'parcels.sqlite') as db:
        db.execute('CREATE TABLE parcels(parcel_key TEXT,properties TEXT,geometry TEXT)')
        for n in range(count):
            p={'OBJECTID':n+100,'TAXLOT':f'000{n}','TAXACCT':f'00R{n}','ACRES':1.0,'LASTUPDATE':1754006400000}
            if n==8:p['LASTUPDATE']=None
            db.execute('INSERT INTO parcels VALUES (?,?,?)',(f'41047:marion-open:test{n}',json.dumps(p),'same exact synthetic geometry '+str(n)))
    finish(root,{'id':'base','name':'Synthetic base','bbox':[-123,44.9,-122,45.1]},'parcels',source_namespace='marion-open')
    with zipfile.ZipFile(root.with_suffix('.olepack')) as z:payload={n:z.read(n) for n in z.namelist()}
    m=json.loads(payload['manifest.json']);m['schema_version']=3
    with zipfile.ZipFile(root.with_suffix('.olepack'),'w') as z:
        for n,data in payload.items():z.writestr(n,json.dumps(m) if n=='manifest.json' else data)
    return [root.with_suffix('.olepack')]


def test_every_association_reconciled_without_objectid_join(tmp_path):
    base=fixture(tmp_path)
    export=tmp_path/'owners.csv';meta=tmp_path/'meta.json'
    owners.synthetic_export(base,export,meta)
    data=list(owners.records(export));metadata=json.loads(meta.read_text())
    data[0]['SOURCE_ID']='99999999' # unrelated OBJECTID is only audit data
    data[1]['OWNERNAME']='' # null
    data[2]['OWNER_AS_OF']='2020-01-01T00:00:00Z' # stale
    data[3]['ACRES']='2.0' # changed geometry association evidence
    data.pop(7) # missing
    data.append({**data[4],'OWNERNAME':'SYNTHETIC TEST ONLY conflicting name'})
    data.append(dict(data[5])) # identical duplicate
    data.append({**data[6],'TAXACCT':'DIFFERENT ACCOUNT'}) # unmatched
    with export.open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=owners.FIELDS);w.writeheader();w.writerows(data)
    before=sha(base[0]);out=tmp_path/'out'
    report=owners.build(base,export,metadata,out,synthetic=True)
    assert sha(base[0])==before
    assert report['status_counts']=={'matched':3,'null':1,'stale':2,'parcel_changed':1,'conflict':1,'missing':1}
    assert report['ownership_coverage_result']=='PARTIAL'
    assert report['unmatched_source_keys']==1 and report['identical_duplicate_rows']==1
    assert report['all_base_records_accounted_for']
    with sqlite3.connect(out/'marion-private-owners/owners.sqlite') as db:
        assert db.execute('SELECT taxlot,taxacct,owner FROM owners WHERE taxlot=?',('0000',)).fetchone()==('0000','00R0','SYNTHETIC TEST ONLY 0000 / 00R0')
        assert db.execute("SELECT count(*) FROM owners WHERE status!='matched' AND owner IS NOT NULL").fetchone()[0]==0
        assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
        assert db.execute("EXPLAIN QUERY PLAN SELECT owner FROM owners WHERE county=? AND taxlot=? AND taxacct=?",('41047','0000','00R0')).fetchone()[3].startswith('SEARCH owners USING PRIMARY KEY')


def test_production_gated_before_reading_input(tmp_path,monkeypatch):
    monkeypatch.setenv('OLE_REPOSITORY_PRIVATE','false')
    metadata={'source':'county export','data_as_of':'2026-01-01','authorization_reference':'pending'}
    with pytest.raises(ValueError,match='private hosted'):owners.build([],tmp_path/'absent.csv',metadata,tmp_path/'out')
    monkeypatch.setenv('GITHUB_ACTIONS','true');monkeypatch.setenv('OLE_REPOSITORY_PRIVATE','true')
    with pytest.raises(ValueError,match='permits'):owners.check_authorization(metadata)
    metadata.update(private_offline_use_authorized=True,acquisition_method='public_api')
    with pytest.raises(ValueError,match='acquisition'):owners.check_authorization(metadata)
    metadata['acquisition_method']='county_export'
    assert owners.check_authorization(metadata)=='2026-01-01T00:00:00+00:00'


def test_invalid_fields_dates_and_real_names_rejected(tmp_path):
    base=fixture(tmp_path,1);export=tmp_path/'owners.csv';meta=tmp_path/'meta.json'
    owners.synthetic_export(base,export,meta);metadata=json.loads(meta.read_text())
    raw=next(owners.records(export))
    for changed in [{'TAXACCT':123},{'COUNTY':'41051'},{'ACRES':'NaN'},{'OWNER_AS_OF':'2026-11-01T12:00:00'},{'OWNER_AS_OF':'2030-01-01'}]:
        with pytest.raises(ValueError):owners.normalize({**raw,**changed},metadata)
    raw['OWNERNAME']='Actual-looking owner forbidden in public tests'
    with export.open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=owners.FIELDS);w.writeheader();w.writerow(raw)
    with pytest.raises(ValueError,match='real-looking'):owners.build(base,export,metadata,tmp_path/'out',synthetic=True)
    export.write_text('TAXLOT,TAXACCT\n0000,00R0\n')
    with pytest.raises(ValueError,match='columns'):list(owners.records(export))


def test_gis_exports_and_duplicate_base_identity_fail_closed(tmp_path):
    base=fixture(tmp_path,1);path=tmp_path/'data.gpkg'
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE owners(TAXLOT TEXT,TAXACCT TEXT,OWNERNAME TEXT)')
        db.execute('INSERT INTO owners VALUES (?,?,?)',('0001','00R1','SYNTHETIC TEST ONLY GIS'))
    assert next(owners.records(path))['TAXACCT']=='00R1'
    with pytest.raises(ValueError):list(owners.records(path,'owners;DROP TABLE owners'))
    metadata={'county':'41047','source':'synthetic://tests','data_as_of':'2026-11-01','authorization_reference':'synthetic','test_data':True}
    with pytest.raises(ValueError,match='Duplicate stable'):owners.build(base+base,path,metadata,tmp_path/'out',synthetic=True)
