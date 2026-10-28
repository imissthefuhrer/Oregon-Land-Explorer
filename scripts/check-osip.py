"""Check real OSIP metadata/tiles; preserve HTTP and ArcGIS errors as artifacts."""
import io
import json
import math
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image, ImageStat

SERVICE = 'https://imagery.oregonexplorer.info/arcgis/rest/services/OSIP_2024/OSIP_2024_WM/ImageServer'
out = Path('work/service-checks')
out.mkdir(parents=True, exist_ok=True)
report = {'service': SERVICE, 'requests': [], 'failures': []}


def request(label, url):
    result = {'label': label, 'url': url}
    report['requests'].append(result)
    req = urllib.request.Request(url, headers={
        'User-Agent': 'OregonLandExplorer/0.1 imagery diagnostics',
        'Origin': 'https://example.com',
    })
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            result.update(status=response.status, content_type=response.headers.get('Content-Type'),
                          cors=response.headers.get('Access-Control-Allow-Origin'))
            body = response.read()
    except urllib.error.HTTPError as error:
        body = error.read()
        result.update(status=error.code, error=str(error), body=body[:2000].decode('utf-8', errors='replace'))
        print(json.dumps(result), flush=True)
        return result, None
    except Exception as error:
        result['error'] = str(error)
        print(json.dumps(result), flush=True)
        return result, None
    result['bytes'] = len(body)
    if 'json' in (result.get('content_type') or '') or body.lstrip().startswith(b'{'):
        try:
            data = json.loads(body)
            if 'error' in data:
                result['arcgis_error'] = data['error']
        except (ValueError, TypeError) as error:
            result['json_error'] = str(error)
    print(json.dumps(result), flush=True)
    (out / (label + '.response')).write_bytes(body)
    return result, body


def coordinates(value):
    if isinstance(value[0], (int, float)):
        yield value
    else:
        for item in value:
            yield from coordinates(item)


def check():
    expected = json.loads(Path('docs/osip-2024-tile-scheme.json').read_text())
    status, body = request('metadata', SERVICE + '?f=pjson')
    if not body or status.get('arcgis_error'):
        raise RuntimeError('Metadata request failed; see HTTP/ArcGIS/network error above.')
    metadata = json.loads(body)
    info = metadata['tileInfo']
    report['tileInfo'] = info
    if not metadata.get('singleFusedMapCache'):
        raise RuntimeError('Service no longer advertises a fused tile cache.')
    for key in ('origin', 'rows', 'cols', 'lods', 'spatialReference'):
        if info[key] != expected['tileInfo'][key]:
            raise RuntimeError('Published tile grid changed: ' + key)

    data = json.loads(Path(sys.argv[1] if len(sys.argv) > 1 else 'preview/parcels.geojson').read_text())
    points = [p for feature in data['features'] for p in coordinates(feature['geometry']['coordinates'])]
    west, east = min(p[0] for p in points), max(p[0] for p in points)
    south, north = min(p[1] for p in points), max(p[1] for p in points)
    project = lambda lon, lat: (6378137 * math.radians(lon),
        6378137 * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2)))
    x, y = project((west + east) / 2, (south + north) / 2)
    for level in (13, 15, 19):
        lod = next(lod for lod in info['lods'] if lod['level'] == level)
        col = math.floor((x - info['origin']['x']) / (info['cols'] * lod['resolution']))
        row = math.floor((info['origin']['y'] - y) / (info['rows'] * lod['resolution']))
        status, body = request('tile-' + str(level), f'{SERVICE}/tile/{level}/{row}/{col}')
        try:
            if not body or status.get('arcgis_error') or not (status.get('content_type') or '').startswith('image/'):
                raise RuntimeError('Expected an actual image response')
            with Image.open(io.BytesIO(body)) as image:
                image.load()
                status['image_size'] = list(image.size)
                status['image_format'] = image.format
                if image.size != (info['cols'], info['rows']):
                    raise RuntimeError('Unexpected tile dimensions')
                rgb = image.convert('RGB')
                status['channel_variance'] = ImageStat.Stat(rgb).var
                if max(status['channel_variance']) < 1:
                    raise RuntimeError('Tile is uniform/blank; investigate coverage before claiming success')
                image.save(out / ('tile-' + str(level) + '.png'))
            print('Decoded imagery:', json.dumps(status), flush=True)
        except Exception as error:
            status['decode_error'] = str(error)
            report['failures'].append('Tile level ' + str(level) + ': ' + str(error))

    # Compare the original export behavior and a correctly ordered bbox. These
    # probes are diagnostic only; this implementation renders the cached tiles.
    xmin, ymin = project(west, south)
    xmax, ymax = project(east, north)
    for label, bbox in [('export-original-reversed', (xmin, ymax, xmax, ymin)),
                        ('export-corrected', (xmin, ymin, xmax, ymax))]:
        params = {'bbox': ','.join(map(str, bbox)), 'bboxSR': 3857, 'imageSR': 3857,
                  'size': '512,512', 'format': 'jpgpng', 'f': 'json'}
        request(label, SERVICE + '/exportImage?' + urllib.parse.urlencode(params))


try:
    check()
except Exception as error:
    report['failures'].append(str(error))
finally:
    report['result'] = 'FAIL' if report['failures'] else 'PASS: actual metadata and three nonblank imagery tiles decoded'
    (out / 'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as stream:
            stream.write('## OSIP live service checks\n\n' + report['result'] + '\n\n')
            stream.write('| Request | HTTP status | Content type | Error |\n| --- | --- | --- | --- |\n')
            for item in report['requests']:
                error = item.get('error') or item.get('arcgis_error') or item.get('decode_error') or ''
                stream.write(f"| {item['label']} | {item.get('status', 'No response')} | {item.get('content_type', '')} | {str(error).replace('|', '/')} |\n")
            for error in report['failures']:
                stream.write('\n' + error.replace('\n', ' ') + '\n')
sys.exit(1 if report['failures'] else 0)
