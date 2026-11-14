"""Configuration contracts keep named destination grounds within both map/detail extents."""
import json
from pathlib import Path
from shapely.geometry import box


def test_mandatory_destination_areas_have_base_and_parcel_packages():
    c=json.loads(Path('regions/corridor.json').read_text())
    base=box(*c['bbox'])
    parcels=[box(*r['bbox']) for r in c['parcel_regions']]
    names={t['name'] for t in c['targets']}
    assert {'St. Louis Ponds','Willamette Mission State Park','Silver Falls State Park'}<=names
    for target in c['targets']:
        area=box(*target['bbox'])
        assert base.covers(area),target['name']
        assert any(p.covers(area) for p in parcels),target['name']
    assert c['maxzoom']>=15
    # Broad countryside, not a town-centre marker or the tiny original 0.02° x 0.015°.
    assert c['bbox'][2]-c['bbox'][0]>=0.5 and c['bbox'][3]-c['bbox'][1]>=0.4


def test_style_has_local_text_and_outdoor_layers():
    s=json.loads(Path('android/app/src/main/assets/outdoor-style.json').read_text())
    assert s['glyphs'].startswith('asset://') and 'sprite' not in s
    layers={layer['id']:layer for layer in s['layers']}
    for name in ['roads','tracks','trails','streams','water','land','place-labels','park-labels','road-labels']:
        assert name in layers
    assert layers['trails']['minzoom']<=12
