#!/bin/bash
cd "$(dirname "$0")/.."
source scripts/lib.sh
foc() { sh dumpsys input 2>/dev/null | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+"; }
dumpd() { sh uiautomator dump ${1:+--display $1} //sdcard/d.xml >/dev/null 2>&1
          "$ADB" shell cat //sdcard/d.xml 2>/dev/null > /tmp/d.xml
          grep -oE 'package="[^"]+"' /tmp/d.xml | sort -u | tr '\n' ' '; }

echo "启动虚拟屏 + 计算器(主屏保持知乎)..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/s9.log 2>&1 &
SPID=$!; sleep 8
VD=$(vdid); echo "VD=$VD  焦点=$(foc)"

echo; echo "════ 实验1:把焦点强制放到主屏,再试着读虚拟屏 ════"
sh input -d 0 tap 540 1200 >/dev/null; sleep 2
echo "  焦点 = $(foc)  (应该是 0)"
echo "  dump --display $VD  -> $(dumpd $VD)"
echo "  dump --display 0    -> $(dumpd 0)"
echo "  dump (不指定)        -> $(dumpd)"

echo; echo "════ 实验2:焦点在主屏时,agent 还能操作虚拟屏吗 ════"
echo "  当前焦点 = $(foc)"
BEF=$(sh dumpsys activity activities | grep -A6 "^Display #$VD" | grep -oE "topResumedActivity=.*" | head -1)
echo "  操作前虚拟屏顶部: $BEF"
echo "  --> 在虚拟屏上启动网易云音乐"
sh am start --display "$VD" -n "$(sh cmd package resolve-activity --brief com.netease.cloudmusic | tail -1)" >/dev/null 2>&1
sleep 3
AFT=$(sh dumpsys activity activities | grep -A6 "^Display #$VD" | grep -oE "topResumedActivity=.*" | head -1)
echo "  操作后虚拟屏顶部: $AFT"
echo "  操作后焦点 = $(foc)"
echo "  主屏顶部仍然是: $(sh dumpsys activity activities | grep -A6 '^Display #0' | grep -oE 'topResumedActivity=.*' | head -1)"

echo; echo "════ 实验3:input -d 在非焦点屏上生效吗 ════"
sh input -d 0 tap 540 1200 >/dev/null; sleep 1
echo "  焦点拉回主屏 = $(foc)"
echo "  --> 往虚拟屏发返回键 (input -d $VD keyevent BACK)"
sh input -d "$VD" keyevent 4 >/dev/null; sleep 2
echo "  虚拟屏顶部现在: $(sh dumpsys activity activities | grep -A6 "^Display #$VD" | grep -oE 'topResumedActivity=.*' | head -1)"
echo "  焦点 = $(foc)"

echo; echo "════ 实验4:IME 策略 ════"
sh "cmd window get-ime-display-policy $VD" 2>&1 | head -2
sh "cmd window help" 2>&1 | grep -i ime | head -5

echo; echo "清理"; kill $SPID 2>/dev/null; sh rm -f //sdcard/d.xml
