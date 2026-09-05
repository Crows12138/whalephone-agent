#!/usr/bin/env bash
# 虚拟设备(VirtualDeviceManager)能不能保住机主的输入法。
#
# 要回答的问题只有一个:副屏上启一个 App 的那一刻,机主正在打字的软键盘会不会被收起。
# 之前测过的老路(DisplayManager 造屏)答案是「会」—— 而且 `am start --display N`
# 是唯一一个实测能收键盘的操作。根子在 IMMS 是全局单例:mDisplayIdToShowIme 只有一个,
# 副屏上任何窗口拿到焦点都会让它改值,主屏的 IME 就跟着没了。
#
# 但根子不在 IME 层。AOSP 里 IMMS 收到「某窗口拿到焦点」的上报后,要先过
# WindowManagerInternal#hasInputMethodClientFocus,而它是拿 getTopFocusedDisplayContent()
# 判的。所以真正决定成败的是**焦点**:
#   OWN_FOCUS 生效  → 主屏保住自己的焦点窗口 → 顶层焦点屏还是 0 → 副屏的上报被拒 →
#                     机主的键盘不动,主屏也不会「没有焦点窗口」(那正是 ANR 的成因)
#   OWN_FOCUS 无效  → 主屏 mCurrentFocus 变 null → 键盘和 ANR 两个问题一起来
#
# 所以这里做的是二乘二:{老路 DisplayManager, 虚拟设备} x {带 OWN_FOCUS, 不带}。
# 四组跑在同一个进程、同一套 hold 逻辑下,每次只差一个变量,才归得了因。
# 量的东西除了 mInputShown,还必须包括**主屏自己的焦点窗口在不在** —— 那是机制,
# 不是现象;只看现象就会像上次那样把「字没丢」当成「没打扰」。
#
# 用法: bash scripts/tests/test-ime-vd.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
DEX=/data/local/tmp/vdmdisplay.dex
LOG=/data/local/tmp/vd.log
ASSOC=${ASSOC:-5}
CALC=com.sec.android.app.popupcalculator/.Calculator
EDGE=com.microsoft.emmx/com.microsoft.ruby.Main

shown() { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
showon(){ sh dumpsys input_method | grep -oE "mDisplayIdToShowIme=[0-9-]+" | head -1 | cut -d= -f2; }
fdisp() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }
# 主屏自己的焦点窗口还在不在 —— 这是机制层的读数
foc0()  { sh dumpsys window displays | awk '/Display: mDisplayId=0 /,/mFocusedApp=/' | grep -m1 mCurrentFocus | grep -oE "null|[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+" | head -1; }
anrs()  { sh logcat -d -b events | grep -c am_anr; }
naps()  { python -c "import time;time.sleep($1)"; }

# 机主在打电话 / 屏幕被贴脸传感器关掉时不能测:量到的每一个 false 都不是我们造成的。
idle_check() {
  local W=$(sh dumpsys power | grep -cE "multi-talk|ILinkVoIPSmallView|'AudioIn'")
  [ "$W" -gt 0 ] && { echo "手机正在通话,现在测不了(也不该去打扰)"; exit 1; }
  sh input -d 0 keyevent 224 >/dev/null 2>&1; naps 1.5
  sh dumpsys display | grep -q "state ON" || { echo "主屏点不亮,可能锁着或贴脸中"; exit 1; }
}

# 把机主的真实输入法叫出来。不走本 app 的无障碍服务:
#   一来它现在是关的(和微信支付冲突),二来用一个会被待测现象弄坏的东西当载体本身就不对。
# 用 uiautomator 找 Edge 地址栏的真实坐标,再用真实触摸点进去。
arm() {
  sh dumpsys activity activities | grep -m1 topResumedActivity | grep -q emmx || {
    sh am start --display 0 -n $EDGE >/dev/null 2>&1; naps 5; }
  for _ in 1 2 3; do
    sh uiautomator dump $LOG.xml >/dev/null 2>&1
    local B=$("$ADB" shell cat $LOG.xml 2>/dev/null | tr '<' '\n' \
      | grep -iE 'resource-id="[^"]*(url_bar|search_box|location_bar)' \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 \
      | grep -oE '[0-9]+' | tr '\n' ' ')
    [ -z "$B" ] && { naps 2; continue; }
    set -- $B
    sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1
    naps 2.5
    [ "$(shown)" = "true" ] && return 0
  done
  return 1
}

# 跑一组:名字 associationId imePolicy flags [虚拟设备自带的输入法组件]
#   associationId 传负数 = 对照组(不建虚拟设备)
run_case() {
  local NAME="$1" A="$2" P="$3" F="$4" I="${5:--}"
  sh "for p in \$(ps -A -o PID,NAME | grep app_process | awk '{print \$1}'); do kill -9 \$p; done" >/dev/null 2>&1
  sh rm -f $LOG >/dev/null 2>&1
  "$ADB" shell "nohup sh -c 'CLASSPATH=$DEX app_process /data/local/tmp ai.whalephone.VdmDisplay $A 120 $P $F $I > $LOG 2>&1' >/dev/null 2>&1 &" >/dev/null 2>&1
  local D=""
  for _ in $(seq 1 15); do
    naps 1
    D=$("$ADB" shell cat $LOG 2>/dev/null | grep -oE "HOLD displayId=[0-9]+" | grep -oE "[0-9]+$")
    [ -n "$D" ] && break
  done
  [ -z "$D" ] && { printf "  %-30s 造屏失败\n" "$NAME"; "$ADB" shell cat $LOG 2>/dev/null | tail -3; return; }
  local POL=$("$ADB" shell cat $LOG 2>/dev/null | grep -c "setDisplayImePolicy.*成功")

  arm || { printf "  %-26s 输入法没叫起来,跳过
" "$NAME"; return; }
  local A0=$(anrs) S0=$(shown) W0=$(showon) F0=$(fdisp) C0=$(foc0)

  # 第一次:把 App 启到副屏 —— 这是唯一实测会收键盘的操作
  sh am start --display $D --activity-multiple-task -n $CALC >/dev/null 2>&1
  naps 3
  local S1=$(shown) W1=$(showon) F1=$(fdisp) C1=$(foc0)
  # 第二次:副屏内部再来一次窗口切换 —— 任务跑起来后这种切换比启动频繁得多,
  # 只测第一次会低估打扰次数(之前 true->false 只数得到第一下)
  sh am start --display $D --activity-multiple-task -n $EDGE >/dev/null 2>&1
  naps 3
  local S2=$(shown) F2=$(fdisp) A1=$(anrs)

  printf "  %-26s 屏=%-2s 键盘 %s→%s→%s  顶层焦点屏 %s→%s→%s  主屏焦点窗口 %s→%s  IME归属 %s→%s  ANR+%d  %s
"     "$NAME" "$D" "$S0" "$S1" "$S2" "$F0" "$F1" "$F2" "$C0" "$C1" "$W0" "$W1" "$((A1-A0))"     "$([ "$S1" = "true" ] && [ "$S2" = "true" ] && echo '没被打扰 ✓' || echo '键盘被收起 ✗')"

  sh am force-stop com.microsoft.emmx >/dev/null 2>&1
  sh am force-stop com.sec.android.app.popupcalculator >/dev/null 2>&1
  sh "for p in \$(ps -A -o PID,NAME | grep app_process | awk '{print \$1}'); do kill -9 \$p; done" >/dev/null 2>&1
  naps 2
}

idle_check
echo "== 副屏上启 App 的那一刻,机主的键盘还在不在 =="
# IME 策略一律用 FALLBACK(1):AOSP 里 HIDE 会直接调 hideCurrentInputLocked,
# 那是主动收键盘,拿它当实验组等于自己给自己下毒。
run_case "老路 · 带 OWN_FOCUS"      -1     1 0x5e08
run_case "老路 · 去掉 OWN_FOCUS"    -1     1 0x1e08
run_case "虚拟设备 · 带 OWN_FOCUS"  $ASSOC 1 0xde08
run_case "虚拟设备 · 去 OWN_FOCUS"  $ASSOC 1 0x9e08
echo
sh am start --display 0 -n $EDGE >/dev/null 2>&1
echo "读法:「主屏焦点窗口」从一个窗口变成 null 的那几行,就是 OWN_FOCUS 没生效 ——"
echo "      键盘被收和 ANR 都是它的后果。四行全 ✗ 的话,说明焦点这条路在这台 ROM 上走不通,"
echo "      得改成「机主在打字时不做窗口切换」的调度解法。"
