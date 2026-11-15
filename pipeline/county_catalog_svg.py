"""Portable coverage atlas built from the audited county and partition polygons."""
import html
import json
import sys
from pathlib import Path
from shapely.geometry import shape
from shapely.ops import transform


def build(root):
    root=Path(root);catalog=json.loads((root/'county-catalog.json').read_text())
    boundary=shape(json.loads((root/'county-boundary.json').read_text())['features'][0]['geometry'])
    w,s,e,n=boundary.bounds;scale=780/((e-w)*.7071)
    def project(x,y,z=None):return 24+(x-w)*.7071*scale,50+(n-y)*scale
    palette=['#80b1d3','#fdb462','#b3de69','#fccde5','#bc80bd','#8dd3c7','#bebada','#fb8072',
             '#ccebc5','#ffed6f','#a6cee3','#b2df8a','#cab2d6','#f4ae7b','#7ac8a6','#e3c76a']
    body=['<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="700" viewBox="0 0 1280 700">',
          '<rect width="1280" height="700" fill="#f8f7ef"/>',
          '<g font-family="sans-serif" fill="#173b2c"><text x="24" y="30" font-size="23">Marion County — offline package coverage</text></g>']
    for i,entry in enumerate(catalog['packages']):
        geometry=transform(project,shape(entry['coverage_geometry']))
        svg=geometry.svg(scale_factor=.6,fill_color=palette[i%len(palette)])
        body.append(f'<g><title>{html.escape(entry["id"])}: {entry["parcel_count"]:,} records</title>{svg}</g>')
        y=58+i*31
        body.append(f'<rect x="835" y="{y-12}" width="16" height="16" fill="{palette[i%len(palette)]}"/>')
        body.append(f'<text x="859" y="{y}" font-family="sans-serif" font-size="13">{html.escape(entry["id"])} · {entry["parcel_count"]:,}</text>')
    outline=transform(project,boundary).svg(scale_factor=.5,fill_color='none').replace('stroke="#555555"','stroke="#173b2c"')
    body.append(outline)
    for name,lon,lat in [('Salem',-123.035,44.942),('Gervais',-122.897,45.108),('Silverton',-122.783,45.005),
                         ('Silver Falls',-122.657,44.878),('Gates',-122.416,44.755),('Detroit',-122.151,44.735)]:
        x,y=project(lon,lat)
        body.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="3" fill="#173b2c"/><text x="{x+5:.2f}" y="{y-5:.2f}" font-family="sans-serif" font-size="13" fill="#173b2c" stroke="white" stroke-width="3" paint-order="stroke">{name}</text>')
    body.extend(['<g font-family="sans-serif" fill="#173b2c" font-size="15">',
        '<text x="24" y="610">Basemap: the complete outlined county plus a surrounding buffer. Public parcels: 115,870 records in 16 packages.</text>',
        '<text x="24" y="637">Colors show processing assignments, not property boundaries. Complete crossing parcels remain intact in their package.</text>',
        '<text x="24" y="664">Owner names withheld by the county public download. Missing assessor geometry does not establish ownership or access.</text>',
        '</g></svg>'])
    (root/'county-coverage.svg').write_text('\n'.join(body))


if __name__=='__main__':build(sys.argv[1])
