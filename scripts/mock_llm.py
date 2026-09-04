#!/usr/bin/env python3
"""
开发期用的假 LLM。实现 OpenAI 兼容的 /v1/chat/completions,但不调模型,
按快照里的元素做规则决策。

它存在的唯一目的是**把「链路对不对」和「模型聪不聪明」拆开验**:
手机上跑起来失败时,如果不能确定是感知层没抓到元素、动作层没点中、
JSON 契约对不上、还是模型判断错了,就没法排查。这个服务端把前三项
单独跑通,剩下的换成真 key 就只剩最后一项。

它走的是和真模型完全相同的路径:app 发 HTTP、收 choices[0].message.content、
按同一份 JSON 契约解析、由同一个 Hands 执行。**唯一不同的是产生动作的是
if-else 而不是 transformer。**

用法:
    python scripts/mock_llm.py            # 监听 8765
    adb reverse tcp:8765 tcp:8765         # 手机的 127.0.0.1:8765 -> 电脑
    # 然后把 app 的 LLM_BASE_URL 配成 http://127.0.0.1:8765/v1
"""

import json
import re
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8765
LOG = []

ELEM = re.compile(r'^\[(\d+)\]\s+(\S+)\s+"(.*)"\s*(.*)$')


def parse_elements(text):
    out = []
    for line in text.splitlines():
        m = ELEM.match(line.strip())
        if m:
            out.append({
                "i": int(m.group(1)),
                "role": m.group(2),
                "label": m.group(3),
                "flags": m.group(4),
            })
    return out


def find(els, pred):
    for e in els:
        if pred(e):
            return e
    return None


def decide(goal, snapshot, history):
    """一个够用的购物策略。真模型会做得更好,这里只需要它把链路走完。"""
    els = parse_elements(snapshot)
    editable = [e for e in els if "可输入" in e["flags"]]
    clickable = [e for e in els if "可点" in e["flags"]]

    m = re.search(r"[「\"']([^」\"']+)[」\"']", goal)
    term = m.group(1) if m else "AirPods Pro 2"

    did = " ".join(history)
    prices = [e for e in els if re.search(r"\d+\.?\d*\s*元|¥\s*\d", e["label"])]

    # 已经搜过,并且看到了带价格的条目 -> 收工
    if "set_text" in did and prices:
        top = prices[0]["label"]
        return {"thought": "结果页出现了带价格的商品,取第一个",
                "action": "done", "summary": f"搜到「{term}」,第一个结果:{top}"}

    # 输入框已经就位,里面还不是要搜的词 -> 填进去
    if editable and term not in editable[0]["label"]:
        return {"thought": "找到输入框,把搜索词填进去",
                "action": "set_text", "index": editable[0]["i"], "text": term}

    # 词填好了 -> 点搜索
    if editable and term in editable[0]["label"]:
        btn = find(clickable, lambda e: e["label"].strip() in ("搜索", "搜 索"))
        if btn:
            return {"thought": "搜索词已就位,提交", "action": "click", "index": btn["i"]}

    # 首页 -> 点搜索栏进入搜索页
    bar = find(clickable, lambda e: "搜索栏" in e["label"] or "搜索" in e["label"])
    if bar:
        return {"thought": "在首页,先进搜索页", "action": "click", "index": bar["i"]}

    # 什么都没有 -> 等一下让界面加载
    scroll = find(els, lambda e: "可滚" in e["flags"])
    if scroll:
        return {"thought": "没看到可用的入口,往下翻一屏",
                "action": "scroll", "index": scroll["i"], "direction": "forward"}
    return {"thought": "界面还没加载出来", "action": "wait", "ms": 1500}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        req = json.loads(body)
        user = [m for m in req["messages"] if m["role"] == "user"][-1]["content"]

        goal = ""
        gm = re.search(r"目标:(.*)", user)
        if gm:
            goal = gm.group(1).strip()
        history = re.findall(r"^\s+\d+\.\s+(.*)$", user, re.M)
        snapshot = user[user.find("这是副屏"):]

        action = decide(goal, snapshot, history)
        n = len(LOG) + 1
        LOG.append(action)
        print(f"[{n:2}] {action['action']:9} {action.get('thought','')}", flush=True)
        if action["action"] == "done":
            print(f"     -> {action['summary']}", flush=True)

        out = json.dumps({
            "choices": [{"message": {"role": "assistant",
                                     "content": json.dumps(action, ensure_ascii=False)}}]
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else PORT
    print(f"假 LLM 监听 {port},等 app 来要动作", flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
