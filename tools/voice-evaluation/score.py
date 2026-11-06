#!/usr/bin/env python3
"""Score real phone transcriptions. Empty cells remain UNMEASURED; no network/audio access."""
import csv, re, sys, unicodedata

def distance(a, b):
    row = list(range(len(b)+1))
    for i,x in enumerate(a,1):
        nxt = [i]
        for j,y in enumerate(b,1): nxt.append(min(nxt[-1]+1,row[j]+1,row[j-1]+(x!=y)))
        row=nxt
    return row[-1]

def normalize(text):
    return ' '.join(re.findall(r'[^\W_]+',unicodedata.normalize('NFC',text).casefold(),re.UNICODE))

rows=list(csv.DictReader(open(sys.argv[1],encoding='utf-8')))
paired=[r for r in rows if r['before'].strip() and r['after'].strip()]
print(f'Paired measurements: {len(paired)}/{len(rows)}')
if not paired:
    print('UNMEASURED: requires the same Android phone and speech engine before/after.')
    sys.exit(0)
for variant in ['before','after']:
    we=wc=ce=cc=0; latency=[]
    for r in paired:
        ref,hyp=normalize(r['reference']),normalize(r[variant])
        we+=distance(ref.split(),hyp.split()); wc+=len(ref.split())
        ce+=distance(ref,hyp); cc+=len(ref)
        if r[variant+'_ms'].strip(): latency.append(float(r[variant+'_ms']))
    print(f'{variant}: WER={we/max(1,wc):.2%}; CER={ce/max(1,cc):.2%}; word edits={we}/{wc}')
    if latency: print(f'  Mean finalization latency: {sum(latency)/len(latency):.0f} ms ({len(latency)} rows)')
    for metric in ['omissions','repeats']:
        values=[int(r[variant+'_'+metric]) for r in paired if r[variant+'_'+metric].strip()]
        if values: print(f'  {metric}: {sum(values)} across {len(values)} rows')
