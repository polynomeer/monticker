#!/usr/bin/env python3
"""저장된 스트림 덤프를 다시 분석한다(분석기를 고친 뒤). kill 시각은 w2 기록의 가장 긴 공백 직전으로, 재기동은 +20s 로 추정한다.
   사용: m2-reanalyze.py <stream 파일…>"""
import sys, subprocess, json, os
for path in sys.argv[1:]:
    lines=[l.rstrip("\n") for l in open(path)]
    w2=[]; i=0
    while i < len(lines):
        if "-" in lines[i] and lines[i].replace("-","").isdigit():
            f={}; j=i+1
            while j+1 < len(lines) and lines[j] in ("s","q","p","w","g","r"): f[lines[j]]=lines[j+1]; j+=2
            if len(f)==6 and f["w"]=="w2": w2.append(int(f["r"]))
            i=j
        else: i+=1
    w2.sort(); k=max(range(1,len(w2)), key=lambda k: w2[k]-w2[k-1]); tkill=w2[k-1]; trestart=tkill+20000
    out=subprocess.check_output(["python3",os.path.join(os.path.dirname(__file__),"m2-analyze-stream.py"),path,str(tkill),str(trestart)],text=True)
    d=json.loads(out); d["run"]=os.path.basename(path).split(".")[0]
    open(path.replace(".stream",".json"),"w").write(json.dumps(d,ensure_ascii=False)+"\n")
    print(json.dumps({k:d[k] for k in ["run","lost","dups","violations","handover_gap_ms","w2_rejoin_s","w1_stall_max_ms","w1_e2e_p99_ms_during","handed_partitions_e2e_max_ms","handed_partitions_ticks_over_1s"]}))
