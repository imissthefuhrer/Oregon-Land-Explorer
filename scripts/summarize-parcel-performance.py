"""No target is substituted for a measurement. Preserve all raw samples."""
import json
import statistics
from pathlib import Path

root=Path('output/emulator-screenshots')
before=json.loads((root/'baseline-performance.json').read_text())
after=json.loads((root/'optimized-performance.json').read_text())
comparisons=[]
for old,new in zip(before['scenarios'],after['scenarios'],strict=True):
    assert old['scenario']==new['scenario']
    b=old['after_navigation']['pss_kib'];a=new['after_navigation']['pss_kib']
    samples=new['navigation_samples']
    tail=samples[len(samples)//2:]
    def slope(part):
        if len(part)<2:return None
        xs=[(s['elapsed_ms']-part[0]['elapsed_ms'])/60000 for s in part]
        ys=[s['pss_kib']/1024 for s in part]
        return statistics.linear_regression(xs,ys).slope
    comparisons.append({'scenario':new['scenario'],'baseline_pss_kib':b,'optimized_pss_kib':a,
                        'reduction_percent':round((1-a/b)*100,2),
                        'baseline_java_alloc_bytes':old['after_navigation']['java_alloc_bytes'],
                        'optimized_java_alloc_bytes':new['after_navigation']['java_alloc_bytes'],
                        'baseline_native_alloc_bytes':old['after_navigation']['native_alloc_bytes'],
                        'optimized_native_alloc_bytes':new['after_navigation']['native_alloc_bytes'],
                        'baseline_popup_median_ms':statistics.median(old['tap_to_popup_ms']),
                        'optimized_popup_median_ms':statistics.median(new['tap_to_popup_ms']),
                        'optimized_lookup_to_ui_median_ms':statistics.median(new['lookup_to_ui_ms']) if new.get('lookup_to_ui_ms') else None,
                        'baseline_complete_frame_median_ms':statistics.median(old['completed_viewport_frame_ms']) if old.get('completed_viewport_frame_ms') else None,
                        'optimized_complete_frame_median_ms':statistics.median(new['completed_viewport_frame_ms']) if new.get('completed_viewport_frame_ms') else None,
                        'baseline_native_fps_mean':old.get('native_fps_mean'),
                        'optimized_native_fps_mean':new.get('native_fps_mean'),
                        'optimized_internal_data_bytes_all_versions_and_indexes':new.get('internal_data_bytes_all_versions_and_indexes'),
                        'optimized_navigation_pss_min_max_kib':[min(s['pss_kib'] for s in samples),max(s['pss_kib'] for s in samples)],
                        'optimized_navigation_first_last_pss_kib':[samples[0]['pss_kib'],samples[-1]['pss_kib']],
                        'optimized_navigation_tail_pss_mib_per_minute':slope(tail),
                        'optimized_navigation_last_ten_pss_kib':[s['pss_kib'] for s in samples[-10:]],
                        'optimized_navigation_first_last_native_alloc_bytes':[samples[0]['native_alloc_bytes'],samples[-1]['native_alloc_bytes']],
                        'optimized_navigation_first_last_java_alloc_bytes':[samples[0]['java_alloc_bytes'],samples[-1]['java_alloc_bytes']]})
report={'comparison':comparisons,'limits':after['limits'],
        'baseline':before,'optimized':after,
        'historical_pr4_both_pss_kib':613805}
Path('output/parcel-performance.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'comparison':comparisons,'limits':after['limits']},indent=2))
