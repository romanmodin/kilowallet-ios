"""Read-only host telemetry around synthetic capture; preserves capture exit status."""
import datetime,json,subprocess,sys,threading
from pathlib import Path
COMMANDS=[["uptime"],["vm_stat"],["ps","-A","-o","pid=,ppid=,%cpu=,%mem=,rss=,comm="]]
def sample_host(run=subprocess.run):
    result={"observedAt":datetime.datetime.now(datetime.timezone.utc).isoformat()}
    for cmd in COMMANDS:
        try:
            r=run(cmd,capture_output=True,text=True,timeout=3)
            result[cmd[0]]={"exit":r.returncode,"output":r.stdout[-40000:],"stderr":r.stderr[-2000:]}
        except Exception as e:result[cmd[0]]={"error":str(e)}
    return result

def monitored_capture(command,path,run=subprocess.run,sample=sample_host,interval=10):
    path.parent.mkdir(parents=True,exist_ok=True)
    stop=threading.Event()
    def monitor():
        with path.open("w") as out:
            while not stop.is_set():
                try:value=sample()
                except Exception as e:value={"error":str(e)}
                out.write(json.dumps(value)+"\n");out.flush()
                stop.wait(interval)
    worker=threading.Thread(target=monitor,daemon=True);worker.start()
    try:return run(command).returncode
    finally:stop.set();worker.join(timeout=12)

if __name__=="__main__":
    assert len(sys.argv)>3 and sys.argv[2]=="--"
    sys.exit(monitored_capture(sys.argv[3:],Path(sys.argv[1])))
