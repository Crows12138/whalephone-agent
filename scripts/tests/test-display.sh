#!/bin/bash
# 生死线测试:能否在副显示器上启动任意 App 并注入输入
# 会临时创建一个 overlay 显示器,结束时自动清理
ADB="/c/Users/12916/platform-tools/adb.exe"
PKG="${1:-com.android.settings}"

cleanup() {
  echo; echo "[清理] 移除测试显示器..."
  "$ADB" shell settings put global overlay_display_devices null 2>/dev/null
  echo "[清理] 完成"
}
trap cleanup EXIT INT TERM

sh() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

echo "════ 测试目标 App: $PKG ════"

echo; echo "[1/5] 记录当前显示器"
BEFORE=$(sh dumpsys SurfaceFlinger --display-id)
echo "$BEFORE"
sh dumpsys display | grep -oE "mDisplayId=[0-9]+" | sort -u

echo; echo "[2/5] 创建 overlay 副显示器 (1080x2400/420)"
echo "  注意:手机屏幕上会出现一个悬浮小窗,这是开发者选项的行为,测试完自动消失"
sh settings put global overlay_display_devices "1080x2400/420"
sleep 3
sh dumpsys display | grep -oE "mDisplayId=[0-9]+" | sort -u
NEWID=$(sh dumpsys display | grep -oE "mDisplayId=[0-9]+" | grep -oE "[0-9]+" | sort -n | tail -1)
echo "  新显示器 ID: $NEWID"
[ "$NEWID" = "0" ] && { echo "  ✗ 失败:没有创建出副显示器"; exit 1; }

echo; echo "[3/5] 在副显示器上启动 $PKG"
COMP=$(sh cmd package resolve-activity --brief "$PKG" | tail -1)
echo "  组件: $COMP"
sh am start --display "$NEWID" -n "$COMP"

sleep 3
echo; echo "[4/5] 检查该 App 落在哪个显示器上"
sh dumpsys activity activities | grep -E "displayId|ResumedActivity|topResumedActivity" | head -12

echo; echo "[5/5] 测试往副显示器注入输入"
sh input -d "$NEWID" tap 540 1200
echo "  input -d $NEWID tap 返回码: $?"
sleep 1
sh dumpsys activity activities | grep -E "topResumedActivity" | head -3

echo; echo "════ 关键判断 ════"
echo "看第4步:目标 App 的 displayId 是不是 $NEWID"
echo "  是  -> 机制通,可以继续"
echo "  0   -> App 被强制拉回主屏(常见于 resizeableActivity=false)"
