"""Reconcile the complete hosted delivery; fail rather than publish a partial county."""
import json
import zipfile
from pathlib import Path
from .packages import sha
from .county import write


def main():
    out=Path('output/delivery'); source=Path('work/extracted')
    catalog=json.loads((source/'county-catalog.json').read_text()); records=[]; total=0
    for entry in catalog['packages']:
        path=out/(entry['id']+'.olepack')
        with zipfile.ZipFile(path) as z:
            manifest=json.loads(z.read('manifest.json'))
            assert manifest['schema_version']==3 and manifest['kind']=='parcels'
            assert manifest['parcel_count']==entry['parcel_count']
            assert manifest['owner_count']==0 and manifest['owner_data_status']=='withheld'
            assert set(z.namelist())==set(manifest['sha256'])|{'manifest.json'}
            import hashlib
            for name,expected in manifest['sha256'].items():assert hashlib.sha256(z.read(name)).hexdigest()==expected
            payload=sum(i.file_size for i in z.infolist())
        total+=entry['parcel_count']
        records.append({**entry,'file':path.name,'sha256':sha(path),'download_bytes':path.stat().st_size,'installed_bytes':payload})
    reconcile=json.loads((source/'county-reconciliation.json').read_text())
    assert total==reconcile['intersecting_official_county']==reconcile['packaged_unique_ids'] and not reconcile['missing_ids']
    catalog['packages']=records
    write(out/'county-catalog.json',catalog)
    for name in ['county-reconciliation.json','county-boundary.json','source-audit.json','open-data-item.json']:
        (out/name).write_bytes((source/name).read_bytes())
    write(out/'county-storage.json',{'parcel_packages':len(records),'parcel_count':total,
        'parcel_download_bytes':sum(r['download_bytes'] for r in records),'parcel_installed_bytes':sum(r['installed_bytes'] for r in records),
        'basemap_download_bytes':(out/'marion-outdoors.olepack').stat().st_size})
    (out/'INSTALL.txt').write_text('Marion County offline maps\n\nInstall the signed county APK over v0.3.2. Do not uninstall.\nDownload marion-outdoors.olepack and every marion-*.olepack parcel package.\nIn the app: Region > Import .olepack from Downloads. Select multiple packages.\nWait for validation. Packages install independently; a failed file preserves its installed version.\nUse Region > County coverage for missing/disabled package status.\nEnable airplane mode and verify GPS privately.\n\nCounty public parcel downloads deliberately omit owner names. This does not mean a parcel has no owner.\nExisting Gervais/Silver Falls owner-enabled packages are retained.\nCountywide owner production is gated pending written permission.\nBoundaries are approximate, not surveys; ownership does not grant public access.\nOSM data: © OpenStreetMap contributors, ODbL 1.0; source Geofabrik. County disclaimer retained in each package.\n')
    files=sorted(out.glob('*.olepack'))
    (out/'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in files))


if __name__=='__main__':main()
