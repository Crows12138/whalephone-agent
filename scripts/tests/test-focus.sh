#!/bin/bash
cd "$(dirname "$0")/.."
source scripts/lib.sh

echo "═══ 基线:只有主屏时的焦点 ═══"
sh dumpsys window 2>/dev/null | grep -iE "mCurrentFocus|mFocusedApp|mPerDisplayFocus|imeInputTarget" | head -8

echo; echo "启动虚拟屏 + 计算器..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/s7.log 2>&1 &
SPID=$!
sleep 8
VD=$(vdid); echo "VD = $VD"

echo; echo "═══ 关键:每屏焦点开关 ═══"
sh dumpsys window 2>/dev/null | grep -iE "mPerDisplayFocusEnabled|perDisplayFocus" | head -4
echo "(空 = 没这个字段,需从别处判断)"

echo; echo "═══ 各显示器的焦点窗口 ═══"
sh dumpsys window displays 2>/dev/null | grep -E "Display: mDisplayId|mCurrentFocus|mFocusedApp" | head -20

echo; echo "═══ dumpsys input 的焦点视图 ═══"
sh dumpsys input 2>/dev/null | grep -iE "FocusedWindow|FocusedDisplay|mFocusedDisplayId" | head -10

echo; echo "═══ 全局:现在谁有焦点 ═══"
sh dumpsys window 2>/dev/null | grep -iE "mCurrentFocus|mFocusedApp|mFocusedDisplayId" | head -8

echo; echo "═══ 计算器的 content-desc 都有啥 (为了修输入测试) ═══"
sh uiautomator dump --display "$VD" //sdcard/uc.xml >/dev/null
"$ADB" shell cat //sdcard/uc.xml > ui_calc.xml 2>/dev/null
grep -oE 'content-desc="[^"]*"' ui_calc.xml | sort -u | head -25

echo; echo "═══ 计算器的结果显示区 ═══"
grep -oE '<node[^>]*resource-id="[^"]*(result|formula|display|input)[^"]*"[^>]*text="[^"]*"' ui_calc.xml | head -5

echo; echo "清理"; kill $SPID 2>/dev/null; sh rm -f //sdcard/uc.xml
