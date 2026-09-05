#!/usr/bin/env bash
# 机主的输入法,在 agent 干活的全程有没有被收起过。
#
# 为什么不是数「字有没有丢」:
#   之前的并发测试用 `input -d 0 text` 一秒打一个字,跑完比对字符串,26/26 通过,
#   于是我在文档里写了「不打扰」。机主用真手指一试就断了。
#   原因是那个指标错了 —— **键盘被收起来之后,重新点一下输入框还能接着打**,
#   字并不会丢。丢的是机主的连续性,而那个测试看不见。
#
# 直接测输入法自己的状态:`dumpsys input_method` 的 mInputShown。
# 它是真实 IME 的真实状态,跟字是怎么进去的无关。任何一次 true -> false
# 都是一次实打实的打扰。同时记 FocusedDisplayId,用来定位是谁把焦点拽走的。
#
# 用法: bash scripts/tests/test-ime.sh ["任务"]

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
GOAL="${1:-打开淘宝,搜索「保温杯」,告诉我前两个商品的价格和店铺}"

ime()   { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
focus() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }

echo "== 准备:在主屏上把真实输入法叫出来 =="
# 用 Edge 的地址栏,不用本 app 自己的输入框。
# 焦点被挪到副屏期间,机主前台的 app 一旦收到触摸就会
# 「Input dispatching timed out」ANR,而 ANR 对话框本身也会带走键盘 ——
# 拿本 app 当载体,等于用一个会被待测现象弄坏的东西去测那个现象,
# 量出来的「被收起 6 次」里有几次是自己的 ANR 造成的都分不清。
sh dumpsys activity activities | grep -m1 topResumedActivity | grep -q emmx || {
  sh monkey -p com.microsoft.emmx -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  python -c "import time;time.sleep(5)"
}
for _ in 1 2 3; do
  sh logcat -c; sh am broadcast -a $A.SNAP --ei display 0 >/dev/null 2>&1
  python -c "import time;time.sleep(2)"
  I=$(sh logcat -d -s WPEyes:* | grep -oE '\[[0-9]+\] EditText' | head -1 | grep -oE '[0-9]+' | head -1)
  [ -n "$I" ] && {
    sh am broadcast -a $A.CLICK --ei display 0 --ei index "$I" >/dev/null 2>&1
    python -c "import time;time.sleep(2.5)"
    [ "$(ime)" = "true" ] && break
  }
done
[ "$(ime)" != "true" ] && { echo "输入法没起来(mInputShown=$(ime)),测不了"; exit 1; }
echo "   输入法已弹出 mInputShown=true  焦点屏=$(focus)"

echo
echo "== 开跑,全程采样 =="
sh logcat -c
sh am broadcast -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

DROPS=0; STEAL=0; PREV=true
for i in $(seq 1 200); do
  M=$(ime); F=$(focus)
  [ "$PREV" = "true" ] && [ "$M" = "false" ] && {
    DROPS=$((DROPS+1)); echo "   [$(date +%H:%M:%S)] 输入法被收起(第 $DROPS 次)  焦点屏=$F"
  }
  [ -n "$F" ] && [ "$F" != "0" ] && STEAL=$((STEAL+1))
  PREV="$M"
  sh logcat -d -s WPSvc:* | grep -q "结束 done=" && break
  python -c "import time;time.sleep(0.4)"
done

echo
echo "== agent 那边做了什么 =="
sh logcat -d -s WPAgent:* | grep -oE "第 [0-9]+ 步 [a-z_]+" | tr '\n' ' '; echo
sh logcat -d -s WPSvc:* | grep "结束 done=" | sed -E 's/.*WPSvc  : /   /'

echo
ANRS=$(sh logcat -d -b events 2>/dev/null | grep -c "am_anr")
echo "== 结论 =="
echo "   期间 ANR 次数        $ANRS   (要求 0)"
echo "   输入法被收起次数    $DROPS   (要求 0)"
echo "   焦点不在主屏的采样  $STEAL 次 (要求 0;短暂离开随即还回来也会被采到)"
echo "   最终 mInputShown=$(ime)  焦点屏=$(focus)"
[ "$DROPS" -eq 0 ] && echo "   机主的键盘全程没被动过 ✓" || echo "   机主的键盘被打断了 ✗"
