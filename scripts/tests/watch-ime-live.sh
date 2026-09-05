#!/usr/bin/env bash
# 机主自然使用手机的同时跑一个任务,采他的键盘状态。
#
# 和 test-ime.sh 的区别:这个脚本**一次都不碰主屏**。不拉 Edge、不点输入框、
# 不模拟打字。机主该干嘛干嘛,他碰巧打字了就采到一个真数据点,没打字就报「这轮没测到」。
#
# 为什么值得单独有一份:合成测试永远有一个洗不掉的疑点 —— 是不是测试自己制造的
# 现象。这个项目已经栽过一次(拿本 app 的输入框当载体,而它正因为待测现象在 ANR)。
# 机主真手指打的字没有这个问题,而且 FINDINGS 里写明了 ANR 的触发条件就是
# 「机主的手指碰屏幕」,合成注入根本触发不到。
#
# 代价是它不保证测到:机主不打字这一轮就是空的。所以它补充 test-ime.sh,不替代。
#
# 用法: bash scripts/tests/watch-ime-live.sh ["任务"] [最长秒数]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
GOAL="${1:-打开计算器,算一下 128 乘 47 等于多少}"
MAX="${2:-300}"
ime()  { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
anrs() { sh logcat -d -b events 2>/dev/null | grep -c am_anr; }

A0=$(anrs)
echo "== 开跑(全程不碰主屏)=="
echo "   机主前台: $(sh dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+' | head -1)"
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

T0=$(date +%s); TYPED=0; DROPS=0; PREV=$(ime); UP=0
while [ $(( $(date +%s) - T0 )) -lt "$MAX" ]; do
  M=$(ime); NOW=$(( $(date +%s) - T0 ))
  [ "$M" = "true" ] && { TYPED=1; UP=$((UP+1)); }
  # true -> false 才算掉落。机主自己收键盘也会走这一支,所以下面要把
  # 「agent 那时在不在动手」一起报出来,不能光看次数。
  if [ "$PREV" = "true" ] && [ "$M" = "false" ]; then
    DROPS=$((DROPS+1))
    echo "   [${NOW}s] 键盘从开变关(第 $DROPS 次)  此刻 agent 在:$(sh logcat -d -s WPAgent:* | grep -oE '第 [0-9]+ 步 [a-z_]+' | tail -1)"
  fi
  PREV="$M"
  sh logcat -d -s WPSvc:* | grep -q "收工:" && break
done
DUR=$(( $(date +%s) - T0 ))

echo
echo "== 结果 =="
echo "   任务时长          ${DUR}s"
echo "   机主键盘弹着的采样 $UP 次"
YIELDS=$(sh logcat -d -s WPConflict:* 2>/dev/null | grep -c "机主在打字")
echo "   agent 主动让路    $YIELDS 次"
echo "   键盘从开变关      $DROPS 次"
echo "   新增 ANR          $(( $(anrs) - A0 ))"
sh logcat -d -s WPSvc:* | grep "收工:" | sed 's/^.*WPSvc *: /   /' | tail -1
echo
if [ "$TYPED" = "0" ]; then
  echo "   这一轮机主全程没打字 —— 没测到东西。不是「通过」,是「没测」,两者要分清。"
elif [ "$DROPS" -eq 0 ]; then
  echo "   机主打字期间键盘一次都没被收起 ✓(而且是他真手指打的字)"
else
  echo "   有 $DROPS 次掉落。对着上面每一次的时间点看 agent 当时在做什么 ——"
  echo "   机主自己收键盘也会计进来,要人工分辨,这个脚本不替你下结论。"
fi
