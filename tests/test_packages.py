import hashlib
import json
import zipfile
from pipeline.packages import split_baseline


def test_repack_preserves_original_payload_and_extraction_date(tmp_path):
    payload={name:b'synthetic payload' for name in ['aerial.pmtiles','parcels.geojson','source-record-hashes.json','source-metadata.json']}
    old={'bbox':[-122.91,45.10,-122.89,45.115],'minzoom':13,'maxzoom':17,
         'parcel_count':1,'tile_count':1,'owner_count':1,'selection_test':{'id':1},
         'attribution':'Synthetic test','parcel_source':'synthetic://parcels',
         'imagery_source':'synthetic://imagery','spatial_candidate_ids_outside_region':[],
         'extracted_at':'2020-01-01T00:00:00+00:00',
         'sha256':{name:hashlib.sha256(data).hexdigest() for name,data in payload.items()}}
    source=tmp_path/'original.olepack'
    with zipfile.ZipFile(source,'w') as z:
        z.writestr('manifest.json',json.dumps(old))
        for name,data in payload.items():z.writestr(name,data)
    split_baseline(source,tmp_path)
    for kind in ['parcels','aerial']:
        root=tmp_path/('gervais-original-'+kind)
        manifest=json.loads((root/'manifest.json').read_text())
        assert manifest['extracted_at']==old['extracted_at']
        assert manifest['packaged_at']!=old['extracted_at']
        for name in manifest['sha256']:assert (root/name).read_bytes()==payload[name]
