#!/usr/bin/env bash
# 让路判据的**活动**那一半:「键盘开着」和「机主在打字」不是一回事。
#
# 这个脚本测四种情形,少了任何一种,结论都是假的:
#
#   A 机主敲过字然后停手      -> 放行,走「按过键」那个阈值(他可能攥着没上屏的字)
#   B 键盘开着、一个键没按过  -> 放行,走「没按过键」那个阈值(两个数默认相同,靠日志分辨)
#   C 只打拼音、不上屏,慢慢打 -> 全程让。这是中文输入的常态,也是这套判据栽过的地方
#   D 连打 75 秒              -> 全程让,不能到点动手
#
# 只测 A 会退化成「把阈值调小」,那等于放宽让路;只测 D 看不出停摆有没有解决;
# 少了 B 看不出两个阈值有没有各自生效;少了 C 就只测了英文式输入(每敲一下都上屏),
# 而中文输入根本不长那样 —— 真机上就是从这个缺口漏出去的。
# 所有读数都来自 YIELD 探针本身返回的毫秒数 —— 测的是上线的那个函数,不是它的副本。
#
# 用法: ANDROID_SERIAL=<设备> bash scripts/tests/test-idle-keyboard.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
IDLE_MS="${IDLE_MS:-10000}"      # 一个键都没按过时的空闲阈值
DRAFT_MS="${DRAFT_MS:-10000}"    # 按过键之后的空闲阈值(可能有没上屏的内容)
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
echo "== A. 机主敲过字后停手 —— 走「按过键」那个阈值($((DRAFT_MS/1000)) 秒)=="
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
# 只卡下界。上界不能卡:探针路径上 Thread.sleep(500) 实测被拉到过 48930 毫秒
# (三星对后台线程的调度限制),空闲时长因此会超出阈值几十秒,超多少取决于当时的
# 调度,和算法无关。「有没有走错档」这件事由下面那条日志断言,那条是精确的。
[ "$IDLE" -ge "$DRAFT_MS" ] && ok "空闲 ${IDLE} 毫秒 ≥ 阈值 ${DRAFT_MS}(墙上等了 ${W} 毫秒)"                             || no "空闲才 ${IDLE} 毫秒,不到阈值 ${DRAFT_MS} —— 提前放行了"
why | grep -q "按过键,等的是" && ok "放行日志写明了走「按过键」那档:$(why | grep -oE "他这次[^)]*")"                               || no "放行日志没说走「按过键」那档 —— 两档可能没分开"
why | grep -q "机主停手了" && ok "放行的理由是机主停手,不是等到上限" \
                          || no "放行的理由不是机主停手"
sh dumpsys input_method | grep -q "mInputShown=true" && ok "放行时机主的键盘还在(agent 让路不负责收键盘)" \
  || echo "   (键盘已经不在了,这一条不判)"

echo
echo "== B. 键盘开着但一个键都没按 —— 走「没按过键」那个阈值($((IDLE_MS/1000)) 秒)=="
# 先让判据**看见**键盘落下,它才会把「这次他按过键」清掉。判据只在让路时才跑,
# 所以专门打一发 YIELD 让它看一眼;键盘不在的时候这一发立刻返回。
sh input -d 0 keyevent 4 >/dev/null 2>&1
python -c "import time;time.sleep(1.5)"
bc -a $A.YIELD >/dev/null 2>&1
python -c "import time;time.sleep(2)"
raise_ime || { echo "输入法没起来,B 测不了"; exit 1; }   # 只点输入框,不打字
sh logcat -c
bc -a $A.YIELD >/dev/null 2>&1
for _ in $(seq 1 60); do
  sh logcat -d -s WPEyes:* | grep -q "让了" && break
  python -c "import time;time.sleep(1)"
done
W=$(waited_ms); [ -z "$W" ] && W=-1
echo "   探针报告让了 $W 毫秒;$(why)"
IDLE=$(idle_ms); [ -z "$IDLE" ] && IDLE=-1
[ "$IDLE" -ge "$IDLE_MS" ] && ok "空闲 ${IDLE} 毫秒 ≥ 阈值 ${IDLE_MS}"                            || no "空闲才 ${IDLE} 毫秒,不到阈值 ${IDLE_MS}"
# 走没走对档看日志,不看数字 —— 两个默认值现在相同,数字根本分不出档。
why | grep -q "一个键都没按,等的是" && ok "放行日志写明了走「没按过键」那档:$(why | grep -oE "他这次[^)]*")"                                     || no "放行日志没说走「没按过键」那档 —— 两档没分开"

echo
echo "== C. 慢慢打拼音、一直不上屏 —— 输入框一个字都不变,判据也得认得出他在打 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
raise_ime || { echo "输入法没起来,C 测不了"; exit 1; }
sh logcat -c
# fp() 会先 logcat -c,让路期间不能用它(会把「让了」那条证据一起清掉);
# 而且它调的 TYPING 探针会真的跑一遍判据,和正在跑的让路循环抢同一份状态。
# 所以只在让路**开始前**和**结束后**各读一次。
fp_nc() {
  bc -a $A.TYPING >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
  sh logcat -d -s WPEyes:* | grep "原始信号 活动" | tail -1 | sed -E 's/.*活动  : //' \
    | grep -oE "输入框[^;]*"
}
ED0=$(fp_nc)
sh logcat -c
bc -a $A.YIELD >/dev/null 2>&1
python -c "import time;time.sleep(1)"
echo "   每 3 秒打一个拼音字母,连打 30 秒,一次都不提交"
# 3 秒一个字母是关键:旧判据要求 5 秒内输入法窗口动 3 次,而一个字母只发约 2 条 ——
# 这个节奏下它认为没人在打字。同时这一串全程不上屏,检测器二(输入框指纹)也是瞎的。
# 所以这一段唯一能撑住的就是「输入法窗口最后一次动的时刻」那条,测的正是它。
BROKE=0
for c in n i h a o s h i j i; do
  sh input -d 0 text "$c" >/dev/null 2>&1
  python -c "import time;time.sleep(3)"
  sh logcat -d -s WPEyes:* | grep -q "让了" && { BROKE=1; break; }
done
[ "$BROKE" = "0" ] && ok "30 秒慢速拼音全程在让(每一下按键都被认出来了)" \
                   || no "打字中途就放行了 —— 慢打没被认出来,机主会被打断"
echo "   停手,等它放行"
for _ in $(seq 1 60); do
  sh logcat -d -s WPEyes:* | grep -q "让了" && break
  python -c "import time;time.sleep(1)"
done
W=$(waited_ms); [ -z "$W" ] && W=-1
echo "   探针报告让了 $W 毫秒;$(why)"
[ "$W" -ge 30000 ] && ok "总共让了 ${W} 毫秒(30 秒打字 + 阈值)" || no "只让了 ${W} 毫秒"
# 前提:这一串确实**没有**上屏。上屏了的话测的就是 A,不是 C。
ED1=$(fp_nc)
[ "$ED0" = "$ED1" ] && ok "前提成立:打了 10 个拼音字母,输入框内容一个字没变(没上屏)" \
                    || no "拼音上屏了,这一轮测的不是「组词中」:$ED0 -> $ED1"
echo
echo "== D. 机主连打 75 秒 —— 全程都得让,不能到让路上限就动手 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
raise_ime || { echo "输入法没起来,D 测不了"; exit 1; }
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
[ "$W" -ge $((75000+DRAFT_MS-15000)) ] && ok "连续打字期间一直在让(${W} 毫秒 = 75 秒打字 + 阈值等待)" \
                   || no "只让了 ${W} 毫秒 —— 机主还在打字就动手了"
IDLE=$(idle_ms); [ -z "$IDLE" ] && IDLE=-1
[ "$IDLE" -ge "$DRAFT_MS" ] && ok "放行时输入框确实已经 ${IDLE} 毫秒没动" \
                            || no "放行时输入框才 ${IDLE} 毫秒没动,不够长档 ${DRAFT_MS}"
why | grep -q "机主停手了" && ok "放行的理由是机主停手,不是等到上限" \
                          || no "放行的理由是等到上限 —— 那一下会打断他"

echo
sh input -d 0 keyevent 4 >/dev/null 2>&1
echo "== 结论:$PASS 条过,$FAIL 条不过 =="
[ "$FAIL" -eq 0 ]
