"""Hosted fixtures only: genuine public boundaries, entirely invented owner names."""
import csv
import json
import shutil
import sqlite3
from pathlib import Path
from pipeline import owners

base=Path('work/county-delivery');out=Path('output/owner-fixtures')
out.mkdir(parents=True,exist_ok=True)
archives=list(base.glob('*.olepack'))
export=out/'synthetic.csv';metadata=out/'synthetic-metadata.json'
owners.synthetic_export(archives,export,metadata)
meta=json.loads(metadata.read_text())
report=owners.build(archives,export,meta,out/'revision1',synthetic=True)
assert report['base_parcels']==115870 and report['status_counts']=={'matched':115870}
with sqlite3.connect(out/'revision1/marion-private-owners/owners.sqlite') as db:
    checked=0
    for _,p,_,geometry_hash in owners.base_rows(archives):
        result=db.execute('SELECT owner,geometry_sha256,status FROM owners WHERE county=? AND taxlot=? AND taxacct=?',('41047',p['TAXLOT'],p['TAXACCT'])).fetchone()
        assert result==('SYNTHETIC TEST ONLY '+p['TAXLOT']+' / '+p['TAXACCT'],geometry_hash,'matched')
        checked+=1
    assert checked==115870
report['independently_checked_owner_and_polygon_associations']=checked
catalog=json.loads((base/'county-catalog.json').read_text())
target=catalog['selection_targets'][0]
account=next(p['TAXACCT'] for _,p,key,_ in owners.base_rows(archives) if key==target['parcel_key'])
with export.open(newline='') as stream:
    row=next(r for r in csv.DictReader(stream) if r['TAXLOT']==target['taxlot'] and r['TAXACCT']==account)
# A newer explicit null must suppress the previously valid name after update.
row['OWNERNAME']=''
with (out/'null-update.csv').open('w',newline='') as stream:
    writer=csv.DictWriter(stream,fieldnames=owners.FIELDS);writer.writeheader();writer.writerow(row)
owners.build(archives,out/'null-update.csv',meta,out/'revision2',revision=2,synthetic=True)
test=Path('android/app/src/androidTest/assets/owner-packs');test.mkdir(parents=True,exist_ok=True)
for revision in [1,2]:shutil.copyfile(out/f'revision{revision}/marion-private-owners.olepack',test/f'owners-v{revision}.olepack')
shutil.copyfile(base/'county-catalog.json',test/'catalog.json')
report['overlay_download_bytes']=(test/'owners-v1.olepack').stat().st_size
report['overlay_installed_bytes']=sum(p.stat().st_size for p in (out/'revision1/marion-private-owners').iterdir())
report['warning']='Synthetic owners validate the engineering path, not actual ownership accuracy or acquisition permission.'
(Path('output')/'owner-county-reconciliation.json').write_text(json.dumps(report,indent=2))
