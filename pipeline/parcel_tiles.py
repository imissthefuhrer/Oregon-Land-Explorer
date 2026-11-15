"""Schema 3: identity-only MVT display plus exact, indexed SQLite selection.

The preparation host may hold a bounded extract. Android never needs to do so.
No tile-area clipping is used for selection; source/display fingerprints remain
bound to unquantized geometry and attributes in SQLite and the original audit.
"""
import gzip
import hashlib
import json
import math
import sqlite3
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

import mapbox_vector_tile
from pmtiles.reader import MmapSource, all_tiles
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid, TileType, Compression
from shapely.geometry import shape, mapping, box
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
from shapely import set_precision

from .imagery import HALF_WORLD, tile_list, tile_bounds
from .parcels import fingerprint

COUNTY = '41047'  # Census FIPS, Marion County, Oregon; never infer from an owner name.
LOW, HIGH, EXTENT = 12, 16, 8192
CELL_SCALE = 100  # 0.01-degree cells, ordinary SQLite indexes on every Android version
APPLICATION_ID = 0x4F4C4550


def compact(value):
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False)


def source_identity(props, manifest):
    if manifest.get('identity_strategy') == 'taxlot-taxacct-sha256-v1':
        assert manifest['source_namespace'] in ('marion-open', 'marion-county-owner')
        assert props.get('TAXLOT') and props.get('TAXACCT')
        value = json.dumps([props['TAXLOT'],props['TAXACCT']], sort_keys=True, separators=(',', ':'))
        return manifest['source_namespace']+':'+hashlib.sha256(value.encode()).hexdigest()
    assert not manifest.get('identity_strategy') and not manifest.get('source_namespace')
    return str(props['OBJECTID'])


def mercator(x, y, z=None):
    # shapely.ops.transform retries scalar arguments if sequence conversion fails.
    return x * HALF_WORLD / 180, math.asinh(math.tan(math.radians(y))) * HALF_WORLD / math.pi


def build_database(path, data, audit, manifest):
    """One row per compound identity. All retained attributes are available on tap."""
    ids = [str(f['properties']['OBJECTID']) for f in data['features']]
    if len(ids) != len(set(ids)) or set(ids) != set(audit):
        raise ValueError('Missing, duplicated or mismatched source parcel IDs')
    with sqlite3.connect(path) as db:
        db.executescript(f'''
            PRAGMA application_id={APPLICATION_ID}; PRAGMA user_version=2;
            CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE parcels(
              id INTEGER PRIMARY KEY, county TEXT NOT NULL, source_id TEXT NOT NULL,
              parcel_key TEXT NOT NULL UNIQUE, taxlot TEXT, owner TEXT, acres REAL,
              properties TEXT NOT NULL, geometry TEXT NOT NULL, audit TEXT NOT NULL,
              UNIQUE(county, source_id));
            CREATE TABLE parcel_bounds(id INTEGER PRIMARY KEY, west REAL NOT NULL,
              east REAL NOT NULL, south REAL NOT NULL, north REAL NOT NULL);
            CREATE TABLE parcel_cells(cell_x INTEGER,cell_y INTEGER,parcel_id INTEGER,
              PRIMARY KEY(cell_x,cell_y,parcel_id)) WITHOUT ROWID;
            CREATE INDEX parcel_cell_id ON parcel_cells(parcel_id,cell_x,cell_y);
        ''')
        for key, value in {'county': COUNTY, 'extracted_at': manifest['extracted_at'],
                           'parcel_source': manifest['parcel_source'], 'cell_scale': str(CELL_SCALE)}.items():
            db.execute('INSERT INTO metadata VALUES (?,?)', (key, value))
        for feature in data['features']:
            props = feature['properties']; source_id = str(props['OBJECTID'])
            geom = shape(feature['geometry']); record = audit[source_id]
            expected = record['display_sha256'] if isinstance(record, dict) else record
            if geom.is_empty or not geom.is_valid or geom.geom_type not in ('Polygon', 'MultiPolygon') or fingerprint(feature) != expected:
                raise ValueError(f'Invalid geometry/fingerprint: {source_id}')
            geometry = compact(feature['geometry'])
            if len(geometry.encode()) > 8 * 1024 * 1024:
                raise ValueError(f'Parcel exceeds single-feature decode budget: {source_id}')
            db.execute('INSERT INTO parcels VALUES (?,?,?,?,?,?,?,?,?,?)',
                       (int(source_id), COUNTY, source_identity(props,manifest), COUNTY + ':' + source_identity(props,manifest),
                        props.get('TAXLOT'), props.get('OWNERNAME'), props.get('ACRES'),
                        compact(props), geometry, compact(record)))
            w, s, e, n = geom.bounds
            db.execute('INSERT INTO parcel_bounds VALUES (?,?,?,?,?)', (int(source_id), w, e, s, n))
            xs=range(math.floor(w*CELL_SCALE),math.floor(e*CELL_SCALE)+1)
            ys=range(math.floor(s*CELL_SCALE),math.floor(n*CELL_SCALE)+1)
            if len(xs)*len(ys)>50000:
                raise ValueError(f'Parcel exceeds spatial-cell budget: {source_id}')
            db.executemany('INSERT INTO parcel_cells VALUES (?,?,?)',
                           ((x,y,int(source_id)) for x in xs for y in ys))
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

        assert db.execute('SELECT count(*) FROM parcel_bounds').fetchone()[0] == len(ids)


def build_tiles(path, data, manifest):
    geometries = [transform(mercator, shape(f['geometry'])) for f in data['features']]
    tree = STRtree(geometries)
    # Include complete source-selected geometries, including reported outside candidates.
    geographic = [shape(f['geometry']).bounds for f in data['features']]
    bounds = [min(g[0] for g in geographic), min(g[1] for g in geographic),
              max(g[2] for g in geographic), max(g[3] for g in geographic)]
    tiles = sorted(tile_list(bounds, LOW, HIGH), key=lambda t: zxy_to_tileid(*t))
    if len(tiles) > 100000:
        raise ValueError('Tile safety limit exceeded; partition the region explicitly')
    count = 0
    with path.open('wb') as output:
        writer = Writer(output)
        for z, x, y in tiles:
            tb = tile_bounds(z, x, y); width = tb[2] - tb[0]
            clip = box(*tb).buffer(width * 8 / 512, join_style=2)
            fills, lines = [], []
            for index in tree.query(clip, predicate='intersects'):
                f = data['features'][int(index)]
                # Pixel-scale simplification only at overview zooms; unsimplified at 16.
                g = geometries[int(index)]
                if z < HIGH:
                    g = g.simplify(width / (512 * 2), preserve_topology=True)
                # Explicit render-grid quantization maintains valid rings. Subpixel
                # components can collapse; exact geometry/holes remain in SQLite.
                # Every source identity must still survive at HIGH (validated below).
                polygon = set_precision(g.intersection(clip), width / EXTENT)
                line = set_precision(g.boundary.intersection(clip), width / EXTENT)
                props = {'parcel_key': COUNTY + ':' + source_identity(f['properties'],manifest)}
                if not polygon.is_empty and polygon.geom_type in ('Polygon', 'MultiPolygon'):
                    fills.append({'geometry': polygon, 'properties': props, 'id': f['properties']['OBJECTID']})
                if not line.is_empty and line.geom_type in ('LineString', 'MultiLineString'):
                    lines.append({'geometry': line, 'properties': props, 'id': f['properties']['OBJECTID']})
            if not fills:
                continue
            blob = mapbox_vector_tile.encode([{'name': 'parcels', 'features': fills},
                                              {'name': 'boundaries', 'features': lines}],
                                             default_options={'quantize_bounds': tb, 'extents': EXTENT})
            writer.write_tile(zxy_to_tileid(z, x, y), gzip.compress(blob, mtime=0))
            count += 1
        w, s, e, n = bounds
        writer.finalize({'tile_type': TileType.MVT, 'tile_compression': Compression.GZIP,
                         'min_zoom': LOW, 'max_zoom': HIGH,
                         'min_lon_e7': round(w*1e7), 'min_lat_e7': round(s*1e7),
                         'max_lon_e7': round(e*1e7), 'max_lat_e7': round(n*1e7),
                         'center_zoom': 14, 'center_lon_e7': round((w+e)*5e6), 'center_lat_e7': round((s+n)*5e6)},
                        {'name': manifest['name'], 'format': 'pbf',
                         'vector_layers': [{'id': 'parcels', 'fields': {'parcel_key': 'String'}},
                                           {'id': 'boundaries', 'fields': {'parcel_key': 'String'}}],
                         'attribution': manifest['attribution']})
    return count


def validate_optimized(root, manifest):
    """Decode every tile; reconcile exact rows, audit associations and maxzoom IDs."""
    with sqlite3.connect(f'file:{root / "parcels.sqlite"}?mode=ro', uri=True) as db:
        assert db.execute('PRAGMA application_id').fetchone()[0] == APPLICATION_ID
        assert db.execute('PRAGMA user_version').fetchone()[0] == 2
        assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'

        keys = set(); exact = {}; total_cells = 0
        attributes=None
        if manifest.get('identity_strategy'):
            attributes=json.loads((root/'source-metadata.json').read_text())['attribute_fingerprints']
        audit = json.loads((root/'source-record-hashes.json').read_text())
        for id, county, sid, key, props, geometry, record in db.execute('SELECT id,county,source_id,parcel_key,properties,geometry,audit FROM parcels'):
            f = {'geometry': json.loads(geometry), 'properties': json.loads(props)}
            assert county == COUNTY and str(id) == str(f['properties']['OBJECTID']) and sid == source_identity(f['properties'],manifest) and key == county + ':' + sid
            if attributes is not None:
                fields=['OBJECTID','TAXLOT','TAXACCT','ACRES','LASTUPDATE']
                if manifest['source_namespace']=='marion-county-owner':fields.append('OWNERNAME')
                source_attributes={k:f['properties'].get(k) for k in fields}
                assert hashlib.sha256(json.dumps(source_attributes,sort_keys=True,separators=(',', ':')).encode()).hexdigest()==attributes[str(id)], ('source attribute mismatch',id)
            record = json.loads(record)
            assert record == audit[str(id)]
            assert fingerprint(f) == (record['display_sha256'] if isinstance(record, dict) else record)
            g = shape(f['geometry']); assert g.is_valid and not g.is_empty
            if isinstance(record, dict):
                assert fingerprint({**f, 'geometry': record['source_geometry']}) == record['source_sha256']
                assert g.boundary.difference(shape(record['source_geometry']).boundary.buffer(1e-7)).length <= 1e-7
            b = db.execute('SELECT west,south,east,north FROM parcel_bounds WHERE id=?', (id,)).fetchone()
            w,s,e,n = g.bounds
            assert b and b[0] <= w and b[1] <= s and b[2] >= e and b[3] >= n
            expected_cells=(math.floor(e*CELL_SCALE)-math.floor(w*CELL_SCALE)+1)*(math.floor(n*CELL_SCALE)-math.floor(s*CELL_SCALE)+1)
            cells=db.execute('SELECT count(*) FROM parcel_cells WHERE parcel_id=? AND cell_x BETWEEN ? AND ? AND cell_y BETWEEN ? AND ?',
                             (id,math.floor(w*CELL_SCALE),math.floor(e*CELL_SCALE),math.floor(s*CELL_SCALE),math.floor(n*CELL_SCALE))).fetchone()[0]
            assert cells==expected_cells, ('missing spatial cells',key)
            total_cells += expected_cells
            keys.add(key)
            exact[key] = transform(mercator, g)
        assert len(keys) == manifest['parcel_count'] == len(audit)
        assert db.execute('SELECT count(*) FROM parcel_bounds').fetchone()[0] == len(keys)
        assert db.execute('SELECT count(*) FROM parcel_cells').fetchone()[0] == total_cells
    seen = set(); count = 0; max_bytes = 0; pieces = defaultdict(list)
    with (root/'parcels.pmtiles').open('rb') as file:
        for (z,x,y), blob in all_tiles(MmapSource(file)):
            raw = gzip.decompress(blob); max_bytes = max(max_bytes, len(raw))
            assert len(raw) <= 4*1024*1024, f'Tile decode budget exceeded: {z}/{x}/{y}'
            layers = mapbox_vector_tile.decode(raw)
            assert set(layers) == {'parcels', 'boundaries'}
            for layer, decoded in layers.items():
                for feature in decoded['features']:
                    key = feature['properties']['parcel_key']
                    assert key in keys and set(feature['properties']) == {'parcel_key'}
                    g = shape(feature['geometry'])
                    assert not g.is_empty and g.is_valid, (z,x,y,key)
                    if layer == 'parcels' and z == HIGH:
                        seen.add(key)
                        w,s,e,n = tile_bounds(z,x,y)
                        unit = (e-w)/EXTENT
                        pieces[key].append(transform(lambda a,b,z=None: (w+a*unit,s+b*unit), g))
            count += 1
    assert seen == keys, f'Missing maxzoom parcel identities: {sorted(keys-seen)}'
    # Reassemble maxzoom pieces independently, including holes and disjoint parts.
    # Grid rounding can remove narrow spikes AND fill narrow incisions. A plain
    # Hausdorff test rejects the first; eroded interiors alone reject the second.
    # Compare each eroded interior against the other's dilation instead. This
    # explicitly tolerates subpixel topology but rejects missing/invented areas
    # wider than two grid units, including separate parts and holes.
    tolerance = 2*HALF_WORLD/(2**HIGH*EXTENT)  # one grid unit in projected meters
    subpixel_changes = 0; max_subpixel_area = 0.0
    for key, geometry in exact.items():
        rendered = unary_union(pieces[key])
        assert rendered.is_valid and not rendered.is_empty, key
        missing = geometry.buffer(-tolerance).difference(rendered.buffer(tolerance)).area
        invented = rendered.buffer(-tolerance).difference(geometry.buffer(tolerance)).area
        assert missing < 1e-5 and invented < 1e-5, ('tile geometry outside render-grid budget',key,missing,invented)
        delta = geometry.symmetric_difference(rendered).area
        assert delta <= tolerance*(geometry.length+rendered.length), ('excessive render-grid area change',key,delta)
        # Report subpixel slivers beyond a simple boundary-distance check.
        subpixel_area = geometry.difference(rendered.buffer(tolerance)).area + rendered.difference(geometry.buffer(tolerance)).area
        if subpixel_area > 1e-5:
            subpixel_changes += 1; max_subpixel_area = max(max_subpixel_area, subpixel_area)
    return {'tiles': count, 'max_uncompressed_tile_bytes': max_bytes, 'parcel_count': len(keys),
            'sqlite_integrity': 'PASS', 'fingerprints': 'PASS', 'tile_identities': 'PASS',
            'reassembled_maxzoom_geometry': 'PASS', 'maxzoom_grid_meters_web_mercator': tolerance,
            'subpixel_sliver_parcel_count': subpixel_changes,
            'max_subpixel_sliver_area_m2_web_mercator': max_subpixel_area,
            'overview_simplification': 'half a 512-pixel tile pixel; topology preserving; none at zoom 16',
            'subpixel_geometry': 'Render-grid rounding can collapse subpixel parts. All unquantized parts/holes remain in SQLite; all parcel identities must survive at maximum zoom.'}


def optimize(root, revision=None):
    """Convert an audited schema-2 directory; never refetch or change source dates."""
    from .packages import finish
    root = Path(root)
    manifest = json.loads((root/'manifest.json').read_text())
    data = json.loads((root/'parcels.geojson').read_text())
    audit = json.loads((root/'source-record-hashes.json').read_text())
    build_database(root/'parcels.sqlite', data, audit, manifest)
    build_tiles(root/'parcels.pmtiles', data, manifest)
    report = validate_optimized(root, manifest)
    (root/'parcel-validation.json').write_text(json.dumps(report, indent=2))
    # The exact geometry is now in SQLite; the source archive is retained separately.
    (root/'parcels.geojson').unlink()
    config = {k:v for k,v in manifest.items() if k not in ('sha256', 'schema_version', 'kind')}
    config.update(revision=revision if revision is not None else manifest['revision']+1, minzoom=LOW, maxzoom=HIGH,
                  packaged_at=datetime.now(timezone.utc).isoformat(),
                  parcel_schema='oregon-parcels-v2', county=COUNTY)
    return finish(root, config, 'parcels', schema_version=3)
