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
#
# 也不能用 app 的 SNAP/CLICK 广播来点输入框:A11yGate 上线后无障碍默认是关的,
# 那两个广播的接收器根本不存在,准备阶段会直接失败。raise_ime 走 uiautomator,
# 不吃这个依赖。
raise_ime || { echo "输入法没起来(mInputShown=$(ime)),测不了"; exit 1; }
echo "   输入法已弹出 mInputShown=true  焦点屏=$(focus)"

echo
echo "== 开跑,全程采样 =="
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

# 机主不会一直开着键盘不动。让键盘全程弹着,测的就变成「等到超时之后会怎样」,
# 那不是真实场景 —— 真人打字是几秒到几十秒然后停。
# 所以只在前 TYPING_S 秒保持弹出,之后自己收掉;判定只看这段窗口内的掉落。
TYPING_S="${TYPING_S:-45}"
T0=$(date +%s)
DROPS=0; AFTER=0; STEAL=0; PREV=true; CLOSED=0
for i in $(seq 1 600); do
  NOW=$(( $(date +%s) - T0 ))
  if [ "$CLOSED" = "0" ] && [ "$NOW" -ge "$TYPING_S" ]; then
    echo "   [${NOW}s] 机主打完字,自己收起键盘(之后的掉落不算 agent 头上)"
    sh input -d 0 keyevent 111 >/dev/null 2>&1
    CLOSED=1; python -c "import time;time.sleep(1)"; PREV=$(ime); continue
  fi
  M=$(ime); F=$(focus)
  if [ "$PREV" = "true" ] && [ "$M" = "false" ]; then
    if [ "$CLOSED" = "0" ]; then
      DROPS=$((DROPS+1)); echo "   [${NOW}s] 机主还在打字,键盘却被收起(第 $DROPS 次)  焦点屏=$F"
    else AFTER=$((AFTER+1)); fi
  fi
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
# 让路机制到底有没有触发。DROPS=0 但一次都没让过,说明这次机主根本没打字,
# 测试没测到东西 —— 那是「没测出来」,不是「解决了」,两者必须分得开。
YIELDS=$(sh logcat -d -s WPConflict:* 2>/dev/null | grep -c "机主在打字")
ANRS=$(sh logcat -d -b events 2>/dev/null | grep -c "am_anr")
echo "== 结论 =="
echo "   期间 ANR 次数        $ANRS   (要求 0)"
echo "   打字期间键盘被收起  $DROPS   (要求 0 —— 这一项才是「有没有打扰机主」)"
echo "   打字结束后被收起    $AFTER  (不算 agent 头上,机主已经不在打字了)"
echo "   agent 主动让路次数  $YIELDS   (为 0 说明这次机主没打字,这轮测试无效)"
echo "   焦点不在主屏的采样  $STEAL 次 (要求 0;短暂离开随即还回来也会被采到)"
echo "   最终 mInputShown=$(ime)  焦点屏=$(focus)"
if [ "$YIELDS" -eq 0 ]; then echo "   这轮不算数:agent 一次都没让过,说明机主全程没打字"
elif [ "$DROPS" -eq 0 ]; then echo "   机主的键盘全程没被动过 ✓"
else echo "   机主的键盘被打断了 ✗"; fi
