#!/bin/bash
export MSYS_NO_PATHCONV=1          # 关键:禁止 Git Bash 改写路径
export MSYS2_ARG_CONV_EXCL="*"
export ADB="/c/Users/12916/platform-tools/adb.exe"
SCRCPY="./tools/scrcpy/scrcpy.exe"
sh() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

echo "启动虚拟屏 + 计算器..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/s5.log 2>&1 &
SPID=$!
sleep 7
VD=$(sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
echo "VD = $VD"

echo; echo "═══ 截图: exec-out screencap -d 逻辑ID $VD ═══"
"$ADB" exec-out screencap -p -d "$VD" > cap_vd.png 2>/tmp/e1.log
echo "  cap_vd.png = $(stat -c %s cap_vd.png) bytes"; head -2 /tmp/e1.log

echo; echo "═══ 截图: exec-out screencap 主屏(对照) ═══"
"$ADB" exec-out screencap -p > cap_main.png 2>/dev/null
echo "  cap_main.png = $(stat -c %s cap_main.png) bytes"

echo; echo "═══ UI树: uiautomator dump 默认屏 ═══"
sh uiautomator dump //sdcard/u1.xml
"$ADB" shell cat //sdcard/u1.xml > ui_main.xml 2>/dev/null
echo "  ui_main.xml = $(stat -c %s ui_main.xml) bytes"
grep -oE 'package="[^"]+"' ui_main.xml 2>/dev/null | sort -u | head -4

echo; echo "═══ UI树: uiautomator dump --display $VD ═══"
sh uiautomator dump --display "$VD" //sdcard/u2.xml
"$ADB" shell cat //sdcard/u2.xml > ui_vd.xml 2>/dev/null
echo "  ui_vd.xml = $(stat -c %s ui_vd.xml) bytes"
grep -oE 'package="[^"]+"' ui_vd.xml 2>/dev/null | sort -u | head -4

echo; echo "═══ 输入验证: 在计算器上点 1 + 2 = ═══"
grep -oE '<node[^>]*text="[1-9+=]"[^>]*bounds="[^"]*"' ui_vd.xml 2>/dev/null | head -8

echo; echo "清理"; kill $SPID 2>/dev/null; sh rm -f //sdcard/u1.xml //sdcard/u2.xml
