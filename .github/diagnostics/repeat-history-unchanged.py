"""Three strict uninstrumented sequences; any failure fails the whole probe."""
import hashlib,json,pathlib,shutil,subprocess,sys
SOURCE="c4f487efc9b328263f15d91c51670d919a9b31b9"
def main():
 assert subprocess.check_output(["git","rev-parse","HEAD"],text=True).strip()==SOURCE
 assert not subprocess.check_output(["git","diff","--name-only"],text=True).strip()
 source=pathlib.Path("scripts/ci-screenshots.sh").read_text()
 assert hashlib.sha256(source.encode()).hexdigest()=="168c35fafecefca744d2416fee8b07f8d1917d78a61eb7b0f1b31507b087d6c3"
 lines=source.splitlines(keepends=True);indexes=[i for i,l in enumerate(lines) if l.startswith("for screen in welcome history ")]
 assert len(indexes)==1
 lines[indexes[0]]="for screen in welcome history; do\n"
 script=pathlib.Path("build/history-uninstrumented.sh");script.parent.mkdir(exist_ok=True);script.write_text("".join(lines))
 subprocess.run(["bash","-n",str(script)],check=True)
 root=pathlib.Path("build/history-repeats");root.mkdir(exist_ok=True)
 receipt={"source":SOURCE,"instrumented":False,"requiredConsecutivePasses":3,"results":[],"qualified":False}
 for n in range(1,4):
  dest=root/str(n);dest.mkdir()
  with (dest/"capture.log").open("w") as out:
   result=subprocess.run(["bash",str(script),sys.argv[1],str(dest/"screenshots")],stdout=out,stderr=subprocess.STDOUT)
  if pathlib.Path("screenshots-logs").exists():
   shutil.move("screenshots-logs",dest/"screenshots-logs")
  receipt["results"].append({"iteration":n,"exitCode":result.returncode})
  receipt["qualified"]=len(receipt["results"])==3 and all(r["exitCode"]==0 for r in receipt["results"])
  (root/"result.json").write_text(json.dumps(receipt,indent=2)+"\n")
  print(json.dumps(receipt),flush=True)
  if result.returncode: return result.returncode
 return 0
if __name__=="__main__":raise SystemExit(main())
