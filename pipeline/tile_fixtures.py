"""Independent PMTiles address/body oracles for the local Android transport.

Random payloads below are reader fixtures only, never property records or maps.
"""
import gzip
import hashlib
import json
import random
import shutil
from pathlib import Path
from pmtiles.reader import Reader, MmapSource, all_tiles
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid, tileid_to_zxy, TileType, Compression


def build(output=Path('output')):
    assets=Path('android/app/src/androidTest/assets/tile-fixtures');assets.mkdir(parents=True,exist_ok=True)
    rng=random.Random(1920)
    addresses=[]
    for z in range(19):
        for x,y in [(0,0),(2**z-1,0),(0,2**z-1),(2**z-1,2**z-1)]+[(rng.randrange(2**z),rng.randrange(2**z)) for _ in range(20)]:
            addresses.append([z,x,y,zxy_to_tileid(z,x,y)])
    bodies=[]
    for id,name in [('willamette-silver-outdoors','basemap.pmtiles'),('gervais-original-parcels','parcels.pmtiles')]:
        with (output/id/name).open('rb') as f:
            source=MmapSource(f);reader=Reader(source)
            for i,((z,x,y),blob) in enumerate(all_tiles(source)):
                if i%101==0:
                    raw=gzip.decompress(blob) if blob[:2]==b'\x1f\x8b' else blob
                    bodies.append({'pack':id,'payload':name,'z':z,'x':x,'y':y,'sha256':hashlib.sha256(raw).hexdigest()})
    # Incompressible directory deltas/lengths force a genuine leaf directory.
    synthetic=assets/'synthetic-reader.pmtiles';ids=[];payloads=[];tile_id=zxy_to_tileid(18,0,0)
    with synthetic.open('wb') as out:
        writer=Writer(out)
        for i in range(16000):
            tile_id+=rng.randrange(1,10000)
            payload=rng.randbytes(rng.randrange(10,300))
            writer.write_tile(tile_id,payload)
            if i%173==0:ids.append(tile_id);payloads.append(payload)
        writer.finalize({'tile_type':TileType.MVT,'tile_compression':Compression.NONE,
                         'min_zoom':18,'max_zoom':18,'min_lon_e7':-1800000000,'min_lat_e7':-850000000,
                         'max_lon_e7':1800000000,'max_lat_e7':850000000,'center_zoom':18,'center_lon_e7':0,'center_lat_e7':0},
                        {'name':'Synthetic binary reader test, not map or ownership data'})
    with synthetic.open('rb') as f:
        reader=Reader(MmapSource(f));assert reader.header()['leaf_directory_length']>0
    nested=[{'z':z,'x':x,'y':y,'sha256':hashlib.sha256(blob).hexdigest()}
            for id,blob in zip(ids,payloads) for z,x,y in [tileid_to_zxy(id)]]
    (assets/'oracles.json').write_text(json.dumps({'addresses':addresses,'bodies':bodies,'nested':nested}))


if __name__=='__main__':build()
