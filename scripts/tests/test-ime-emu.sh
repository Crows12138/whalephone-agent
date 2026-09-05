#!/usr/bin/env bash
# 模拟器版的验收:机主的键盘弹着,agent 在副屏上跑完一整个任务。
#
# 和真机版 test-ime.sh 量的是同样三个数:ANR 次数、键盘被收起次数、agent 主动让路次数。
# 差别只在载体 —— 模拟器上没有 Edge 和淘宝,机主用「设置」的搜索框顶着键盘,
# agent 去时钟里加闹钟。两边不同 App,避开 Conflict.userIsUsing 那条保护。
#
# 用法: bash scripts/tests/test-ime-emu.sh ["任务"]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
GOAL="${1:-打开 Clock,依次看一下 Alarm、Timer、Stopwatch 三个标签,告诉我每个标签上有什么}"   # 模拟器是英文环境,App 显示名得用英文
TYPING_S="${TYPING_S:-25}"      # 机主「打字」多久
ARM_AFTER="${ARM_AFTER:-10}"    # 任务开始多少秒后机主才开始打字                                  # 机主「打字」多久
naps()  { python -c "import time;time.sleep($1)"; }
shown() { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
focus() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }

# 真实场景是:agent 已经在干活了,机主这时才拿起手机开始打字。
# 先让任务跑起来,过 ARM_AFTER 秒再把键盘顶起来,只统计这段窗口内的掉落。
# (先顶键盘再开任务只能测到「造屏」那一处;跑起来之后的每一次页面跳转才是大头。)
arm() {
  # 必须带 --display 0。不带的话 am 会启到**当前顶层焦点屏**上,而 agent 干活时
  # 那正是副屏 —— 等于测试自己把设置塞进了 agent 的屏,agent 就此看不懂自己在哪。
  sh am start --display 0 -a android.settings.SETTINGS >/dev/null 2>&1; naps 3
  for _ in 1 2 3; do
    sh uiautomator dump /sdcard/wp.xml >/dev/null 2>&1
    B=$("$ADB" shell cat /sdcard/wp.xml 2>/dev/null | tr '<' '
' | grep 'class="android.widget.EditText"' | grep -oE 'bounds="..[0-9]+,[0-9]+..[0-9]+,[0-9]+.."' | head -1 | grep -oE '[0-9]+' | tr '
' ' ')
    [ -n "$B" ] && { set -- $B; sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1; naps 2; }
    [ "$(shown)" = "true" ] && return 0
  done
  return 1
}

echo "== 开跑(机主先不打字)=="
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

T0=$(date +%s)
DROPS=0; AFTER=0; STEAL=0; PREV=$(shown); ARMED=0; CLOSED=0
for i in $(seq 1 600); do
  NOW=$(( $(date +%s) - T0 ))
  if [ "$ARMED" = "0" ] && [ "$NOW" -ge "$ARM_AFTER" ]; then
    if arm; then echo "   [${NOW}s] 机主开始打字了(键盘弹出)"; ARMED=1; PREV=true
    else echo "   [${NOW}s] 键盘没顶起来,这轮测不到东西"; ARMED=2; fi
    continue
  fi
  if [ "$ARMED" = "1" ] && [ "$CLOSED" = "0" ] && [ "$NOW" -ge "$((ARM_AFTER+TYPING_S))" ]; then
    echo "   [${NOW}s] 机主打完字,自己收起键盘(之后的掉落不算 agent 头上)"
    sh input -d 0 keyevent 111 >/dev/null 2>&1; CLOSED=1; naps 1; PREV=$(shown); continue
  fi
  M=$(shown); F=$(focus)
  if [ "$PREV" = "true" ] && [ "$M" = "false" ]; then
    if [ "$ARMED" = "1" ] && [ "$CLOSED" = "0" ]; then
      DROPS=$((DROPS+1)); echo "   [${NOW}s] 机主还在打字,键盘却被收起(第 $DROPS 次)  焦点屏=$F"
    else AFTER=$((AFTER+1)); fi
  fi
  [ -n "$F" ] && [ "$F" != "0" ] && STEAL=$((STEAL+1))
  PREV="$M"
  sh logcat -d -s WPSvc:* | grep -q "结束 done=" && { echo "   [${NOW}s] 任务结束"; break; }
  naps 0.4
done

echo
echo "== agent 做了什么 =="
sh logcat -d -s WPAgent:* | grep -oE "第 [0-9]+ 步 [a-z_]+" | tr '
' ' '; echo
sh logcat -d -s WPSvc:* | grep "结束 done=" | sed -E 's/.*WPSvc  *: /   /'
echo
YIELDS=$(sh logcat -d -s WPConflict:* 2>/dev/null | grep -c "机主在打字")
ANRS=$(sh logcat -d -b events 2>/dev/null | grep -c "am_anr")
echo "== 结论 =="
echo "   ANR 次数            $ANRS   (要求 0)"
echo "   打字期间键盘被收起  $DROPS   (要求 0 —— 这一项才是「有没有打扰机主」)"
echo "   打字结束后被收起    $AFTER  (不算 agent 头上)"
echo "   agent 主动让路次数  $YIELDS  (为 0 说明闸门没触发,这轮不算数)"
echo "   焦点不在主屏的采样  $STEAL"
echo "   收尾 mInputShown=$(shown)  焦点屏=$(focus)"
