import json
import time
import urllib.parse
import urllib.request
import urllib.error


def fetch(url, params=None):
    if params:
        url += '?' + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            request = urllib.request.Request(url, headers={'User-Agent': 'OregonLandExplorer/0.3 (personal offline GIS)'})
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError):
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


def get_json(url, params=None):
    data = json.loads(fetch(url, params))
    if 'error' in data:
        raise RuntimeError(f'ArcGIS error at {url}: {data["error"]}')
    return data
