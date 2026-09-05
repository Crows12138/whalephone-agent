#!/usr/bin/env bash
# 在模拟器(纯 AOSP Android 16)上验证根因链和「机主在打字」这个判据。
#
# 为什么要在模拟器上做:真机不在手边的时候,这是唯一能跑的底座。
#
# 它能验什么:
#   - 副屏上出现可获焦窗口 → 主屏丢焦点 → 机主的软键盘被收起。这条链全是 AOSP 行为
#     (DisplayContent.findFocusedWindowIfNeeded / IMMS),模拟器如实复现,而且比三星
#     ROM 更接近教科书版本。
#   - Conflict.ownerTyping() 靠的那个判据 —— 无障碍窗口列表里有没有 TYPE_INPUT_METHOD。
#     这也是 AOSP 行为。这一条是整个修复里唯一没被量过的假设,也是最该先量的。
#
# 它验不了什么(必须在真机上补):
#   - 三星 One UI 的差异(DeX 会自动往受信副屏上放桌面,那本身就是个抢焦点的窗口)
#   - Shizuku 那条特权路径(模拟器有 root,走的不是同一条)
#   - 讯飞输入法等第三方 IME 的行为
#
# 用法: bash scripts/tests/verify-on-emulator.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
SVC=$A/.EyesAndHands
APK="$WROOT/app/build/outputs/apk/debug/app-debug.apk"
DEX=/data/local/tmp/vdmdisplay.dex
naps() { python -c "import time;time.sleep($1)"; }

sh getprop ro.kernel.qemu 2>/dev/null | grep -q 1 || \
  sh getprop ro.build.characteristics 2>/dev/null | grep -q emulator || {
    echo "连的不是模拟器。这个脚本只在模拟器上跑 —— 真机请用 verify-fix.sh"; exit 1; }

shown() { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
foc0()  { sh dumpsys window displays | awk '/Display: mDisplayId=0 /,/mFocusedApp=/' \
            | grep -m1 mCurrentFocus | grep -oE "null|[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+" | head -1; }

echo "== 准备 =="
"$ADB" root >/dev/null 2>&1; naps 2
"$ADB" install -r "$APK" 2>&1 | tr -d '\r' | tail -1
"$ADB" push "$WROOT/probe/out3/classes.dex" $DEX >/dev/null 2>&1
# adb root 会重启 adbd,紧跟着的 settings put 会静默丢掉(实测:读回是 null)。
# 写完必须读回来确认,不然后面整段都在测一个没启用的服务,而且看不出来。
for _ in 1 2 3 4 5; do
  sh settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
  sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
  naps 2
  [ "$(sh settings get secure enabled_accessibility_services)" = "$SVC" ] && break
done
[ "$(sh settings get secure enabled_accessibility_services)" = "$SVC" ] || {
  echo "无障碍服务没启用起来,后面测不了"; exit 1; }
naps 3

echo
echo "== 1. 判据:无障碍报不报 IME 窗口 =="
# 键盘没弹的时候
sh logcat -c; sh am broadcast -a $A.TYPING >/dev/null 2>&1; naps 2
echo "-- 键盘没弹 --"; sh logcat -d -s WPEyes:* | sed -n '/---- TYPING ----/,$p' | sed 's/^/   /' | tail -4

# 把键盘叫出来:用系统设置里的搜索框,模拟器上一定有
sh am start -a android.settings.SETTINGS >/dev/null 2>&1; naps 4
sh uiautomator dump /sdcard/wp.xml >/dev/null 2>&1
B=$("$ADB" shell cat /sdcard/wp.xml 2>/dev/null | tr '<' '\n' | grep -iE 'class="android.widget.EditText"|search' \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
[ -n "$B" ] && { set -- $B; sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1; naps 3; }
[ "$(shown)" != "true" ] && { echo "   键盘没叫起来(mInputShown=$(shown)),后面测不了"; exit 1; }
sh logcat -c; sh am broadcast -a $A.TYPING >/dev/null 2>&1; naps 2
echo "-- 键盘弹着 --"; sh logcat -d -s WPEyes:* | sed -n '/---- TYPING ----/,$p' | sed 's/^/   /' | tail -4
echo "   ↑ 这两组里「无障碍」那一行必须分别是 false / true。都是 false 的话,"
echo "     让路机制会静默失效,只能靠 IMMS 兜底那条路。"

echo
echo "== 2. 根因链:建一块副屏,主屏会不会丢焦点、键盘会不会被收 =="
printf "   建屏前   主屏焦点=%-42s 键盘=%s\n" "$(foc0)" "$(shown)"
"$ADB" shell "nohup sh -c 'CLASSPATH=$DEX app_process /data/local/tmp ai.whalephone.VdmDisplay -1 60 1 0x1e08 > /data/local/tmp/vd.log 2>&1' >/dev/null 2>&1 &" >/dev/null 2>&1
for _ in $(seq 1 15); do naps 1
  D=$("$ADB" shell cat /data/local/tmp/vd.log 2>/dev/null | grep -oE "HOLD displayId=[0-9]+" | grep -oE "[0-9]+$")
  [ -n "$D" ] && break; done
[ -z "$D" ] && { echo "   造屏失败"; "$ADB" shell cat /data/local/tmp/vd.log | tail -3; exit 1; }
naps 3
printf "   建屏后   主屏焦点=%-42s 键盘=%s   (副屏=%s)\n" "$(foc0)" "$(shown)" "$D"
# 副屏上启个 App —— 这才是真正会出现可获焦窗口的一步
sh am start --display $D --activity-multiple-task -a android.settings.SETTINGS >/dev/null 2>&1
naps 4
printf "   启App后  主屏焦点=%-42s 键盘=%s\n" "$(foc0)" "$(shown)"
sh "for p in \$(ps -A -o PID,NAME | grep app_process | awk '{print \$1}'); do kill -9 \$p; done" >/dev/null 2>&1
naps 3
printf "   释放后   主屏焦点=%-42s 键盘=%s\n" "$(foc0)" "$(shown)"
echo
echo "   读法:主屏焦点变成 null 且键盘变 false,就复现了根因;那也正是让路机制要躲开的那一刻。"
echo "        如果在模拟器上主屏焦点**不**变 null,说明这台 ROM 的行为和三星不同,"
echo "        那结论只对三星成立,得在真机上重测 —— 别把模拟器的结果当成真机的结果。"
