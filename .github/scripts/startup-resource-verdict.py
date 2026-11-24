#!/usr/bin/env python3
"""Resource/thread guard only. Method tracing is a separate diagnostic."""
from pathlib import Path
import json,re,sys

def verdict(root):
    launch=(root/'launch.txt').read_text()
    for pattern in [r'^Status: ok\s*$',r'^LaunchState: COLD\s*$',r'^Activity: com\.slukhayka\.audiobooks\.debug/com\.slukhayka\.audiobooks\.MainActivity\s*$']:
        if not re.search(pattern,launch,re.M):raise ValueError('invalid cold MainActivity launch')
    pid=int((root/'live-pid.txt').read_text().strip())
    if pid<=0:raise ValueError('no live application process')
    lines=(root/'logcat.txt').read_text().splitlines()
    events={}
    for name in ['CREATE_BEGIN','CREATE_END','FIRST_DRAW_END']:
        found=[(i,re.search(r'event='+name+r' pid=(\d+) wallNs=(\d+)',line))for i,line in enumerate(lines) if 'event='+name+' 'in line]
        if len(found)!=1 or not found[0][1] or int(found[0][1][1])!=pid:raise ValueError('missing or mixed-process '+name)
        events[name]=found[0]
    if not events['CREATE_BEGIN'][0]<events['CREATE_END'][0]<events['FIRST_DRAW_END'][0]:raise ValueError('invalid event order')
    records=[]
    pattern=r'\s(\d+)\s+(\d+)\s+I\s+Startup1742\s*: .*END kind=(ROOM|COIL) main=(true|false) beforeFirstDraw=(true|false) wallNs=(\d+) cpuNs=(\d+)'
    for i,line in enumerate(lines):
        m=re.search(pattern,line)
        if not m:
            if 'END kind=' in line and 'Startup1742' in line:raise ValueError('malformed resource record')
            continue
        process,thread=int(m[1]),int(m[2]);main=m[4]=='true';before=m[5]=='true'
        if process!=pid or main!=(process==thread):raise ValueError('resource process/thread mismatch')
        if main and before!=(i<events['FIRST_DRAW_END'][0]):raise ValueError('main resource first-draw mismatch')
        wall,cpu=int(m[6]),int(m[7])
        if wall<=0 or cpu<0:raise ValueError('invalid constructor durations')
        records.append({'kind':m[3],'pid':pid,'thread':thread,'main':main,'beforeFirstDraw':before,'wallMs':wall/1e6,'cpuMs':cpu/1e6})
    main_begins=sum('BEGIN kind=' in line and ' main=true ' in line for line in lines)
    if main_begins!=sum(r['main'] for r in records):raise ValueError('incomplete main constructor observations')
    violations=[r for r in records if r['main']and r['beforeFirstDraw']]
    return {'scope':'resource/thread guard on producer-pinned diagnostic APK without method profiler; no presentation or latency claim','pid':pid,'constructors':records,'violations':violations,'outcome':'red-resource-init-on-main-before-draw'if violations else'green'}

def main():
    try:
        root=Path(sys.argv[1]);report=verdict(root)
        (root/'resource-verdict.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
    except Exception as error:
        print('Infrastructure/resource observations incomplete: '+str(error),file=sys.stderr);return 2
    return 1 if report['violations']else 0
if __name__=='__main__':sys.exit(main())
