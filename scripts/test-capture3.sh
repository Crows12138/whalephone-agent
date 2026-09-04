#!/bin/bash
cd "$(dirname "$0")/.."
source scripts/lib.sh

echo "启动虚拟屏 + 计算器..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/s6.log 2>&1 &
SPID=$!
sleep 8
VD=$(vdid); echo "VD = $VD"
[ "$VD" = "0" ] && { echo "虚拟屏没建起来:"; tail -6 /tmp/s6.log; exit 1; }

echo; echo "═══ A. exec-out screencap -d $VD (逻辑ID) ═══"
"$ADB" exec-out screencap -p -d "$VD" > cap_vd.png 2>/tmp/e1.log
echo "  $(stat -c %s cap_vd.png) bytes"; head -3 /tmp/e1.log

echo; echo "═══ B. uiautomator dump --display $VD ═══"
sh uiautomator dump --display "$VD" //sdcard/u2.xml
"$ADB" shell cat //sdcard/u2.xml > ui_vd.xml 2>/dev/null
echo "  $(stat -c %s ui_vd.xml) bytes"
echo "  抓到的包:"; grep -oE 'package="[^"]+"' ui_vd.xml | sort -u | head -4

echo; echo "═══ C. 对照:主屏 UI 树 ═══"
sh uiautomator dump //sdcard/u1.xml >/dev/null
"$ADB" shell cat //sdcard/u1.xml > ui_main.xml 2>/dev/null
echo "  $(stat -c %s ui_main.xml) bytes"
echo "  抓到的包:"; grep -oE 'package="[^"]+"' ui_main.xml | sort -u | head -4

echo; echo "═══ D. 输入验证:计算器 1+2= ═══"
btn() { grep -oE "<node[^>]*content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" ui_vd.xml | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' '; }
for LABEL in 1 加 2 等于; do
  C=$(btn "$LABEL")
  if [ -n "$C" ]; then
    set -- $C; X=$(( ($1+$3)/2 )); Y=$(( ($2+$4)/2 ))
    echo "  点 '$LABEL' -> ($X,$Y)"; sh input -d "$VD" tap $X $Y; sleep 1
  else echo "  找不到按钮 '$LABEL'"; fi
done
sleep 1
sh uiautomator dump --display "$VD" //sdcard/u3.xml >/dev/null
"$ADB" shell cat //sdcard/u3.xml > ui_after.xml 2>/dev/null
echo "  点击后计算器显示:"; grep -oE 'text="[0-9+=.-]+"' ui_after.xml | sort -u | head -8

echo; echo "清理"; kill $SPID 2>/dev/null; sh rm -f //sdcard/u1.xml //sdcard/u2.xml //sdcard/u3.xml
