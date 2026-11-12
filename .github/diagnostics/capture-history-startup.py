import hashlib, pathlib, subprocess, sys
source = pathlib.Path("scripts/ci-screenshots.sh").read_text()
assert hashlib.sha256(source.encode()).hexdigest() == "168c35fafecefca744d2416fee8b07f8d1917d78a61eb7b0f1b31507b087d6c3"
assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == "c4f487efc9b328263f15d91c51670d919a9b31b9"
lines = source.splitlines(keepends=True)
loops = [i for i, line in enumerate(lines) if line.startswith("for screen in welcome history ")]
assert len(loops) == 1
lines[loops[0]] = "for screen in welcome history; do\n"
path = pathlib.Path("build/history-startup-capture.sh")
path.parent.mkdir(exist_ok=True)
path.write_text("".join(lines))
# No readiness/selection/deadline/OCR change: narrow only the fixture sequence.
raise SystemExit(subprocess.run(["bash", str(path), sys.argv[1], "screenshots"]).returncode)
