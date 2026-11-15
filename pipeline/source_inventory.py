"""Compare full taxlot/account inventories without requesting any owner attributes."""
import concurrent.futures
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from .county import request, ids, write, digest, OPEN, COUNTY, MIRROR


def inventory(source):
    wanted=ids(source);stamp=request(source).get('editingInfo')
    def batch(offset):
        part=wanted[offset:offset+500]
        result=request(source+'/query',objectIds=','.join(map(str,part)),returnGeometry='false',
                       outFields='OBJECTID,TAXLOT,TAXACCT,ACRES,LASTUPDATE,CITY')
        rows=[f['attributes'] for f in result.get('features',[])]
        returned=[r['OBJECTID'] for r in rows]
        assert not result.get('exceededTransferLimit') and len(returned)==len(set(returned)) and set(returned)==set(part)
        return rows
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        rows=[r for part in pool.map(batch,range(0,len(wanted),500)) for r in part]
    assert ids(source)==wanted and request(source).get('editingInfo')==stamp
    grouped=defaultdict(list)
    for r in rows: grouped[(r.get('TAXLOT'),r.get('TAXACCT'))].append(r)
    duplicates=[{'taxlot':k[0],'taxacct':k[1],'source_ids':[r['OBJECTID'] for r in v]} for k,v in grouped.items() if len(v)>1]
    missing=[r['OBJECTID'] for r in rows if not r.get('TAXLOT') or not r.get('TAXACCT')]
    return grouped,{'url':source,'count':len(rows),'source_editing_info':stamp,'unique_taxlot_accounts':len(grouped),
                    'duplicate_taxlot_accounts':duplicates,'missing_stable_field_ids':missing,'attributes_sha256':digest(sorted(rows,key=lambda r:r['OBJECTID']))}


def main():
    values={};reports={}
    for label,source in [('mirror',MIRROR),('county',COUNTY),('open_data',OPEN)]:
        print('Comparing allowlisted non-owner inventory: '+label,flush=True)
        values[label],reports[label]=inventory(source)
    comparisons={}
    for label in ['mirror','county']:
        old=values[label];new=values['open_data'];only_new=set(new)-set(old);only_old=set(old)-set(new);shared=set(new)&set(old)
        acres_changed=[];different_oids=0
        for key in shared:
            if len(old[key])!=1 or len(new[key])!=1: continue
            a=old[key][0];b=new[key][0]
            different_oids+=a['OBJECTID']!=b['OBJECTID']
            if a.get('ACRES')!=b.get('ACRES'): acres_changed.append({'taxlot':key[0],'taxacct':key[1],'old':a.get('ACRES'),'open_data':b.get('ACRES')})
        comparisons[label+'_vs_open_data']={'shared_stable_ids':len(shared),'shared_with_different_objectids':different_oids,
            'only_in_open_data':[list(k) for k in sorted(only_new,key=str)],'only_in_'+label:[list(k) for k in sorted(only_old,key=str)],
            'open_data_only_by_city':dict(Counter(str(new[k][0].get('CITY')) for k in only_new)),
            'acres_changed':acres_changed}
    out=Path('output/source-inventory')
    write(out/'county-source-inventory-comparison.json',{'observed_at':datetime.now(timezone.utc).isoformat(),
        'sources':reports,'comparisons':comparisons,'owner_data':'Not requested. OWNERNAME and addresses excluded from every query.',
        'limits':'Inventory differences do not establish geometry absence or legal access. County and open-data OBJECTIDs are compared only after a stable taxlot/account join.'})


if __name__=='__main__':main()
