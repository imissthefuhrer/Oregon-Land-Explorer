"""Private attribute-only ownership overlay. Parcel geometry is never rewritten.

Production requires a private GitHub runner and a source authorization reference.
The public CI path accepts only generated, visibly synthetic owner fixtures.
"""
import argparse
import csv
import hashlib
import json
import math
import os
import re
import sqlite3
import tempfile
import zipfile
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from .packages import finish, sha

APPLICATION_ID=0x4f4c454f
STATUSES={'matched','missing','null','conflict','stale','parcel_changed'}
FIELDS=['COUNTY','TAXLOT','TAXACCT','OWNERNAME','OWNER_AS_OF','ACRES','SOURCE_ID']


def fingerprint(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',', ':'),ensure_ascii=False).encode()).hexdigest()


def date(value):
    if isinstance(value,(float,int)):return datetime.fromtimestamp(value/1000,timezone.utc)
    text=str(value).strip()
    if re.fullmatch(r'\d{13}',text):return datetime.fromtimestamp(int(text)/1000,timezone.utc)
    parsed=datetime.fromisoformat(text.replace('Z','+00:00'))
    if len(text)==10:return parsed.replace(tzinfo=timezone.utc)
    if parsed.tzinfo is None:raise ValueError('Owner timestamps must specify a timezone')
    return parsed.astimezone(timezone.utc)


def check_authorization(metadata, synthetic=False):
    required=['source','data_as_of','authorization_reference']
    if any(not isinstance(metadata.get(k),str) or not metadata[k].strip() for k in required):
        raise ValueError('Source, source data_as_of and authorization_reference are required')
    stamp=date(metadata['data_as_of'])
    if stamp>datetime.now(timezone.utc):raise ValueError('Owner source vintage cannot be in the future')
    if synthetic:
        if metadata.get('test_data') is not True:raise ValueError('Synthetic mode requires test_data=true')
    else:
        if metadata.get('test_data') is True:raise ValueError('Synthetic inputs require explicit synthetic mode')
        if os.environ.get('GITHUB_ACTIONS')!='true' or os.environ.get('OLE_REPOSITORY_PRIVATE')!='true':
            raise ValueError('Production owner processing requires a private hosted repository')
        if metadata.get('private_offline_use_authorized') is not True:
            raise ValueError('Confirm the actual source permits this private acquisition and offline use')
        if metadata.get('acquisition_method') not in ['county_export','explicit_api_permission','authorized_provider_export']:
            raise ValueError('Document the permitted acquisition method; API visibility alone is insufficient')
    return stamp.isoformat()


def records(path, table='owners'):
    """CSV, attribute-only GeoJSON, or county GIS GeoPackage/SQLite export."""
    path=Path(path)
    if path.stat().st_size>384*1024*1024:raise ValueError('Owner input exceeds preparation limit')
    if path.suffix.lower()=='.csv':
        with path.open(encoding='utf-8-sig',newline='') as stream:
            reader=csv.DictReader(stream)
            if not {'TAXLOT','TAXACCT','OWNERNAME'}<=set(reader.fieldnames or []):raise ValueError('Export needs TAXLOT, TAXACCT and OWNERNAME columns')
            yield from reader
    elif path.suffix.lower() in ['.geojson','.json']:
        data=json.loads(path.read_text())
        if data.get('type')!='FeatureCollection':raise ValueError('Expected a GIS FeatureCollection')
        for f in data['features']:yield f['properties']
    elif path.suffix.lower() in ['.sqlite','.gpkg','.db']:
        if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]{0,62}',table):raise ValueError('Invalid export table')
        with sqlite3.connect(f'file:{path}?mode=ro',uri=True) as db:
            db.row_factory=sqlite3.Row
            cols=[r[1] for r in db.execute(f'PRAGMA table_info("{table}")')]
            selected=[k for k in FIELDS if k in cols]
            if not {'TAXLOT','TAXACCT','OWNERNAME'}<=set(selected):raise ValueError('Export needs TAXLOT, TAXACCT and OWNERNAME columns')
            for row in db.execute('SELECT '+','.join('"'+k+'"' for k in selected)+f' FROM "{table}"'):yield dict(row)
    else:raise ValueError('Use CSV, GeoJSON, GeoPackage or SQLite export')


def normalize(row, metadata):
    if not {'TAXLOT','TAXACCT','OWNERNAME'}<=row.keys():raise ValueError('Missing required ownership fields')
    p={k:row.get(k) for k in FIELDS}
    p['COUNTY']=str(p.get('COUNTY') or metadata.get('county','')).strip()
    for k in ['TAXLOT','TAXACCT']:
        if not isinstance(p[k],str) or not p[k].strip():raise ValueError(f'Missing string {k}; preserve leading zeroes')
        p[k]=p[k].strip()
        if len(p[k])>128:raise ValueError('Identifier too long')
    if p['COUNTY']!='41047':raise ValueError('Owner record has wrong or missing county FIPS')
    if p['OWNERNAME'] is not None:
        if not isinstance(p['OWNERNAME'],str):raise ValueError('OWNERNAME must be text or null')
        p['OWNERNAME']=p['OWNERNAME'].strip() or None
        if p['OWNERNAME'] and (len(p['OWNERNAME'])>1024 or '\x00' in p['OWNERNAME']):raise ValueError('Invalid owner name')
    p['OWNER_AS_OF']=date(p.get('OWNER_AS_OF') or metadata['data_as_of']).isoformat()
    if date(p['OWNER_AS_OF'])>date(metadata['data_as_of']):raise ValueError('Record owner date exceeds documented snapshot')
    p['ACRES']=None if p['ACRES'] in [None,''] else float(p['ACRES'])
    if p['ACRES'] is not None and (not math.isfinite(p['ACRES']) or p['ACRES']<0):raise ValueError('Invalid source acreage')
    p['SOURCE_ID']=None if p['SOURCE_ID'] is None else str(p['SOURCE_ID'])
    return p


def base_rows(archives):
    """Bounded disk cursor. No all-county geometry collection is built."""
    for archive in sorted(map(Path,archives)):
        with zipfile.ZipFile(archive) as z:
            m=json.loads(z.read('manifest.json'))
            if m.get('kind')!='parcels' or m.get('source_namespace')!='marion-open':continue
            if m.get('schema_version')!=3:raise ValueError('Owners require the verified county schema 3 geometry')
            with tempfile.TemporaryDirectory() as directory:
                import shutil
                path=Path(directory)/'base.sqlite'
                with z.open('parcels.sqlite') as input,path.open('wb') as output:shutil.copyfileobj(input,output)
                if sha(path)!=m['sha256']['parcels.sqlite']:raise ValueError('Base checksum mismatch')
                with sqlite3.connect(f'file:{path}?mode=ro',uri=True) as db:
                    for key,raw,geometry in db.execute('SELECT parcel_key,properties,geometry FROM parcels'):
                        p=json.loads(raw)
                        yield m,p,key,hashlib.sha256(geometry.encode()).hexdigest()


def build(archives, export, metadata, output, revision=1, synthetic=False, table='owners'):
    stamp=check_authorization(metadata,synthetic)
    grouped=defaultdict(dict);input_count=0
    for raw in records(export,table):
        input_count+=1
        if input_count>250000:raise ValueError('Too many source rows; review the export')
        p=normalize(raw,metadata)
        if synthetic and p['OWNERNAME'] and not p['OWNERNAME'].startswith('SYNTHETIC TEST ONLY '):
            raise ValueError('Public test mode rejects real-looking owner names')
        grouped[(p['COUNTY'],p['TAXLOT'],p['TAXACCT'])][fingerprint(p)]=p
    root=Path(output)/'marion-private-owners';root.mkdir(parents=True,exist_ok=False)
    counts=Counter();used=set();base_ids=set();associations=[];bounds=[]
    with sqlite3.connect(root/'owners.sqlite') as db:
        db.executescript(f'''PRAGMA application_id={APPLICATION_ID}; PRAGMA user_version=1;
          CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
          CREATE TABLE owners(county TEXT NOT NULL,taxlot TEXT NOT NULL,taxacct TEXT NOT NULL,
            geometry_sha256 TEXT NOT NULL,status TEXT NOT NULL,owner TEXT,reported_owner TEXT,
            owner_as_of TEXT NOT NULL,record_sha256 TEXT NOT NULL,note TEXT NOT NULL,
            PRIMARY KEY(county,taxlot,taxacct)) WITHOUT ROWID;''')
        for k,v in {'county':'41047','source_data_as_of':stamp,'source':metadata['source'],'base_namespace':'marion-open'}.items():db.execute('INSERT INTO metadata VALUES (?,?)',(k,v))
        for manifest,p,key,geometry_hash in base_rows(archives):
            identity=('41047',p['TAXLOT'].strip(),p['TAXACCT'].strip())
            if identity in base_ids:raise ValueError('Duplicate stable identity across base packages')
            base_ids.add(identity);bounds.append(manifest['bbox'])
            candidates=list(grouped.get(identity,{}).values());used.add(identity)
            owner=reported=None;asof=stamp;note=''
            if not candidates:status='missing';note='No ownership record matched this county/taxlot/account'
            elif len(candidates)>1:status='conflict';note=f'{len(candidates)} conflicting source records; no owner assigned'
            else:
                record=candidates[0];reported=record['OWNERNAME'];asof=record['OWNER_AS_OF'];status='matched'
                if not reported:status='null';note='Source owner name is null or withheld'
                elif p.get('LASTUPDATE') and date(asof)<date(p['LASTUPDATE']):status='stale';note='Owner record predates the parcel LASTUPDATE; verify before relying on it'
                elif record['ACRES'] is not None and p.get('ACRES') is not None and abs(record['ACRES']-p['ACRES'])>1e-8:
                    status='parcel_changed';note='Acreage differs from the matched parcel snapshot'
                else:owner=reported
            hashes=sorted(grouped.get(identity,{}))
            record_hash=fingerprint({'identity':identity,'source_records':hashes,'geometry_sha256':geometry_hash,'status':status})
            db.execute('INSERT INTO owners VALUES (?,?,?,?,?,?,?,?,?,?)',(*identity,geometry_hash,status,owner,reported,asof,record_hash,note))
            counts[status]+=1;associations.append([*identity,key,status,record_hash])
        if not base_ids:raise ValueError('No county base parcels found')
        if db.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise ValueError('Owner database corrupt')
        db.execute('INSERT INTO metadata VALUES (?,?)',('parcel_count',str(len(base_ids))))
    unmatched=[list(k) for k in grouped if k not in used]
    report={'result':'PASS','base_parcels':len(base_ids),'source_rows':input_count,'source_unique_keys':len(grouped),
        'source_data_as_of':stamp,'source_age_days_at_build':(datetime.now(timezone.utc)-date(stamp)).days,
        'freshness_limit':'Matching cannot detect ownership transfers after the documented owner-source snapshot. Parcel LASTUPDATE is a GIS maintenance timestamp, not proof of the last ownership transfer.',
        'identical_duplicate_rows':input_count-sum(len(v) for v in grouped.values()),'status_counts':dict(counts),
        'unmatched_source_keys':len(unmatched),'associations_sha256':fingerprint(associations),
        'policy':'Exact county/TAXLOT/TAXACCT only. Conflicting, null, stale or changed records never populate OWNERNAME. OBJECTID is never a cross-source key.',
        'test_data':synthetic,'all_base_records_accounted_for':sum(counts.values())==len(base_ids),
        'base_packages':{Path(p).name:sha(p) for p in sorted(map(Path,archives))},
        'base_geometry_policy':'Unmodified. Attribute-only overlay binds each association to its exact installed polygon SHA256.'}
    (root/'owner-validation.json').write_text(json.dumps(report,indent=2))
    (root/'source-metadata.json').write_text(json.dumps({**metadata,'input_sha256':sha(export),'source_data_as_of':stamp},indent=2))
    # Full per-record audit stays private beside the package, not in public logs.
    Path(output).mkdir(parents=True,exist_ok=True)
    with (Path(output)/'owner-association-audit.csv').open('w',newline='') as f:
        writer=csv.writer(f);writer.writerow(['COUNTY','TAXLOT','TAXACCT','parcel_key','status','association_sha256']);writer.writerows(associations)
    (Path(output)/'owner-unmatched-source-keys.json').write_text(json.dumps(unmatched))
    bbox=[min(b[0] for b in bounds),min(b[1] for b in bounds),max(b[2] for b in bounds),max(b[3] for b in bounds)]
    finish(root,{'id':root.name,'name':('SYNTHETIC TEST ONLY ' if synthetic else '')+'Private Marion County ownership',
        'bbox':bbox,'revision':revision},'owners',owner_schema='oregon-owners-v1',county='41047',
        source_updated_at=stamp,source_data_as_of=stamp,source_namespace='marion-owner-overlay',
        identity_strategy='county-taxlot-taxacct-v1',base_namespace='marion-open',test_data=synthetic,
        authorization_reference=metadata['authorization_reference'],parcel_count=len(base_ids),owner_count=counts['matched'],
        attribution='Private ownership source: '+metadata['source']+'. As-of information; not a survey or permission to enter.')
    if (root/'owners.sqlite').stat().st_size>384*1024*1024 or sum(p.stat().st_size for p in root.iterdir())>512*1024*1024:
        raise ValueError('Owner overlay exceeds existing phone limits; split by geographic base regions')
    (Path(output)/'SHA256SUMS.txt').write_text(sha(root.with_suffix('.olepack'))+'  '+root.with_suffix('.olepack').name+'\n')
    return report


def synthetic_export(archives,path,metadata_path):
    """All actual county keys, unmistakably invented names. Never production data."""
    with Path(path).open('w',newline='') as f:
        writer=csv.DictWriter(f,fieldnames=FIELDS);writer.writeheader()
        for _,p,key,_ in base_rows(archives):writer.writerow({'COUNTY':'41047','TAXLOT':p['TAXLOT'],'TAXACCT':p['TAXACCT'],
            'OWNERNAME':'SYNTHETIC TEST ONLY '+p['TAXLOT']+' / '+p['TAXACCT'],'OWNER_AS_OF':'2026-11-01T00:00:00+00:00','ACRES':p.get('ACRES')})
    Path(metadata_path).write_text(json.dumps({'county':'41047','source':'synthetic://county-owner-test',
        'data_as_of':'2026-11-01T00:00:00+00:00','authorization_reference':'synthetic-only-no-real-owners','test_data':True}))


def main():
    p=argparse.ArgumentParser();p.add_argument('--base',type=Path,required=True);p.add_argument('--export',type=Path,required=True)
    p.add_argument('--metadata',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('output/private-owners'))
    p.add_argument('--revision',type=int,default=1);p.add_argument('--synthetic',action='store_true');p.add_argument('--generate-synthetic',action='store_true');p.add_argument('--table',default='owners')
    a=p.parse_args();archives=list(a.base.glob('*.olepack'))
    if a.generate_synthetic:synthetic_export(archives,a.export,a.metadata)
    report=build(archives,a.export,json.loads(a.metadata.read_text()),a.output,a.revision,a.synthetic,a.table)
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
