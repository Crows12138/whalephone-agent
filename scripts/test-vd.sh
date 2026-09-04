#!/bin/bash
export ADB="/c/Users/12916/platform-tools/adb.exe"
SCRCPY="./tools/scrcpy/scrcpy.exe"
PKG="${1:-com.android.settings}"
sh() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

echo "════ 测试 App: $PKG ════"
echo; echo "[基线] 测试前的显示器:"
sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | sort -u
echo "[基线] 主屏当前前台 App:"
sh dumpsys activity activities | grep -oE "topResumedActivity.*" | head -2

echo; echo "[启动] scrcpy 虚拟显示器 + 启动 $PKG ..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app="$PKG" >/tmp/scrcpy.log 2>&1 &
SPID=$!
sleep 8

echo; echo "[结果] 现在的显示器列表:"
sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | sort -u
VD=$(sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
echo "  -> 虚拟显示器 ID = $VD"

echo; echo "[结果] 各显示器上的 Activity:"
sh dumpsys activity activities | grep -E "^  Display #|topResumedActivity|ResumedActivity" | head -20

echo; echo "[结果] $PKG 落在哪:"
sh dumpsys activity activities | grep -B2 -A2 "$PKG" | grep -E "Display #|displayId|$PKG" | head -10

if [ -n "$VD" ] && [ "$VD" != "0" ]; then
  echo; echo "[输入] 往虚拟屏 $VD 注入 tap ..."
  sh input -d "$VD" tap 540 1200
  echo "  退出码: $?"
  sleep 2
  sh dumpsys activity activities | grep -oE "topResumedActivity.*" | head -2
fi

echo; echo "[scrcpy 日志]"
tail -15 /tmp/scrcpy.log

echo; echo "[清理] 关闭 scrcpy"
kill $SPID 2>/dev/null
sleep 2
echo "测试结束。测试后显示器:"
sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | sort -u
