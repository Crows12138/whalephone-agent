#!/bin/bash
cd "$(dirname "$0")/.."
source scripts/lib.sh

foc()  { sh dumpsys input 2>/dev/null | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+"; }
ime()  { sh dumpsys input_method 2>/dev/null | grep -E "mInputShown|mShowRequested|mDisplayIdToShowIme|mCurTokenDisplayId" | sed 's/^ *//' | tr '\n' ' '; }
tree() { sh uiautomator dump //sdcard/t.xml >/dev/null 2>&1; "$ADB" shell cat //sdcard/t.xml 2>/dev/null > /tmp/t.xml; }
# 找第一个 EditText 的中心坐标
findedit() {
  grep -oE '<node[^>]*class="[^"]*(EditText|AutoCompleteTextView)"[^>]*>' /tmp/t.xml | head -1 \
   | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' '
}
# 读所有 EditText 的 text
edittext() { grep -oE '<node[^>]*search_src_text[^>]*>|<node[^>]*(EditText|AutoCompleteTextView)[^>]*>' /tmp/t.xml | head -1 | grep -oE 'text="[^"]*"' | head -1; }

echo "════ 0. 主屏打开设置并进入搜索框 ════"
sh am start -a android.settings.SETTINGS >/dev/null 2>&1; sleep 3
tree; C=$(findedit)
if [ -z "$C" ]; then
  echo "  设置首页没有 EditText,找搜索入口..."
  SB=$(grep -oE '<node[^>]*(content-desc="搜索[^"]*"|resource-id="[^"]*search[^"]*")[^>]*>' /tmp/t.xml | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  if [ -n "$SB" ]; then set -- $SB; sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null; sleep 3; tree; C=$(findedit); fi
fi
[ -z "$C" ] && { echo "  ✗ 找不到输入框,dump 里的可点元素:"; grep -oE 'content-desc="[^"]{1,12}"' /tmp/t.xml | sort -u | head -10; exit 1; }
set -- $C; EX=$(( ($1+$3)/2 )); EY=$(( ($2+$4)/2 ))
echo "  输入框中心 = ($EX,$EY)"
sh input -d 0 tap $EX $EY >/dev/null; sleep 2

echo; echo "════ 1. 基线:只有用户在打字 ════"
echo "  焦点屏 = $(foc)"
echo "  IME    = $(ime)"
sh input -d 0 text "AAAA" >/dev/null; sleep 2
tree; echo "  输入框内容 = $(edittext)"

echo; echo "════ 2. 起虚拟屏(agent 上线) ════"
"$SCRCPY" --new-display=1080x2340/450 --no-audio --start-app=com.sec.android.app.popupcalculator >/tmp/sA.log 2>&1 &
SPID=$!; sleep 8
VD=$(vdid); echo "  VD = $VD   焦点屏 = $(foc)"
echo "  IME = $(ime)"

echo; echo "════ 3. 用户点回输入框继续打字 ════"
sh input -d 0 tap $EX $EY >/dev/null; sleep 2
echo "  焦点屏 = $(foc)"
echo "  IME    = $(ime)"
sh input -d 0 text "BB" >/dev/null; sleep 1
tree; echo "  内容 = $(edittext)   (期望 AAAABB)"

echo; echo "════ 4. ★ agent 在虚拟屏上动手 ★ ════"
sh input -d "$VD" tap 540 1200 >/dev/null; sleep 2
echo "  焦点屏 = $(foc)      <- 预期被抢到 $VD"
echo "  IME    = $(ime)"

echo; echo "════ 5. 用户继续打字(不重新点输入框) ════"
sh input -d 0 text "CC" >/dev/null; sleep 2
sh input -d 0 tap $EX $EY >/dev/null; sleep 2   # 点回来才能读主屏树
tree; echo "  内容 = $(edittext)"
echo "  --> AAAABBCC = 完全没影响 | AAAABB = CC 丢了 | 其他 = 焦点乱了"

echo; echo "════ 6. agent 连续操作 5 次,看键盘是否被反复打断 ════"
sh input -d 0 tap $EX $EY >/dev/null; sleep 2
for i in 1 2 3 4 5; do
  sh input -d "$VD" tap 540 1200 >/dev/null
  sleep 1
  printf "  第%d次后: 焦点=%s  IME=%s\n" $i "$(foc)" "$(ime)"
done

echo; echo "════ 7. 清理 ════"
kill $SPID 2>/dev/null; sleep 2
sh input -d 0 keyevent 3 >/dev/null   # HOME,退出设置
sh rm -f //sdcard/t.xml
echo "完成"
