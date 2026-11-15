"""Full-county outdoor PMTiles from bulk OSM; no imagery or public tile requests."""
import gzip
import json
import os
import subprocess
import urllib.request
from pathlib import Path
from collections import Counter

import mapbox_vector_tile
from pmtiles.reader import Reader, MmapSource, all_tiles
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid
from shapely.geometry import shape, box
from shapely.ops import transform
from shapely.prepared import prep

from .county import request, write, BOUNDARY
from .build_corridor import run, validate_vector
from .imagery import tile_list, tile_bounds
from .parcel_tiles import mercator
from .packages import finish, sha


def main():
    work=Path('work/county-vector');work.mkdir(parents=True,exist_ok=True)
    output=Path('output/county-basemap');root=output/'marion-outdoors';root.mkdir(parents=True,exist_ok=True)
    boundary=request(BOUNDARY+'/query',where='1=1',outFields='OBJECTID,NAME,STCOFIPS,COUNTYAREA,LASTUPDATE',outSR=4326,returnGeometry='true',f='geojson')
    county=shape(boundary['features'][0]['geometry']);assert county.is_valid
    write(output/'county-boundary.json',boundary)
    w,s,e,n=county.bounds; bounds=[w-.025,s-.025,e+.025,n+.025]
    config={'id':'marion-outdoors','name':'All Marion County outdoor basemap','bbox':bounds,'minzoom':8,'maxzoom':15,'revision':int(os.environ.get('OLE_DATA_REVISION','1')),
            'targets':json.loads(Path('regions/corridor.json').read_text())['targets']}
    url=os.environ.get('OLE_OSM_SOURCE','https://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf')
    allowed={'https://download.geofabrik.de/north-america/us/oregon-latest.osm.pbf':'Geofabrik Oregon',
             'https://download.openstreetmap.fr/extracts/north-america/us-west.osm.pbf':'OpenStreetMap France US West'}
    assert url in allowed, 'Choose an explicitly supported public OSM bulk provider'
    provider=allowed[url]
    source=work/'oregon.osm.pbf'
    if not source.exists():
        run('curl','-4','--fail','--location','--retry','4','--retry-all-errors','--connect-timeout','30','--max-time','600','--output',work/'oregon.download',url)
        run('curl','-4','--fail','--location','--retry','4','--retry-all-errors','--connect-timeout','30','--max-time','120','--output',work/'oregon.md5',url+'.md5')
        import hashlib
        expected=(work/'oregon.md5').read_text().split()[0]
        with (work/'oregon.download').open('rb') as downloaded: assert hashlib.file_digest(downloaded,'md5').hexdigest()==expected, 'Geofabrik bulk checksum mismatch; retry a consistent publication'
        (work/'oregon.download').replace(source)
    with (work/'source-info.json').open('w') as f: subprocess.run(['osmium','fileinfo','-e','-j',str(source)],stdout=f,check=True)
    run('osmium','extract','-b',','.join(map(str,[bounds[0]-.04,bounds[1]-.04,bounds[2]+.04,bounds[3]+.04])),
        '-s','smart',source,'-o',work/'extract.osm.pbf','--overwrite')
    tileconfig=json.loads(Path('pipeline/vector/config.json').read_text());tileconfig['settings']['bounding_box']=bounds
    write(work/'config.json',tileconfig)
    run('tilemaker','--input',work/'extract.osm.pbf','--output',work/'raw.pmtiles','--config',work/'config.json',
        '--process','pipeline/vector/process.lua','--bbox',','.join(map(str,bounds)))
    # Explicit empty vector tiles represent land without mapped features. They
    # distinguish source emptiness from a missing/offline tile in completeness checks.
    empty=gzip.compress(mapbox_vector_tile.encode([{'name':'land','features':[]}]),mtime=0)
    empties=0; projected=prep(transform(mercator,county));required=0; classes=Counter()
    with (work/'raw.pmtiles').open('rb') as f, (root/'basemap.pmtiles').open('wb') as dest:
        reader=Reader(MmapSource(f));writer=Writer(dest)
        for z,x,y in sorted(tile_list(bounds,8,15),key=lambda t:zxy_to_tileid(*t)):
            blob=reader.get(z,x,y)
            if not blob: blob=empty;empties+=1
            writer.write_tile(zxy_to_tileid(z,x,y),blob)
            if projected.intersects(box(*tile_bounds(z,x,y))): required+=1
        writer.finalize(reader.header(),reader.metadata())
    report=validate_vector(root/'basemap.pmtiles',config,allow_empty=True)
    with (root/'basemap.pmtiles').open('rb') as f:
        reader=Reader(MmapSource(f))
        for z,x,y in tile_list(county.bounds,8,15):
            if projected.intersects(box(*tile_bounds(z,x,y))): assert reader.get(z,x,y), (z,x,y)
        for (z,x,y),blob in all_tiles(MmapSource(f)):
            if z==15:
                for name,layer in mapbox_vector_tile.decode(gzip.decompress(blob)).items():
                    for feature in layer['features']:classes[name+':'+feature['properties'].get('class','')]+=1
    report.update(county_boundary_source=BOUNDARY,county_bbox=list(county.bounds),coverage_bbox=bounds,
                  published_county_area_square_miles=boundary['features'][0]['properties']['COUNTYAREA'],
                  county_intersecting_tiles_verified=required,explicit_empty_tiles=empties,
                  maxzoom_feature_classes_with_edge_duplicates=dict(classes),
                  missing_roads_or_trails='OSM omissions are not comprehensively established. Target name audit included; mapped access tags do not establish legal access.')
    write(output/'county-vector-validation.json',report)
    write(root/'source-metadata.json',{'url':url,'sha256':sha(source),'osmium':json.loads((work/'source-info.json').read_text()),
        'tool':'Tilemaker v3.2.0','schema':'oregon-outdoor-v1','license':'https://opendatacommons.org/licenses/odbl/1-0/',
        'boundary_source':BOUNDARY,'boundary_sha256':sha(output/'county-boundary.json')})
    finish(root,config,'vector',tile_count=report['tiles'],tile_schema='oregon-outdoor-v1',
        attribution='© OpenStreetMap contributors • ODbL 1.0 • '+provider+' bulk extract; county boundary Marion County IT-GIS')
    write(output/'basemap-storage.json',{'package_bytes':(output/'marion-outdoors.olepack').stat().st_size,'pmtiles_bytes':(root/'basemap.pmtiles').stat().st_size})


if __name__=='__main__':main()
