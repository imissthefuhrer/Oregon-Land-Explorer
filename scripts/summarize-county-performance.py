"""Actual county measurements, including peaks; never substitute a target for evidence."""
import json
import re
import statistics
from pathlib import Path

root=Path('output/emulator-screenshots')
county=json.loads((root/'county-performance.json').read_text())
baseline=json.loads((root/'v032-performance.json').read_text())
assert county['result']=='PASS'
assert county['update_rollback_remove_reimport']=='PASS' and county['gps_recenter_checks']==14
rows=[]
for s in county['scenarios']:
    samples=s['navigation_memory']+[v['memory'] for v in s['views']]+[s['after_navigation'],s['after_recreation']]
    rows.append({'scenario':s['scenario'],'activity_ready_ms':s['activity_ready_ms'],
        'pan_zoom_operations':s['pan_zoom_operations'],'after_navigation_pss_mib':s['after_navigation']['pss_kib']/1024,
        'sampled_pss_min_max_mib':[min(v['pss_kib'] for v in samples)/1024,max(v['pss_kib'] for v in samples)/1024],
        'sampled_native_alloc_max_mib':max(v['native_alloc_bytes'] for v in samples)/1048576,
        'sampled_java_alloc_max_mib':max(v['java_alloc_bytes'] for v in samples)/1048576,
        'sampled_open_fd_min_max':[min(v['open_file_descriptors'] for v in samples),max(v['open_file_descriptors'] for v in samples)],
        'directory_cache_max_bytes':max(v['directory_cache_bytes'] for v in samples),
        'exact_selection_median_ms':statistics.median(v['exact_lookup_ms'] for v in s['selection']),
        'exact_selection_max_ms':max(v['exact_lookup_ms'] for v in s['selection']),
        'complete_frame_median_ms':statistics.median(v['complete_frame_ms'] for v in s['views']),
        'views':s['views']})
assert next(r for r in rows if r['scenario']=='overlapping-regions')['pan_zoom_operations']>=600
old=next(s for s in baseline['scenarios'] if s['scenario']=='both')
launches={}
for path in Path('output').glob('county-*-cold-[123].txt'):
    value=re.search(r'^TotalTime:\s*(\d+)',path.read_text(),re.M)
    assert value,path
    group=path.name.rsplit('-cold-',1)[0]
    launches.setdefault(group,[]).append(int(value[1]))
report={'result':'PASS','county_parcels':county['county_parcel_count'],'county_packages':county['county_package_count'],
    'import_all_ms':county['import_all_ms'],'installed_payload_bytes_including_original_regions':county['installed_payload_bytes'],
    'same_emulator_v032_both_regions_after_navigation_pss_mib':old['after_navigation']['pss_kib']/1024,
    'same_emulator_v032_popup_median_ms':statistics.median(old['tap_to_popup_ms']),
    'historical_v032_both_regions_pss_mib':127.1,'county_scenarios':rows,'cold_activity_total_ms':launches,
    'limits':county['limits'],'sampling':'PSS sampled one second after views and every 12 navigation operations; transient peaks between samples are not measured. Complete-frame timings use the first fully rendered callback after the requested camera move. Exact selection timings include main-thread dispatch, not popup drawing.'}
Path('output/county-performance-summary.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
