#!/usr/bin/env bash
# 任务收尾之后,机主那块屏有没有获焦窗口。
#
# 为什么单独测这个:副屏按设计在任务结束后保留(跨任务复用,少一次抖动),
# 而副屏上有可获焦窗口,它会一直占着全局焦点。于是主屏 mCurrentFocus=null
# 长期存在 —— 机主下一次按键正落在「Application does not have a focused window」上,
# 也就是「应用未响应」。真机上实测到过这个状态:任务跑完、机主的 App 还好好在屏上,
# 但主屏没有获焦窗口,看不出任何异常。
#
# 这条不能靠整任务测试顺带覆盖:那些脚本自己会往主屏 input,一注入焦点就回来了,
# 于是永远量不到这个残留状态。必须跑完之后什么都别碰,直接读。
#
# 同时盯 ANR:还焦点这个机制被否过一次,当时的错法是任务进行中每 250ms 灌一次空按键,
# 而那本身是输入事件,主屏没获焦窗口时送不进去,派发超时 5 秒就是一次 ANR。
# 所以「焦点回来了」和「没制造 ANR」必须一起成立,只看前者就是重蹈覆辙。
#
# 注意:这一条**在模拟器上是空过的**。纯 AOSP 的副屏在任务结束后不留可获焦窗口,
# 主屏焦点根本没丢,日志会说「主屏本来就有焦点,不动」—— 全绿但什么都没验到。
# 丢焦点是三星特有的:DeX 会往受信副屏上常驻一个桌面/任务栏。所以这个脚本要在真机上跑。
#
# 用法: bash scripts/tests/test-focus-return.sh [轮数]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
N="${1:-3}"
GOAL="${GOAL:-打开时钟,告诉我现在几点}"
naps() { python -c "import time;time.sleep($1)"; }

foc0()  { sh dumpsys window displays | awk '/Display: mDisplayId=0 /,/mCurrentFocus=/' \
            | grep -m1 mCurrentFocus | sed 's/.*mCurrentFocus=//' | tr -d '\r'; }
anrs()  { sh logcat -d -b events 2>/dev/null | grep -c am_anr; }

PASS=0; FAIL=0
ok() { PASS=$((PASS+1)); printf "   ✓ %s\n" "$1"; }
no() { FAIL=$((FAIL+1)); printf "   ✗ %s\n" "$1"; }

echo "== 起点 =="
echo "   主屏焦点: $(foc0)"
A0=$(anrs); echo "   已有 ANR: $A0"
echo

for i in $(seq 1 "$N"); do
  echo "== 第 $i 轮 =="
  sh logcat -c
  bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1
  for _ in $(seq 1 90); do naps 2; sh logcat -d -s WPSvc:* | grep -q "收工:" && break; done
  # 收尾之后什么都不碰,直接读。任何一次 input 注入都会把焦点带回来,读数就废了。
  naps 3
  F=$(foc0)
  sh logcat -d -s WPPriv:* | grep -E "主屏(原本|本来)" | sed 's/^.*WPPriv *: /     /' | tail -1
  if [ -n "$F" ] && [ "$F" != "null" ]; then ok "主屏有获焦窗口: $F"
  else no "主屏没有获焦窗口($F)—— 机主下一次按键就可能 ANR"; fi
done

echo
A1=$(anrs)
echo "== 结论 =="
echo "   期间新增 ANR: $((A1-A0))  (要求 0 —— 还焦点这个动作本身不能制造 ANR)"
[ "$((A1-A0))" -eq 0 ] && ok "没有制造新的 ANR" || no "制造了 $((A1-A0)) 次 ANR,这个机制又走回老路了"
echo "   $PASS 条过,$FAIL 条不过"
[ "$FAIL" -eq 0 ] || exit 1
