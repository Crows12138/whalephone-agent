# -*- coding: utf-8 -*-
"""
输入法状态录像机。

回答的是「机主说唤不起输入法,那一刻系统里到底发生了什么」——
不靠复现时盯着屏幕猜,而是把 IMMS 的内部状态按时间轴记下来,事后回放。

两路信号,缺一不可:

**状态**(只在变化时写一行)—— 每一栏都是 IMMS 自己的字段,不是推断:
  shown        mInputShown                输入法窗口到底显示了没有
  vis          mImeWindowVis              0 = 完全不可见
  hiddenByPol  mImeHiddenByDisplayPolicy  被「这块屏不许显示输入法」摁住了
  showIme      mDisplayIdToShowIme        系统打算显示在哪块屏(-1 = 哪都不显示)
  target       mFocusedWindowClient 所在的屏 —— 输入法认的是哪块屏上的窗口
  client       mCurClient 所在的屏
  focus        窗口管理器认的当前焦点窗口

**请求**(每来一条写一行)—— 光看状态分不清「压根没人请求弹键盘」和
「请求发了但被半路丢掉」,而这两种是不同的病。ImeTracker 把每一次 show/hide
请求连同它**停在哪个阶段**都记下来了(PHASE_xxx),这一路专门抓它。

掉线要看得见:设备断了就明写一行,别退化成一排问号 —— 上一版就是这么
悄悄瞎掉的,等发现时已经错过了复现。
"""
import re, subprocess, sys, time

ADB = r"C:/Users/12916/platform-tools/adb.exe"
OUT = sys.argv[1] if len(sys.argv) > 1 else "ime-timeline.log"

FIELDS = ("mInputShown|mImeWindowVis|mImeHiddenByDisplayPolicy|mDisplayIdToShowIme|"
          "mFocusedWindowClient|mCurClient=|mFocusedWindowSoftInputMode|"
          "mRequestedShowExplicitly|mShowForced")
CMD = ("dumpsys input_method | grep -E '%s'"
       "; echo ==REQ==; dumpsys input_method | grep -B1 -A2 -E 'ORIGIN_(CLIENT|SERVER|IME)'"
       "; echo ==WIN==; dumpsys window | grep -E 'mCurrentFocus'") % FIELDS

ENTRY = re.compile(
    r"startTime=\S+ (\d\d:\d\d:\d\d\.\d+).*?(ORIGIN_\w+)\s*\n\s*reason=(\S+)\s+(PHASE_\S+)"
    r"(?:\s*\n\s*requestWindowName=(.*))?")


def field(name, text):
    m = re.search(re.escape(name) + r"=([^\s,}]+)", text)
    return m.group(1) if m else "?"


def parse(text):
    state, reqs, wins = text.split("==REQ==")[0], "", ""
    rest = text.split("==REQ==")
    if len(rest) > 1:
        reqs, wins = (rest[1].split("==WIN==") + [""])[:2]
    tgt = re.search(r"mFocusedWindowClient=ClientState\{[^}]*mSelfReportedDisplayId=(\d+)", state)
    cli = re.search(r"mCurClient=ClientState\{[^}]*mSelfReportedDisplayId=(\d+)", state)
    foc = re.findall(r"mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}", wins)
    row = (
        field("mInputShown", state), field("mImeWindowVis", state),
        field("mImeHiddenByDisplayPolicy", state), field("mDisplayIdToShowIme", state),
        tgt.group(1) if tgt else "?", cli.group(1) if cli else "?",
        " | ".join(foc) if foc else "-",
    )
    return row, [m.groups() for m in ENTRY.finditer(reqs)]


HEAD = "时刻          shown  vis hiddenByPol showIme target client  focus"
FMT = "%s  %-5s  %-3s %-11s %-7s %-6s %-6s %s"
last, seen, alive = None, set(), True
with open(OUT, "w", encoding="utf-8") as f:
    def w(line):
        f.write(line + "\n"); f.flush(); print(line)
    w(HEAD)
    while True:
        p = subprocess.run([ADB, "shell", CMD], capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        if p.returncode != 0 or "device" in (p.stderr or "") and "not found" in (p.stderr or ""):
            if alive:
                w("%s  【手机掉线了 —— 这段时间没有任何记录】 %s"
                  % (time.strftime("%H:%M:%S"), (p.stderr or "").strip()[:80]))
                alive, last = False, None
            time.sleep(2); continue
        if not alive:
            w("%s  【手机回来了,继续记】" % time.strftime("%H:%M:%S"))
            alive = True
        row, reqs = parse(p.stdout)
        for e in reqs:
            key = (e[0], e[1], e[2], e[3])
            if key in seen:
                continue
            seen.add(key)
            w("%s  请求 %-14s %-26s 停在 %-34s 窗口=%s"
              % (time.strftime("%H:%M:%S"), e[1], e[2], e[3], (e[4] or "").strip()))
        if row != last:
            w(FMT % ((time.strftime("%H:%M:%S"),) + row))
            last = row
        time.sleep(0.5)
