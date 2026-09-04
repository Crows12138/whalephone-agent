#!/usr/bin/env python3
"""
副屏取景窗。在电脑上开一个自动刷新的网页,显示 agent 那块看不见的屏。

录演示视频时用:主画面拍手里的手机(用户在正常刷手机),画中画放这个网页
(agent 在副屏上把任务做完)。两边同框才说得清「互不干扰」这件事。

它只是取景窗:agent 不依赖它,关掉照常工作。数据线在演示里只用来传这张图。

前提:app 里把 DEMO_FEED 配成 1
    adb shell am broadcast -a ai.whalephone.agent.CONFIG --es key DEMO_FEED --es value 1

用法:
    python scripts/vd_view.py          # 然后浏览器开 http://127.0.0.1:8080
"""

import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

ADB = os.environ.get("ADB", r"C:\Users\12916\platform-tools\adb.exe")
REMOTE = "/sdcard/Download/wp_vd.png"
LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".feed.png")
PORT = 8080

PAGE = b"""<!doctype html><meta charset=utf-8><title>agent 副屏</title>
<style>
 body{margin:0;background:#111;display:flex;align-items:center;justify-content:center;height:100vh}
 img{max-height:100vh;max-width:100vw;object-fit:contain}
 #t{position:fixed;top:8px;left:10px;color:#666;font:12px system-ui}
</style>
<div id=t>agent 副屏 · 用户看不到这块屏</div>
<img id=v src="/f.png">
<script>
 setInterval(()=>{document.getElementById('v').src='/f.png?'+Date.now()},400)
</script>
"""


def puller():
    while True:
        subprocess.run([ADB, "pull", REMOTE, LOCAL],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.5)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path.startswith("/f.png"):
            try:
                with open(LOCAL, "rb") as f:
                    data = f.read()
            except OSError:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else PORT
    threading.Thread(target=puller, daemon=True).start()
    print(f"取景窗开在 http://127.0.0.1:{port}", flush=True)
    HTTPServer(("127.0.0.1", port), H).serve_forever()
