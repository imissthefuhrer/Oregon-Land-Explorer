"""Reconcile the complete hosted delivery; fail rather than publish a partial county."""
import json
import zipfile
import hashlib
import shutil
import sqlite3
import tempfile
from pathlib import Path
from .packages import sha
from .county import write


def verify_attributes(z,manifest):
    """Bind account IDs and original timestamps too, beyond the legacy four-field audit."""
    source=json.loads(z.read('source-metadata.json'))['attribute_fingerprints']
    count=0
    with tempfile.TemporaryDirectory() as temporary:
        dbpath=Path(temporary)/'parcels.sqlite'
        with z.open('parcels.sqlite') as input, dbpath.open('wb') as output:shutil.copyfileobj(input,output)
        with sqlite3.connect(f'file:{dbpath}?mode=ro',uri=True) as db:
            assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
            for oid,owner,raw in db.execute('SELECT id,owner,properties FROM parcels'):
                p=json.loads(raw)
                assert owner is None and 'OWNERNAME' not in p
                assert not set(p)-{'OBJECTID','TAXLOT','TAXACCT','ACRES','LASTUPDATE','owner_data_status','geometry_repair_note'}
                attrs={k:p.get(k) for k in ['OBJECTID','TAXLOT','TAXACCT','ACRES','LASTUPDATE']}
                actual=hashlib.sha256(json.dumps(attrs,sort_keys=True,separators=(',', ':')).encode()).hexdigest()
                assert actual==source[str(oid)], ('source attribute mismatch',manifest['id'],oid)
                count+=1
    assert count==manifest['parcel_count']==len(source)
    return count


def main():
    out=Path('output/delivery'); source=Path('work/extracted')
    catalog=json.loads((source/'county-catalog.json').read_text()); records=[]; total=0
    for entry in catalog['packages']:
        path=out/(entry['id']+'.olepack')
        with zipfile.ZipFile(path) as z:
            manifest=json.loads(z.read('manifest.json'))
            assert manifest['schema_version']==3 and manifest['kind']=='parcels'
            assert manifest['parcel_count']==entry['parcel_count']
            assert manifest['owner_count']==0 and manifest['owner_data_status']=='withheld'
            assert set(z.namelist())==set(manifest['sha256'])|{'manifest.json'}
            for name,expected in manifest['sha256'].items():
                with z.open(name) as payload:assert hashlib.file_digest(payload,'sha256').hexdigest()==expected
            verify_attributes(z,manifest)
            payload=sum(i.file_size for i in z.infolist())
        total+=entry['parcel_count']
        records.append({**entry,'file':path.name,'sha256':sha(path),'download_bytes':path.stat().st_size,'installed_bytes':payload})
    reconcile=json.loads((source/'county-reconciliation.json').read_text())
    assert total==reconcile['intersecting_official_county']==reconcile['packaged_unique_ids'] and not reconcile['missing_ids']
    catalog['packages']=records
    write(out/'county-catalog.json',catalog)
    for name in ['county-reconciliation.json','county-boundary.json','source-audit.json','open-data-item.json']:
        (out/name).write_bytes((source/name).read_bytes())
    with zipfile.ZipFile(out/'marion-outdoors.olepack') as basemap:
        provenance=json.loads(basemap.read('source-metadata.json'))
        assert provenance['boundary_sha256']==sha(out/'county-boundary.json'), 'County boundary changed between parcel and basemap preparation; rerun extraction'
    write(out/'county-attributes-validation.json',{'result':'PASS','validated_records':total,'owner_values':'ABSENT',
        'checks':['Original source attribute fingerprints including TAXACCT and LASTUPDATE','SQLite integrity','Allowlisted non-owner fields only','Package payload checksums']})
    write(out/'county-storage.json',{'parcel_packages':len(records),'parcel_count':total,
        'parcel_download_bytes':sum(r['download_bytes'] for r in records),'parcel_installed_bytes':sum(r['installed_bytes'] for r in records),
        'basemap_download_bytes':(out/'marion-outdoors.olepack').stat().st_size})
    (out/'INSTALL.txt').write_text('Marion County offline maps\n\nInstall the signed county APK over v0.3.2. Do not uninstall.\nDownload marion-outdoors.olepack and every marion-*.olepack parcel package.\nIn the app: Region > Import .olepack from Downloads. Select multiple packages.\nWait for validation. Packages install independently; a failed file preserves its installed version.\nUse Region > County coverage for missing/disabled package status.\nEnable airplane mode and verify GPS privately.\n\nCounty public parcel downloads deliberately omit owner names. This does not mean a parcel has no owner.\nExisting Gervais/Silver Falls owner-enabled packages are retained.\nCountywide owner production is gated pending written permission.\nBoundaries are approximate, not surveys; ownership does not grant public access.\nOSM data: © OpenStreetMap contributors, ODbL 1.0; source Geofabrik. County disclaimer retained in each package.\n')
    files=sorted(out.glob('*.olepack'))
    (out/'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in files))


if __name__=='__main__':main()
