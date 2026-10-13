"""Start the local viewer independently of the launching console."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent
PORT = int(os.environ.get("PORT") or 8930)
URL = f"http://127.0.0.1:{PORT}"


def healthy():
    try:
        with urllib.request.urlopen(URL + "/api/status", timeout=2) as response:
            return "converter" in json.load(response)
    except Exception:
        return False


def main():
    if healthy():
        print(f"Viewer already running: {URL}")
        return
    (ROOT / "logs").mkdir(exist_ok=True)
    kwargs = {"cwd": ROOT, "stdin": subprocess.DEVNULL, "close_fds": True}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    with (ROOT / "logs" / "web-viewer-process.log").open("ab") as output:
        child = subprocess.Popen([sys.executable, "-u", str(ROOT / "web_viewer_server.py")],
                                 stdout=output, stderr=output, **kwargs)
    for _ in range(20):
        if child.poll() is not None:
            break
        if healthy():
            print(f"Viewer ready: {URL} (PID {child.pid})")
            return
        time.sleep(0.25)
    raise SystemExit("Viewer failed to start; see logs/web-viewer-process.log")


if __name__ == "__main__":
    main()
