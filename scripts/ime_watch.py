# -*- coding: utf-8 -*-
"""
输入法状态录像机。

回答的是「机主说唤不起输入法,那一刻系统里到底发生了什么」——
不靠复现时盯着屏幕猜,而是把 IMMS 的内部状态按时间轴记下来,
事后回放。只在状态**变化**的时候写一行,静止的时候不写。

每一栏都是 IMMS 自己的字段,不是推断:
  shown        mInputShown            输入法窗口到底显示了没有
  vis          mImeWindowVis          0 = 完全不可见
  hiddenByPol  mImeHiddenByDisplayPolicy  被「这块屏不许显示输入法」的策略摁住了
  showIme      mDisplayIdToShowIme    系统打算把输入法显示在哪块屏(-1 = 哪都不显示)
  target       mFocusedWindowClient 所在的屏 —— 输入法认的是哪块屏上的窗口
  client       mCurClient 所在的屏
  focus        窗口管理器认的当前焦点窗口
"""
import re, subprocess, sys, time

ADB = r"C:/Users/12916/platform-tools/adb.exe"
OUT = sys.argv[1] if len(sys.argv) > 1 else "ime-timeline.log"

CMD = ("dumpsys input_method | grep -E "
       "'mInputShown|mImeWindowVis|mImeHiddenByDisplayPolicy|mDisplayIdToShowIme|"
       "mFocusedWindowClient|mCurClient=|mFocusedWindowSoftInputMode|mSelectedMethodId'"
       "; echo ----; dumpsys window | grep -E 'mCurrentFocus|imeInputTarget'")


def one(field, text, pat=r"=([^\s,}]+)"):
    m = re.search(re.escape(field) + pat, text)
    return m.group(1) if m else "?"


def sample():
    p = subprocess.run([ADB, "shell", CMD], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    t = p.stdout
    tgt = re.search(r"mFocusedWindowClient=ClientState\{[^}]*mSelfReportedDisplayId=(\d+)", t)
    cli = re.search(r"mCurClient=ClientState\{[^}]*mSelfReportedDisplayId=(\d+)", t)
    foc = re.findall(r"mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}", t)
    return (
        one("mInputShown", t),
        one("mImeWindowVis", t),
        one("mImeHiddenByDisplayPolicy", t),
        one("mDisplayIdToShowIme", t),
        tgt.group(1) if tgt else "?",
        cli.group(1) if cli else "?",
        " | ".join(foc) if foc else "-",
    )


HEAD = "时刻          shown  vis hiddenByPol showIme target client  focus"
FMT = "%s  %-5s  %-3s %-11s %-7s %-6s %-6s %s"
last = None
with open(OUT, "w", encoding="utf-8") as f:
    f.write(HEAD + "\n")
    f.flush()
    print(HEAD)
    while True:
        s = sample()
        if s != last:
            line = FMT % ((time.strftime("%H:%M:%S"),) + s)
            f.write(line + "\n")
            f.flush()
            print(line)
            last = s
        time.sleep(0.5)
