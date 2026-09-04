#!/bin/bash
cd "$(dirname "$0")/.."
source scripts/lib.sh

foc() { sh dumpsys input 2>/dev/null | grep -oE "FocusedDisplayId: [0-9]+" | head -1; }
focwin() { sh dumpsys window displays 2>/dev/null | grep -E "Display: mDisplayId|mCurrentFocus" | sed 's/^ *//' | paste - - 2>/dev/null | head -4; }

echo "启动虚拟屏 + 计算器..."
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/s8.log 2>&1 &
SPID=$!
sleep 8
VD=$(vdid); echo "VD = $VD"

echo; echo "════ 焦点乒乓测试 ════"
echo "[T0] 虚拟屏刚起来:      $(foc)"

echo; echo "[T1] 模拟用户点主屏 (input -d 0 tap 540 1200)"
sh input -d 0 tap 540 1200 >/dev/null; sleep 2
echo "     焦点现在:          $(foc)"

echo; echo "[T2] 模拟 agent 点虚拟屏 (input -d $VD tap 540 1200)"
sh input -d "$VD" tap 540 1200 >/dev/null; sleep 2
echo "     焦点现在:          $(foc)"

echo; echo "[T3] 再模拟用户点主屏"
sh input -d 0 tap 540 1200 >/dev/null; sleep 2
echo "     焦点现在:          $(foc)"

echo; echo "════ 各屏焦点窗口明细 ════"
sh dumpsys window displays 2>/dev/null | grep -E "Display: mDisplayId=|mCurrentFocus=|mImeInputTarget=" | sed 's/^ *//'

echo; echo "════ 有没有运行时开关能打开每屏焦点 ════"
sh settings list global 2>/dev/null | grep -iE "focus|display" | head -10
sh "getprop | grep -iE 'per_display|perdisplay|multi_focus'" | head -5
echo "(空 = 没有运行时开关)"

echo; echo "════ 输入验证(修正按钮名):1 加号 2 计算 ════"
sh uiautomator dump --display "$VD" //sdcard/c.xml >/dev/null
"$ADB" shell cat //sdcard/c.xml > ui_c.xml 2>/dev/null
tapdesc() {
  B=$(grep -oE "<node[^>]*content-desc=\"$1\"[^>]*/>" ui_c.xml | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+')
  [ -z "$B" ] && { echo "     找不到: $1"; return; }
  set -- $B; X=$(( ($1+$3)/2 )); Y=$(( ($2+$4)/2 ))
  echo "     点 '$1' -> ($X,$Y)"; sh input -d "$VD" tap $X $Y >/dev/null; sleep 1
}
for L in "1" "加号" "2" "计算"; do tapdesc "$L"; done
sleep 1
sh uiautomator dump --display "$VD" //sdcard/c2.xml >/dev/null
"$ADB" shell cat //sdcard/c2.xml > ui_c2.xml 2>/dev/null
echo; echo "     计算器现在显示什么:"
grep -oE '(resource-id="[^"]*(result|formula|preview)[^"]*"[^>]*text="[^"]*"|text="[^"]*"[^>]*resource-id="[^"]*(result|formula|preview)[^"]*")' ui_c2.xml | head -4
echo "     所有非按钮文本:"
grep -oE 'text="[^"]{1,12}"' ui_c2.xml | sort -u | grep -vE 'text="(0|1|2|3|4|5|6|7|8|9|\+|-|×|÷|=|\.|%|\(\)|)"' | head -8

echo; echo "清理"; kill $SPID 2>/dev/null; sh rm -f //sdcard/c.xml //sdcard/c2.xml
