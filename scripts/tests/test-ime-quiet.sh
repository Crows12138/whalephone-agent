#!/usr/bin/env bash
# 机主根本没在用手机的时候,agent 干活会不会把他的键盘**唤起来**。
#
# 这个方向之前一直没测。已有的 test-ime.sh 数的是 true -> false(键盘被收起),
# 而且是在机主打字、键盘本来就开着的前提下测的 —— 键盘再被唤起一次它根本看不见。
# 机主自己发现的:任务跑起来之后输入法自己冒出来又落下去。
#
# 机理不是我们调了输入法。整机只有一个 IME,`mDisplayIdToShowIme` 实测恒为 0,
# 所以**任何一块屏**上的输入框请求输入法,系统都只能弹到机主那块屏上。agent 点一下
# 淘宝搜索栏,搜索页给输入框自动对焦,机主眼前的键盘就自己冒出来。
# 解法是给副屏设 DISPLAY_IME_POLICY_HIDE,见 ShellBridge.hideImeOn。
#
# 用法: ANDROID_SERIAL=<设备> bash scripts/tests/test-ime-quiet.sh ["任务"]

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
# 必须是会点到搜索框的任务 —— 键盘被唤起来正是从「点搜索栏、页面给输入框自动对焦」
# 那一下开始的。任务绕过搜索框,这个脚本就等于什么都没测。
GOAL="${1:-淘宝上找个保温杯,挑个销量最高的,告诉我多少钱、哪家店}"
PASS=0; FAIL=0
ok() { PASS=$((PASS+1)); echo "   ✓ $1"; }
no() { FAIL=$((FAIL+1)); echo "   ✗ $1"; }
ime() { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }

echo "== 前提:机主没在用手机,键盘是落下的 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
sh input -d 0 keyevent 3 >/dev/null 2>&1
python -c "import time;time.sleep(1.5)"
[ "$(ime)" = "true" ] && { echo "   键盘收不掉,测不了"; exit 1; }
ok "起点:键盘是落下的"

# 副屏跨任务复用,而输入法策略是造屏那一刻设的 —— 上一轮留下的屏没走过新代码,
# 拿它测等于测了个旧东西。所以先把 app 停掉,逼这一轮重新造屏。
sh am force-stop $A >/dev/null 2>&1
sh am force-stop com.taobao.taobao >/dev/null 2>&1
python -c "import time;time.sleep(2)"
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

echo
echo "== agent 干活,机主一动不动 =="
PREV=false; UP=0; T0=$(date +%s)
for _ in $(seq 1 400); do
  M=$(ime); N=$(( $(date +%s) - T0 ))
  if [ "$PREV" = "false" ] && [ "$M" = "true" ]; then
    UP=$((UP+1))
    echo "   [${N}s] 键盘自己冒出来了(第 $UP 次)  agent 刚做完: $(sh logcat -d -s WPAgent:* | grep -oE '第 [0-9]+ 步 [a-z_]+' | tail -1)"
  fi
  PREV="$M"
  sh logcat -d -s WPSvc:* | grep -q "结束 done=" && break
  python -c "import time;time.sleep(0.3)"
done

echo
sh logcat -d -s WPAgent:* | grep -oE "第 [0-9]+ 步 [a-z_]+" | tr '\n' ' '; echo
DONE=$(sh logcat -d -s WPSvc:* | grep -m1 "结束 done=")
echo "   $(echo "$DONE" | sed -E 's/.*WPSvc   : //' | cut -c1-80)"

echo
echo "== 结论 =="
# 任务没跑起来的话「键盘 0 次」是白给的。这一条必须先过。
case "$DONE" in
  *done=true*) ok "任务真的跑完了(否则「一次没弹」是白给的)";;
  *)           no "任务没跑完,这轮读数不作数";;
esac
[ "$UP" -eq 0 ] && ok "全程没把机主的键盘唤起来" || no "把机主的键盘唤起了 $UP 次"
# 只看结果不够:0 次也可能是这一轮压根没点到输入框。要那条归因日志。
if sh logcat -d -s WPBridge:* | grep -q "输入法策略 .* -> 2"; then
  ok "是那条策略起的作用($(sh logcat -d -s WPBridge:* | grep -oE '副屏 [0-9]+ 输入法策略 .*' | tail -1))"
else
  no "没看到副屏输入法策略被设成 HIDE —— 就算这轮没弹,也不是因为它"
fi
echo "== $PASS 条过,$FAIL 条不过 =="
[ "$FAIL" -eq 0 ]
