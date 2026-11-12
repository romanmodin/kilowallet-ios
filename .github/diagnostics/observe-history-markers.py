import json, pathlib, sys, time
root=pathlib.Path(sys.argv[1]); out=pathlib.Path(sys.argv[2])
end=time.monotonic()+600
with out.open("w",buffering=1) as f:
    while time.monotonic()<end:
        markers={p.name:p.stat().st_mtime_ns for p in root.glob("kw-ready-*") if p.is_file()}
        f.write(json.dumps({"observedAt":time.time(),"markers":markers})+"\n")
        time.sleep(1)
