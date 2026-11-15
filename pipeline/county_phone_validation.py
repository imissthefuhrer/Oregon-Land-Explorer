"""Final packaged-ID reconciliation and actual place-specific Android selection fixtures."""
import argparse
import json
import math
import shutil
import sqlite3
import tempfile
import zipfile
from pathlib import Path
from shapely.geometry import shape, box, Point
from .county import digest, write
from .county_delivery import verify_attributes

TARGETS=[('Salem',-123.035,44.942),('Gervais',-122.897,45.108),('Silverton',-122.783,45.005),
         ('Silver Falls',-122.657,44.878),('Gates',-122.416,44.755),('Detroit',-122.151,44.735),('Eastern forest',-121.93,44.85)]


def main():
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,default=Path('work/county-delivery'));a=p.parse_args()
    catalog=json.loads((a.source/'county-catalog.json').read_text());reconciliation=json.loads((a.source/'county-reconciliation.json').read_text())
    attributes={};selected={}
    for entry in catalog['packages']:
        with zipfile.ZipFile(a.source/entry['file']) as z:
            manifest=json.loads(z.read('manifest.json'))
            part=verify_attributes(z,manifest)
            assert not set(attributes)&set(part), 'Duplicate source identities across delivered packages'
            attributes.update(part)
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'parcels.sqlite'
                with z.open('parcels.sqlite') as input,path.open('wb') as output:shutil.copyfileobj(input,output)
                with sqlite3.connect(f'file:{path}?mode=ro',uri=True) as db:
                    for name,lon,lat in TARGETS:
                        window=box(lon-.002,lat-.002,lon+.002,lat+.002);anchor=Point(lon,lat)
                        cells=[math.floor(v*100) for v in (lon-.002,lon+.002,lat-.002,lat+.002)]
                        rows=db.execute('SELECT id,parcel_key,taxlot,geometry FROM parcels WHERE id IN (SELECT parcel_id FROM parcel_cells WHERE cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?)',cells)
                        for oid,key,taxlot,raw in rows:
                            g=shape(json.loads(raw));intersection=g.intersection(window)
                            if intersection.is_empty or intersection.area==0:continue
                            distance=g.distance(anchor)
                            if name in selected and selected[name]['distance_degrees']<=distance:continue
                            point=anchor if g.covers(anchor) else intersection.representative_point()
                            assert g.covers(point)
                            selected[name]={'name':name,'package':entry['id'],'object_id':oid,'parcel_key':key,
                                'taxlot':taxlot,'latitude':point.y,'longitude':point.x,'distance_degrees':distance,
                                'description':'Actual source polygon at or within 0.002 degrees of public place reference; not a home coordinate or legal-access assertion.'}
    assert len(selected)==len(TARGETS), f'Missing actual selection fixtures: {set(t[0] for t in TARGETS)-set(selected)}'
    actual=sorted(map(int,attributes));outside=reconciliation['outside_official_county_ids']
    assert len(actual)==reconciliation['intersecting_official_county']==reconciliation['packaged_unique_ids']
    assert not set(actual)&set(outside)
    assert digest(sorted(actual+outside))==reconciliation['source_ids_sha256'], 'Final packaged ID set does not reconcile to complete source inventory'
    if not outside:assert digest(attributes)==reconciliation['attribute_fingerprints_sha256']
    catalog['selection_targets']=[selected[name] for name,_,_ in TARGETS]
    write(a.source/'county-catalog.json',catalog)
    write(a.source/'county-final-reconciliation.json',{'result':'PASS','actual_packaged_unique_ids':len(actual),
        'missing_ids':[],'duplicate_ids':[],'outside_county_source_ids':outside,
        'source_id_digest':'PASS','source_attributes_including_account_and_timestamp':'PASS','owner_values':'ABSENT',
        'actual_place_selection_targets':catalog['selection_targets']})


if __name__=='__main__':main()
