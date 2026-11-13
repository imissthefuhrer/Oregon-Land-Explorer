import io
import math
from PIL import Image, ImageStat
from pmtiles.writer import Writer
from pmtiles.tile import zxy_to_tileid, TileType, Compression
from .http import fetch

SOURCE = 'https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer'
HALF_WORLD = math.pi * 6378137


def tile_xy(lon, lat, z):
    n = 2 ** z
    return (int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n))


def tile_bounds(z, x, y):
    step = 2 * HALF_WORLD / 2 ** z
    return (-HALF_WORLD + x * step, HALF_WORLD - (y + 1) * step,
            -HALF_WORLD + (x + 1) * step, HALF_WORLD - y * step)


def tile_list(bounds, low, high):
    west, south, east, north = bounds
    for z in range(low, high + 1):
        left, top = tile_xy(west, north, z)
        right, bottom = tile_xy(east, south, z)
        for x in range(left, right + 1):
            for y in range(top, bottom + 1):
                yield z, x, y


def validate_jpeg(data):
    if not data.startswith(b'\xff\xd8\xff'):
        raise ValueError('Imagery service did not return a JPEG')
    with Image.open(io.BytesIO(data)) as im:
        im.load()
        if im.size != (256, 256) or max(ImageStat.Stat(im.convert('RGB')).stddev) < 2:
            raise ValueError('Blank, corrupt, or incorrectly sized aerial tile')


def build(path, config):
    tiles = list(tile_list(config['bbox'], config['minzoom'], config['maxzoom']))
    if len(tiles) > config['max_tiles']:
        raise ValueError(f'{len(tiles)} tiles exceeds safety limit')
    with open(path, 'wb') as output:
        writer = Writer(output)
        for i, (z, x, y) in enumerate(sorted(tiles, key=lambda t: zxy_to_tileid(*t))):
            # Use PR #2's proven exportImage path. Never reinterpret OSIP's nonstandard cache as XYZ.
            data = fetch(SOURCE + '/exportImage', {'bbox': ','.join(map(str, tile_bounds(z,x,y))),
                'bboxSR': 3857, 'imageSR': 3857, 'size': '256,256', 'format': 'jpg', 'f': 'image',
                'adjustAspectRatio': 'false'})
            validate_jpeg(data)
            writer.write_tile(zxy_to_tileid(z,x,y), data)
            if i % 25 == 0:
                print(f'Validated imagery {i+1}/{len(tiles)}', flush=True)
        west, south, east, north = config['bbox']
        writer.finalize({'tile_type': TileType.JPEG, 'tile_compression': Compression.NONE,
            'min_zoom': config['minzoom'], 'max_zoom': config['maxzoom'],
            'min_lon_e7': round(west*1e7), 'min_lat_e7': round(south*1e7),
            'max_lon_e7': round(east*1e7), 'max_lat_e7': round(north*1e7),
            'center_zoom': 15, 'center_lon_e7': round((west+east)*5e6), 'center_lat_e7': round((south+north)*5e6)},
            {'name': config['name'], 'format': 'jpg', 'attribution': 'Oregon Statewide Imagery Program (OSIP), 2024'})
    return len(tiles)
