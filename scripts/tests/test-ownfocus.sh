#!/usr/bin/env bash
# 哪些操作会抢走用户的焦点和键盘,OWN_FOCUS 有没有用,以及怎么还回去。
#
# 之前的测试都在锁屏下做,keyguard 不争焦点,那个条件不算数。这个脚本要求
# 主屏上有一个真实的、聚焦的、带输入法的输入框(设置页搜索框),逐个动作量:
#
#   FocusedDisplayId  全局焦点指针在哪块屏
#   mInputShown       用户的输入法还开着吗
#
# 两组对照:带 OWN_FOCUS(0x5e08)和不带(0x1e08),其余标志位相同。
#
# 用法: bash scripts/tests/test-ownfocus.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
DEX=//data/local/tmp/flagprobe.dex

focus() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$"; }
ime()   { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
at()    { printf "  %-22s 焦点屏=%-3s 输入法=%s\n" "$1" "$(focus)" "$(ime)"; }

# 用无障碍读主屏输入框。不能用 uiautomator —— 它只读全局焦点所在的屏,
# 而焦点会不会跑正是要量的东西。
snap0() {
  sh logcat -c
  sh am broadcast -a $A.SNAP --ei display 0 >/dev/null 2>&1
  python -c "import time;time.sleep(1.8)"
  sh logcat -d -s WPEyes:*
}
fieldidx() { snap0 | grep -oE '\[[0-9]+\] AutoCompleteTextView' | head -1 | grep -oE '[0-9]+' | head -1; }
field()    { snap0 | grep -oE '\[[0-9]+\] AutoCompleteTextView "[^"]*"' | head -1 | sed -E 's/.*"(.*)"/\1/'; }

# 把焦点还给用户那块屏。KEYCODE_UNKNOWN 是个空按键,不会在界面上产生任何效果,
# 但带着显示器维度进了 InputDispatcher,足以把全局焦点指针推回去,输入法也跟着回来。
handback() { sh input -d 0 keyevent 0 >/dev/null 2>&1; python -c "import time;time.sleep(1.2)"; }

killholders() {
  "$ADB" shell "for p in \$(ps -A -o PID,NAME | grep app_process | awk '{print \$1}'); do kill -9 \$p; done" >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
}

prepare() {
  sh am start -a android.settings.SETTINGS >/dev/null 2>&1
  python -c "import time;time.sleep(2.5)"
  local IDX=$(fieldidx)
  [ -z "$IDX" ] && { echo "主屏上找不到设置页的搜索框"; return 1; }
  sh am broadcast -a $A.CLICK --ei display 0 --ei index "$IDX" >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
  IDX=$(fieldidx)
  sh am broadcast -a $A.TEXT --ei display 0 --ei index "$IDX" --es text "'AAA'" >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
}

run() {
  local NAME="$1" FLAGS="$2"
  echo
  echo "===== $NAME  flags=$FLAGS ====="
  killholders
  prepare || return 1
  at "起点(用户在打字)"

  "$ADB" shell "CLASSPATH=$DEX app_process /data/local/tmp ai.whalephone.FlagProbe 150 $FLAGS" > "$WROOT/.of.log" 2>&1 &
  python -c "import time;time.sleep(7)"
  local ID=$(grep -oE "HOLD displayId=[0-9]+" "$WROOT/.of.log" | grep -oE "[0-9]+$")
  [ -z "$ID" ] && { echo "  造屏失败"; tail -3 "$WROOT/.of.log"; return 1; }
  at "造屏后"
  handback; at "  还焦点后"

  sh am start --display "$ID" --activity-multiple-task -n com.sec.android.app.popupcalculator/.Calculator >/dev/null 2>&1
  python -c "import time;time.sleep(4)"
  at "副屏启 App 后"
  handback; at "  还焦点后"

  for i in 1 2 3; do
    sh am broadcast -a $A.CLICK --ei display "$ID" --es text "$i" >/dev/null 2>&1
    python -c "import time;time.sleep(0.8)"
  done
  at "无障碍连点三下后"

  # 模拟用户继续打字。不带 -d,所以它落在全局焦点所在的屏 ——
  # 字要是进了副屏,用户框里就不会出现 BBB。
  sh input text BBB >/dev/null 2>&1
  python -c "import time;time.sleep(2)"
  local F=$(field)
  if [ "$F" = "AAABBB" ]; then echo "  用户继续打字         落在自己的框里 ✓ 「$F」"
  else echo "  用户继续打字         没落进来 ✗ 实际「$F」"; fi

  killholders
}

run "带独立焦点"   0x5e08
run "不带独立焦点" 0x1e08
echo
echo "两组唯一差别是 OWN_FOCUS(1<<14)。还焦点用的是 input -d 0 keyevent 0(空按键)。"
