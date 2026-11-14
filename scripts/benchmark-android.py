"""Same-emulator comparison. Activity timing is not tile-render latency or hardware GNSS evidence."""
import json
import re
import subprocess
import time
from pathlib import Path


def adb(*args):
    return subprocess.check_output(['adb',*map(str,args)],text=True)


def measure(apk,package):
    adb('install','-r',apk)
    activity=package+'/org.oregonlandexplorer.MainActivity'
    timings=[]
    for _ in range(3):
        adb('shell','am','force-stop',package)
        launch=adb('shell','am','start','-W','-n',activity)
        values=dict(re.findall(r'^(\w+):\s*(\d+)\s*$',launch,re.M))
        assert 'TotalTime' in values,launch
        timings.append({k:int(v) for k,v in values.items()})
        time.sleep(8)
    before=adb('shell','dumpsys','meminfo',package)
    adb('shell','dumpsys','gfxinfo',package,'reset')
    for _ in range(4):
        adb('shell','input','swipe','180','500','750','500','700')
        adb('shell','input','swipe','750','500','180','500','700')
    after=adb('shell','dumpsys','meminfo',package)
    gfx=adb('shell','dumpsys','gfxinfo',package,'framestats')
    for label,data in [('memory-idle',before),('memory-after-pan',after),('gfx',gfx)]:
        Path(f'output/{package}-{label}.txt').write_text(data)
    pss=re.search(r'TOTAL PSS:\s*(\d+)',after)
    adb('shell','am','force-stop',package)
    return {'apk_bytes':Path(apk).stat().st_size,'activity_launch_ms':timings,'after_pan_total_pss_kib':int(pss[1]) if pss else None,
            'limits':'Three force-stopped activity launches with OS disk caches retained; am start timing is first activity display, not completed map rendering. Default map extents differ. Gfxinfo covers Android UI and is not a full MapLibre GPU frame benchmark.'}

if __name__=='__main__':
    adb('shell','cmd','connectivity','airplane-mode','enable')
    adb('shell','svc','wifi','disable');adb('shell','svc','data','disable')
    results={
        'prototype':measure('work/baseline/app-debug.apk','org.oregonlandexplorer'),
        'expanded':measure('android/app/build/outputs/apk/debug/app-debug.apk','org.oregonlandexplorer.expanded')}
    Path('output/performance.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
