import hashlib, pathlib, subprocess, sys
source = pathlib.Path("scripts/ci-screenshots.sh").read_text()
assert hashlib.sha256(source.encode()).hexdigest() == "168c35fafecefca744d2416fee8b07f8d1917d78a61eb7b0f1b31507b087d6c3"
assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == "c4f487efc9b328263f15d91c51670d919a9b31b9"
lines = source.splitlines(keepends=True)
loops = [i for i, line in enumerate(lines) if line.startswith("for screen in welcome history ")]
assert len(loops) == 1
lines[loops[0]] = "for screen in welcome history; do\n"
source="".join(lines)
def replace(old,new):
    global source
    assert source.count(old)==1, old
    source=source.replace(old,new)
replace('n=0\nfailures=0', 'observer_pid=""\ntrap \'if [ -n "$observer_pid" ]; then kill "$observer_pid" 2>/dev/null || true; wait "$observer_pid" 2>/dev/null || true; fi\' EXIT\nn=0\nfailures=0')
replace('  printf %s "$screen" > "$DATA/Documents/kw-demo-case"', '''  rm -f "$DATA/Documents"/kw-ready-trace-*
  if [ -n "$observer_pid" ]; then kill "$observer_pid" 2>/dev/null || true; wait "$observer_pid" 2>/dev/null || true; fi
  python3 -u candidate-frame-harness/.github/diagnostics/observe-history-markers.py "$DATA/Documents" "screenshots-logs/$name.markers.jsonl" &
  observer_pid=$!
  printf %s "$screen" > "$DATA/Documents/kw-demo-case"''')
replace('  local phase="$1"', '''  local phase="$1"
  ls -lT "$DATA/Documents"/kw-ready-* >"screenshots-logs/$name.$phase.markers-before.txt" 2>&1 || true''')
replace(' >"screenshots-logs/$name.$phase.system.log" 2>&1 || true', ''' >"screenshots-logs/$name.$phase.system.log" 2>&1 || true
  # Observation after the unchanged failing gate. This never converts failure to success.
  ls -lT "$DATA/Documents"/kw-ready-* >"screenshots-logs/$name.$phase.markers-after.txt" 2>&1 || true
  xcrun simctl io "$UDID" screenshot "$OUT/$phase-post-diagnostics-$name.png" || true''')
path = pathlib.Path("build/history-startup-capture.sh")
path.parent.mkdir(exist_ok=True)
path.write_text(source)
subprocess.run(["bash","-n",str(path)],check=True)
raise SystemExit(subprocess.run(["bash", str(path), sys.argv[1], "screenshots"]).returncode)
