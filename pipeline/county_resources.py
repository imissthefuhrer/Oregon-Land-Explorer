"""Hosted glyph audit near actual benchmark places; no tile-server requests."""
import gzip
import json
import tempfile
import zipfile
from pathlib import Path
import mapbox_vector_tile
from pmtiles.reader import Reader,MmapSource
from .county_phone_validation import TARGETS
from .imagery import tile_list
from .county import write


def main():
    results=[];ranges=set()
    with tempfile.TemporaryDirectory() as directory:
        path=Path(directory)/'basemap.pmtiles'
        with zipfile.ZipFile('work/county-delivery/marion-outdoors.olepack') as z:path.write_bytes(z.read('basemap.pmtiles'))
        with path.open('rb') as f:
            reader=Reader(MmapSource(f))
            for name,lon,lat in TARGETS:
                needed=set();labels=set();missing=[]
                for z,x,y in tile_list([lon-.02,lat-.02,lon+.02,lat+.02],14,15):
                    blob=reader.get(z,x,y)
                    if blob is None:missing.append([z,x,y]);continue
                    decoded=mapbox_vector_tile.decode(gzip.decompress(blob))
                    for layer in decoded.values():
                        for feature in layer['features']:
                            props=feature['properties']
                            for field in ['name','ref']:
                                label=str(props.get(field,''));labels.add(label)
                                needed.update(ord(c)//256*256 for c in label)
                ranges.update(needed)
                bundled={0,256,512,768,1024,1280,8192}
                extra=needed-bundled
                results.append({'place':name,'missing_tiles':missing,'glyph_ranges':sorted(needed),
                    'not_in_original_apk':sorted(extra),'labels_requiring_extra_ranges':sorted(t for t in labels if any(ord(c)//256*256 in extra for c in t))})
    write('output/source-inventory/county-resource-audit.json',{'places':results,'all_needed_ranges':sorted(ranges)})


if __name__=='__main__':main()
