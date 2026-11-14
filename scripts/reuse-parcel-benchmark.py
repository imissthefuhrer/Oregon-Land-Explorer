"""Retain executed measurements after a CI-only fix; never synthesize samples."""
import json
import shutil
import sys
from pathlib import Path

source=Path(sys.argv[1]); target=Path('output/emulator-screenshots');target.mkdir(parents=True,exist_ok=True)
for variant in ['baseline','optimized']:
    matches=list(source.rglob(variant+'-performance.json'))
    assert len(matches)==1,(variant,matches)
    report=json.loads(matches[0].read_text())
    assert report['variant']==variant and [s['scenario'] for s in report['scenarios']]==['original','gervais','silver','both']
    assert len(report['scenarios'][-1]['navigation_samples'])==50
    for path in matches[0].parent.iterdir():
        if path.is_file():shutil.copyfile(path,target/path.name)
for path in source.rglob('*instrumentation.txt'):
    text=path.read_text()
    assert 'OK (' in text and 'FAILURES!!!' not in text,path
    shutil.copyfile(path,Path('output')/path.name)
for path in source.rglob('*cold*.txt'):
    shutil.copyfile(path,Path('output')/path.name)
Path('output/performance-provenance.json').write_text(json.dumps({
    'paired_measurement_run':43630083953,
    'source_commit':'bcdaa8d0c8b818dede9f618cf3dfaaf52a19fd24',
    'reason':'Reuse completed database/transport tests and paired benchmarks for unchanged production sources. Git diff guards unchanged production, GIS and benchmark sources; functional UI tests execute again.',
},indent=2))
print('Retained executed database/transport preflight and both complete paired benchmarks from run 43630083953')
