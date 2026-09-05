#!/usr/bin/env bash
# 任务进行中,机主那块屏有多长时间处于「没有获焦窗口」。
#
# 这不是一个通过/失败的测试,是一次**测量** —— 用来决定要不要在任务中间补还焦点。
#
# 为什么要先量再改:「每次抢完焦点就还回去」这个形状,正是当年 A/B 测出会造成 ANR
# 的那个模式(还回去、再抢走、反复;往主屏注入本身就是输入事件,主屏没有获焦窗口时
# 送不进去,派发超时就是 ANR)。收尾那一次安全是因为之后没人再抢。所以这条改动
# 不能凭推理做,得先知道它要解决的窗口到底有多大。
#
# 采样期间**不碰主屏**:任何一次 input -d 0 都会把焦点带回来,读数就废了。
#
# 用法: bash scripts/tests/measure-focus-gap.sh ["任务"]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
GOAL="${1:-打开时钟,告诉我现在几点}"
naps() { python -c "import time;time.sleep($1)"; }
foc0() { sh dumpsys window displays | awk '/Display: mDisplayId=0 /,/mCurrentFocus=/' \
           | grep -m1 mCurrentFocus | sed 's/.*mCurrentFocus=//' | tr -d '\r'; }

echo "== 起点:主屏焦点 = $(foc0) =="
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

T0=$(date +%s); NULLS=0; TOTAL=0; FIRST=""; LAST=""
for _ in $(seq 1 400); do
  F=$(foc0); NOW=$(( $(date +%s) - T0 )); TOTAL=$((TOTAL+1))
  if [ -z "$F" ] || [ "$F" = "null" ]; then
    NULLS=$((NULLS+1)); [ -z "$FIRST" ] && { FIRST=$NOW; echo "   [${NOW}s] 主屏开始没有获焦窗口"; }
    LAST=$NOW
  fi
  sh logcat -d -s WPSvc:* | grep -q "收工:" && break
done
DUR=$(( $(date +%s) - T0 ))

echo
echo "== 测量结果 =="
echo "   任务总时长        ${DUR}s"
echo "   采样次数          $TOTAL,其中主屏无获焦窗口 $NULLS 次"
[ -n "$FIRST" ] && echo "   失焦窗口          第 ${FIRST}s 到第 ${LAST}s" || echo "   失焦窗口          没出现过"
echo "   占比              $(python -c "print(f'{$NULLS/max($TOTAL,1)*100:.0f}%')")"
echo "   收尾后主屏焦点    $(foc0)"
sh logcat -d -s WPSvc:* | grep "主屏" | sed 's/^.*WPSvc *: /   /' | tail -1
echo
echo "   占比接近 0 说明这个风险窗口本来就不存在,第 1 条改动没必要冒 ANR 的险;"
echo "   占比高说明任务全程机主一碰屏幕就可能 ANR,那才值得做 A/B。"
