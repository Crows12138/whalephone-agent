#!/usr/bin/env bash
# 让路判据的**活动**那一半:「键盘开着」和「机主在打字」不是一回事。
#
# 这个脚本测两个方向,少了任何一个方向的结论都是假的:
#
#   A 机主停手了(键盘还开着) -> agent 应该在空闲阈值后放行,不是等满上限
#   B 机主一直在打(超过原来的 60 秒上限) -> agent 应该一直让,不能到点动手
#
# 只测 A 会退化成「把阈值调小」,那等于放宽让路;只测 B 看不出停摆有没有解决。
# 两个方向的读数都来自 YIELD 探针本身返回的毫秒数 —— 测的是上线的那个函数,
# 不是它的副本。
#
# 用法: ANDROID_SERIAL=<设备> bash scripts/tests/test-idle-keyboard.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
IDLE_MS="${IDLE_MS:-10000}"
PASS=0; FAIL=0
# 判据自己算出来的「输入框多久没动」。这个量只由两个绝对时间戳决定,
# 不受线程调度影响 —— 墙上时钟在这台机器上不可用(见下面 A 用例的注释)。
idle_ms()   { why | grep -oE "输入框已经 [0-9]+ 毫秒没动" | grep -oE "[0-9]+"; }
ok()  { PASS=$((PASS+1)); echo "   ✓ $1"; }
no()  { FAIL=$((FAIL+1)); echo "   ✗ $1"; }

# YIELD 探针会阻塞到放行为止,日志里给出让了多少毫秒。
# 在后台发广播,主线程负责在这段时间里模拟机主。
waited_ms() { sh logcat -d -s WPEyes:* 2>/dev/null | grep -oE "让了 [0-9]+ 毫秒后放行" | tail -1 | grep -oE "[0-9]+"; }
why()       { sh logcat -d -s WPConflict:* 2>/dev/null | grep -oE "机主在打字,让了 [0-9]+ 毫秒\(.*\)" | tail -1; }

# 这个脚本测的是探针,不是整轮任务,所以 A11yGate 不会被调用 —— 无障碍得自己开。
# 上一轮任务收工时 A11yGate 会把它关掉,不开的话 EyesAndHands 里的动态接收器
# 根本不存在,TYPING/YIELD 广播静默地没人收,读数是空的却看不出来。
SVC=ai.whalephone.agent/.EyesAndHands
PREV_SVC=$(sh settings get secure enabled_accessibility_services)
PREV_EN=$(sh settings get secure accessibility_enabled)
restore() {
  if [ "$PREV_SVC" = "null" ]; then sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
  else sh settings put secure enabled_accessibility_services "$PREV_SVC" >/dev/null 2>&1; fi
  sh settings put secure accessibility_enabled "${PREV_EN:-0}" >/dev/null 2>&1
}
trap restore EXIT
sh settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
for _ in $(seq 1 20); do
  sh logcat -d -s WPEyes:* 2>/dev/null | grep -q "无障碍服务已连接" && break
  python -c "import time;time.sleep(1)"
done
echo "   无障碍: $(sh settings get secure enabled_accessibility_services)"

echo "== 准备:叫起机主的输入法,确认活动信号读得到 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
raise_ime || { echo "输入法没起来,测不了"; exit 1; }
# 先敲一下,把输入框弄成有内容的状态,顺带确认注入这条路是通的。
owner_types
python -c "import time;time.sleep(1)"
sh logcat -c
bc -a $A.TYPING >/dev/null 2>&1
python -c "import time;time.sleep(2)"
sh logcat -d -s WPEyes:* | grep -E "原始信号 活动|判据结论" | sed -E 's/.*WPEyes  : /   /'
# 必须**看到**那一行才算数。原来写的是「没看到那句否定的话就算过」——
# 广播压根没人收的时候日志一行都没有,这条检查会真空通过,后面两个用例
# 全部对着空气跑。缺席不是证据。
K=$(sh logcat -d -s WPEyes:* | grep -m1 "原始信号 活动")
case "$K" in
  "")             echo "   TYPING 探针没有任何输出 —— 广播没人收,测不了"; exit 1 ;;
  *读不到机主的输入框*) echo "   信号没上膛 —— 读不到机主正在编辑的输入框,后面的用例不成立"; exit 1 ;;
esac
ok "活动信号可用(读得到机主的输入框)"

# 前提:敲一下,判据看到的指纹得真的变。变不了说明注入没进输入框 ——
# 真机上撞到过两次(Edge 地址栏没拿到输入焦点;讯飞把 ASCII 吃成拼音串不提交),
# 两次都是「读数照常、测的是空气」。所以这一条不过就不往下测。
fp() {
  sh logcat -c
  bc -a $A.TYPING >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
  sh logcat -d -s WPEyes:* | grep -m1 "原始信号 活动" | sed -E 's/.*活动  : //'
}
F1=$(fp); owner_types; python -c "import time;time.sleep(1)"; F2=$(fp)
echo "   敲之前: $F1"
echo "   敲之后: $F2"
[ "$F1" != "$F2" ] && ok "敲一下,判据看到的指纹跟着变了" || {
  no "敲了字但指纹没变 —— 注入没进输入框,后面测的是空气"; echo "== 结论:前提不成立,不往下测 =="; exit 1; }

echo
echo "== A. 机主停手,键盘留着 —— 应该在 $((IDLE_MS/1000)) 秒左右放行,不是 60 秒 =="
[ "$(sh dumpsys input_method | grep -c 'mInputShown=true')" -gt 0 ] || { no "键盘掉了,A 测不了"; }
sh logcat -c
T0=$(date +%s%3N)
bc -a $A.YIELD >/dev/null 2>&1
for _ in $(seq 1 180); do
  sh logcat -d -s WPEyes:* | grep -q "让了" && break
  python -c "import time;time.sleep(1)"
done
W=$(waited_ms); [ -z "$W" ] && W=-1
echo "   探针报告让了 $W 毫秒;$(why)"
# 卡的是**判据自己算出来的空闲时长**,不是墙上时钟。
#
# 墙上时钟在这台机器上不可用:实测 Thread.sleep(500) 会被拉到十几秒(三星对后台
# 线程的调度限制,判据本身只要 12~49 毫秒),让路总时长因此忽长忽短 —— 同一份代码
# 量出过 18 秒也量出过 70 秒。拿它当断言,过与不过取决于当时的调度,测不出算法。
# 判据里的空闲时长是两个绝对时间戳相减,不受调度影响,它才是要卡的那个量。
IDLE=$(idle_ms); [ -z "$IDLE" ] && IDLE=-1
if [ "$IDLE" -ge "$IDLE_MS" ] && [ "$IDLE" -le $((IDLE_MS+25000)) ]; then
  ok "按空闲阈值放行(判据算出空闲 ${IDLE} 毫秒,阈值 ${IDLE_MS};墙上等了 ${W} 毫秒,差额是线程被调度器拖的)"
else
  no "判据算出的空闲是 ${IDLE} 毫秒,期望 ${IDLE_MS}~$((IDLE_MS+25000))"
fi
why | grep -q "机主停手了" && ok "放行的理由是机主停手,不是等到上限" \
                          || no "放行的理由不是机主停手"
sh dumpsys input_method | grep -q "mInputShown=true" && ok "放行时机主的键盘还在(agent 让路不负责收键盘)" \
  || echo "   (键盘已经不在了,这一条不判)"

echo
echo "== B. 机主连打 75 秒 —— 全程都得让,不能到 60 秒上限就动手 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
raise_ime || { echo "输入法没起来,B 测不了"; exit 1; }
sh logcat -c
bc -a $A.YIELD >/dev/null 2>&1
python -c "import time;time.sleep(1)"
for i in $(seq 1 15); do            # 15 轮 x 5 秒 = 75 秒
  owner_types
  python -c "import time;time.sleep(5)"
done
echo "   停手,等它放行"
for _ in $(seq 1 60); do
  sh logcat -d -s WPEyes:* | grep -q "让了" && break
  python -c "import time;time.sleep(1)"
done
W=$(waited_ms); [ -z "$W" ] && W=-1
echo "   探针报告让了 $W 毫秒;$(why)"
[ "$W" -ge 70000 ] && ok "连续打字期间一直在让(${W} 毫秒 > 原来的 60 秒上限)" \
                   || no "只让了 ${W} 毫秒 —— 机主还在打字就动手了"
IDLE=$(idle_ms); [ -z "$IDLE" ] && IDLE=-1
[ "$IDLE" -ge "$IDLE_MS" ] && ok "放行时输入框确实已经 ${IDLE} 毫秒没动" \
                           || no "放行时输入框才 ${IDLE} 毫秒没动,不够阈值 ${IDLE_MS}"
why | grep -q "机主停手了" && ok "放行的理由是机主停手,不是等到上限" \
                          || no "放行的理由是等到上限 —— 那一下会打断他"

echo
sh input -d 0 keyevent 4 >/dev/null 2>&1
echo "== 结论:$PASS 条过,$FAIL 条不过 =="
[ "$FAIL" -eq 0 ]
