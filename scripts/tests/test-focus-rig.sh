#!/usr/bin/env bash
# 焦点机制的密闭试验台:用两块虚拟屏当替身,全程不碰机主的主屏。
#
# 要回答的是一个纯机制问题:一块**没有** OWN_FOCUS 的屏,在另一块更晚创建的屏
# 拿到焦点窗口时,会不会丢掉自己的焦点窗口?
#   会丢  → 机主的主屏也一样会丢(主屏没法加 OWN_FOCUS,那是造屏时的 flag)。
#           而「主屏没有焦点窗口」正是 ANR 和输入法被收的共同成因,焦点这条路就是死的。
#   不会丢 → 主屏能保住焦点,那 IMMS 那边拒收副屏的上报,输入法被收就另有原因。
# 两种结果都能把假设砍掉一半,所以这个试验值得做。
#
# A 屏 = 机主的替身(不带 OWN_FOCUS,其余和 agent 屏一样)
# B 屏 = agent 屏(带 OWN_FOCUS)
# 三星会自动往带系统装饰的受信屏上放 DeX 的 SecondaryLauncher,所以两块屏都会
# 自带一个真实的可获焦窗口 —— 不需要我再去启什么东西,也就不会打扰到机主。
#
# 主屏的焦点窗口全程当安全阀读:它一变 null 就立刻收手。
#
# 用法: bash scripts/tests/test-focus-rig.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
DEX=/data/local/tmp/vdmdisplay.dex
ASSOC=${ASSOC:-5}

naps()  { python -c "import time;time.sleep($1)"; }
fdisp() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }
shown() { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
# 某块屏自己的焦点窗口(null 表示这块屏没有焦点窗口)
focof() { sh dumpsys window displays | awk -v d="Display: mDisplayId=$1 " \
            '$0 ~ d,/mFocusedApp=/' | grep -m1 mCurrentFocus \
            | grep -oE "null|[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+" | head -1; }
kill_all() { sh "for p in \$(ps -A -o PID,NAME | grep app_process | awk '{print \$1}'); do kill -9 \$p; done" >/dev/null 2>&1; }

start_display() {   # 日志文件 associationId flags -> 打印 displayId
  local L="$1" A="$2" F="$3"
  sh rm -f "$L" >/dev/null 2>&1
  "$ADB" shell "nohup sh -c 'CLASSPATH=$DEX app_process /data/local/tmp ai.whalephone.VdmDisplay $A 90 1 $F > $L 2>&1' >/dev/null 2>&1 &" >/dev/null 2>&1
  for _ in $(seq 1 15); do
    naps 1
    local D=$("$ADB" shell cat "$L" 2>/dev/null | grep -oE "HOLD displayId=[0-9]+" | grep -oE "[0-9]+$")
    [ -n "$D" ] && { echo "$D"; return; }
  done
  echo ""
}

report() { printf "  %-22s 主屏焦点=%-46s A屏焦点=%-46s B屏焦点=%-46s 顶层焦点屏=%-2s 键盘=%s\n" \
            "$1" "$(focof 0)" "${A:+$(focof $A)}" "${B:+$(focof $B)}" "$(fdisp)" "$(shown)"; }

guard() {  # 主屏一丢焦点就收手 —— 那既是安全阀,也正是答案
  [ "$(focof 0)" = "null" ] && { echo; echo "  !! 主屏丢了焦点窗口 —— 结论已经拿到,立刻收手"; kill_all; naps 2; report "收手后"; exit 0; }
}

kill_all
echo "== 焦点机制试验台(不碰主屏)=="
A=""; B=""; report "基线"
guard

A=$(start_display /data/local/tmp/vdA.log -1 0x1e08)
[ -z "$A" ] && { echo "  A 屏没造出来"; kill_all; exit 1; }
naps 3; report "建了A屏(无OWN_FOCUS)"; guard

B=$(start_display /data/local/tmp/vdB.log $ASSOC 0xde08)
[ -z "$B" ] && { echo "  B 屏没造出来"; "$ADB" shell cat /data/local/tmp/vdB.log | tail -3; kill_all; exit 1; }
naps 3; report "建了B屏(有OWN_FOCUS)"; guard

sh am start --display $B --activity-multiple-task -n com.sec.android.app.popupcalculator/.Calculator >/dev/null 2>&1
naps 4; report "往B屏启App"; guard

echo
echo "  读法:A 屏(机主的替身)在建 B 屏、往 B 屏启 App 之后还保不保得住自己的焦点窗口。"
echo "        保不住 → 主屏也保不住,焦点这条路死;保得住 → 输入法被收另有原因。"
sh am force-stop com.sec.android.app.popupcalculator >/dev/null 2>&1
kill_all; naps 2; report "全部释放后"
