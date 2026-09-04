#!/bin/bash
export ADB="/c/Users/12916/platform-tools/adb.exe"
SCRCPY="./tools/scrcpy/scrcpy.exe"
sh() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }

APPS="com.sec.android.app.popupcalculator com.tencent.mm com.taobao.taobao com.sankuai.meituan com.jingdong.app.mall com.netease.cloudmusic com.autonavi.minimap tv.danmaku.bili com.whatsapp com.zhihu.android"

echo "启动虚拟显示器..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --no-vd-destroy-content >/tmp/scrcpy2.log 2>&1 &
SPID=$!
sleep 7
VD=$(sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
echo "虚拟显示器 ID = $VD"
[ "$VD" = "0" ] && { echo "创建失败"; kill $SPID; exit 1; }

echo
printf "%-34s %-10s %s\n" "包名" "落在哪" "判定"
printf "%-34s %-10s %s\n" "----" "------" "----"

for P in $APPS; do
  COMP=$(sh cmd package resolve-activity --brief "$P" 2>/dev/null | tail -1)
  [ -z "$COMP" ] || [ "$COMP" = "No activity found" ] && { printf "%-34s %-10s %s\n" "$P" "-" "未安装/无入口"; continue; }
  sh am start --display "$VD" -n "$COMP" >/dev/null 2>&1
  sleep 3
  # 找该包所在的 Display #
  LOC=$(sh dumpsys activity activities | awk -v pkg="$P" '
    /^Display #/ { d=$2; sub("#","",d) }
    $0 ~ pkg && $0 ~ /ActivityRecord/ && found!=1 { print d; found=1 }
  ' | head -1)
  [ -z "$LOC" ] && LOC="?"
  if [ "$LOC" = "$VD" ]; then V="✅ 虚拟屏"
  elif [ "$LOC" = "0" ]; then V="❌ 被弹回主屏"
  else V="? ($LOC)"; fi
  printf "%-34s %-10s %s\n" "$P" "$LOC" "$V"
done

echo
echo "═══ 测试给虚拟屏截图 ═══"
sh screencap -d "$VD" -p /sdcard/vd_test.png 2>&1 | head -3
sleep 1
SZ=$(sh 'stat -c %s /sdcard/vd_test.png 2>/dev/null || echo 0')
echo "虚拟屏截图大小: $SZ bytes"
if [ "$SZ" -gt 10000 ] 2>/dev/null; then
  "$ADB" pull /sdcard/vd_test.png ./vd_test.png >/dev/null 2>&1 && echo "已拉取到 ./vd_test.png"
  sh rm /sdcard/vd_test.png
else
  echo "screencap -d 对虚拟屏无效"
fi

echo
echo "═══ 主屏在整个过程中的状态 ═══"
sh dumpsys activity activities | grep -A3 "^Display #0" | grep -oE "topResumedActivity=.*" | head -2

echo; echo "清理..."
kill $SPID 2>/dev/null; sleep 2
sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | sort -u
