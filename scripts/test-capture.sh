#!/bin/bash
export ADB="/c/Users/12916/platform-tools/adb.exe"
SCRCPY="./tools/scrcpy/scrcpy.exe"
sh() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

echo "═══ screencap 完整用法 ═══"
sh screencap -h

echo; echo "═══ 物理显示器 ID ═══"
sh dumpsys SurfaceFlinger --display-id

echo; echo "═══ uiautomator 用法 ═══"
sh uiautomator dump --help 2>&1 | head -12

echo; echo "启动虚拟屏..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/s3.log 2>&1 &
SPID=$!
sleep 7
VD=$(sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
echo "VD = $VD"

echo; echo "═══ 方案1: screencap -d 逻辑ID ═══"
sh screencap -d "$VD" -p /sdcard/c1.png; echo "  size=$(sh 'stat -c %s /sdcard/c1.png 2>/dev/null||echo 0')"

echo; echo "═══ 方案2: screencap -d 物理ID ═══"
PHYS=$(sh dumpsys SurfaceFlinger --display-id | grep -oE "Display [0-9]+" | grep -oE "[0-9]+" | head -1)
echo "  物理ID尝试: $PHYS"
sh screencap -d "$PHYS" -p /sdcard/c2.png; echo "  size=$(sh 'stat -c %s /sdcard/c2.png 2>/dev/null||echo 0')"

echo; echo "═══ 方案3: uiautomator dump 默认 ═══"
sh uiautomator dump /sdcard/u1.xml; echo "  size=$(sh 'stat -c %s /sdcard/u1.xml 2>/dev/null||echo 0')"

echo; echo "═══ 方案4: uiautomator dump --display VD ═══"
sh uiautomator dump --display "$VD" /sdcard/u2.xml; echo "  size=$(sh 'stat -c %s /sdcard/u2.xml 2>/dev/null||echo 0')"

echo; echo "═══ 方案4 抓到的是哪个界面 ═══"
sh "grep -oE 'package=\"[^\"]+\"' /sdcard/u2.xml 2>/dev/null | sort -u | head -6"

echo; echo "═══ 方案3 抓到的是哪个界面(对照) ═══"
sh "grep -oE 'package=\"[^\"]+\"' /sdcard/u1.xml 2>/dev/null | sort -u | head -6"

echo; echo "═══ 方案5: scrcpy 自己录一帧 ═══"
"$SCRCPY" --display-id="$VD" --no-audio --no-window --record=/tmp/vd.mp4 --time-limit=2 >/tmp/s4.log 2>&1
ls -la /tmp/vd.mp4 2>/dev/null && echo "  scrcpy 录制成功" || echo "  失败"; tail -3 /tmp/s4.log

echo; echo "清理"; kill $SPID 2>/dev/null; sh rm -f /sdcard/c1.png /sdcard/c2.png /sdcard/u1.xml /sdcard/u2.xml
