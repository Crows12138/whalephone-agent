#!/usr/bin/env bash
# 验收:「应用未响应」和「输入法被收起」这两个问题到底解没解决。
#
# 分三层,一层比一层强,缺任何一层都不算解决:
#   1. 判据本身可不可信 —— 「机主在打字」这个判断读得到吗(两路信号是否一致)
#   2. 机制有没有触发   —— 机主键盘弹着的时候 agent 真的停手了吗
#   3. 结果对不对       —— 整轮任务下来 ANR 和键盘被收起各是几次
#
# 为什么要分开:整轮跑完只看到「键盘没被收起」是不够的。如果判据根本读不到,
# 机制一次都没触发,而那一轮机主恰好没打字,读数也是漂亮的 —— 这个项目已经在
# 这种假绿灯上栽过一次(数「字有没有丢」,26/26 通过,真手指一试就断)。
#
# 用法: bash scripts/tests/verify-fix.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
SVC=$A/.EyesAndHands
APK="$WROOT/app/build/outputs/apk/debug/app-debug.apk"
naps() { python -c "import time;time.sleep($1)"; }

"$ADB" get-state >/dev/null 2>&1 || { echo "手机没连上(USB 和无线调试都没有),这个验收跑不了"; exit 1; }

echo "== 0. 装新包 =="
"$ADB" install -r "$APK" 2>&1 | tr -d '\r' | tail -1
"$ADB" shell pidof rikka.shizuku.server >/dev/null 2>&1 || sh pidof shizuku_server >/dev/null 2>&1 \
  || echo "   注意:Shizuku 服务好像没在跑,特权桥会连不上"

# 无障碍现在是关的(和微信支付冲突,见 TECH-CHOICES 已知边界)。
# 测完还原成原样,不把机主的设置改掉。
PREV=$(sh settings get secure enabled_accessibility_services)
PREV_EN=$(sh settings get secure accessibility_enabled)
restore() {
  if [ "$PREV" = "null" ]; then sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
  else sh settings put secure enabled_accessibility_services "$PREV" >/dev/null 2>&1; fi
  sh settings put secure accessibility_enabled "${PREV_EN/null/0}" >/dev/null 2>&1
  echo "   无障碍已还原成测试前的样子($PREV)"
}
trap restore EXIT
sh settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
naps 3
sh logcat -c

echo
echo "== 1. 判据可不可信 =="
echo "-- 键盘没弹的时候,两路都该说 false --"
sh am broadcast -a $A.TYPING >/dev/null 2>&1; naps 2
sh logcat -d -s WPEyes:* | sed -n '/---- TYPING ----/,$p' | sed 's/^/   /' | tail -4

echo "-- 把机主的真实输入法叫出来,两路都该说 true --"
sh dumpsys activity activities | grep -m1 topResumedActivity | grep -q emmx || {
  sh am start --display 0 -n com.microsoft.emmx/com.microsoft.ruby.Main >/dev/null 2>&1; naps 5; }
for _ in 1 2 3; do
  sh uiautomator dump /sdcard/wp.xml >/dev/null 2>&1
  B=$("$ADB" shell cat /sdcard/wp.xml 2>/dev/null | tr '<' '\n' \
      | grep -iE 'resource-id="[^"]*(url_bar|search_box|location_bar)' \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
  [ -n "$B" ] && { set -- $B; sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1; naps 2.5; }
  sh dumpsys input_method | grep -q "mInputShown=true" && break
done
sh dumpsys input_method | grep -q "mInputShown=true" || { echo "   输入法没叫起来,后面两层测不了"; exit 1; }
sh logcat -c
sh am broadcast -a $A.TYPING >/dev/null 2>&1; naps 2
sh logcat -d -s WPEyes:* | sed -n '/---- TYPING ----/,$p' | sed 's/^/   /' | tail -4

echo
echo "== 2 + 3. 让路有没有触发,结果对不对 =="
echo "   (键盘保持弹出,跑一轮真任务;这一步要几分钟)"
bash "$(dirname "${BASH_SOURCE[0]}")/test-ime.sh"
