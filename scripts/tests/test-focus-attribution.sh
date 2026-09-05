#!/usr/bin/env bash
# 到底是哪些操作会抢走机主的输入法。
#
# 「不打扰用户」这条要求落到可测量的东西上,就是这两个:
#   FocusedDisplayId   全局焦点在哪块屏
#   mInputShown        机主的软键盘还开着吗
#
# 一次只做一个操作,前后各量一次,中间把状态复原。这样每一行都能单独归因,
# 不会像整任务跑那样只知道「被打断了 3 次」却不知道是谁干的。
#
# 关键结论(实测):input -d 0 keyevent 0 能把焦点指针推回主屏,
# 但**不会**把已经收起的软键盘重新弹出来 —— 所以事后补救不成立,
# 只能一开始就不碰。
#
# 用法: bash scripts/tests/test-focus-attribution.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent

ime()   { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
focus() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }

# 把机主的输入法重新叫出来。每个用例开始前都要回到这个干净状态。
#
# 用三星浏览器的地址栏,不用本 app 自己的输入框 —— 焦点被抢之后本 app 会因为
# 「Application does not have a focused window」而 ANR,拿它当载体等于用一个
# 会被待测现象弄坏的东西去测那个现象。
arm() {
  raise_ime && return 0
  # 叫不起来时再推一次焦点回主屏,然后重试一遍 —— 有时是焦点还在副屏上,
  # 点主屏的输入框根本不生效。
  sh input -d 0 keyevent 0 >/dev/null 2>&1
  python -c "import time;time.sleep(1)"
  raise_ime
}

# 跑一个用例:名字 + 一条命令
case_() {
  local NAME="$1"; shift
  arm || { printf "  %-34s 准备失败,跳过\n" "$NAME"; return; }
  local F0=$(focus)
  eval "$@" >/dev/null 2>&1
  python -c "import time;time.sleep(1.8)"
  local F1=$(focus) M1=$(ime)
  printf "  %-34s 焦点 %s->%-4s 输入法=%-6s %s\n" \
    "$NAME" "$F0" "$F1" "$M1" "$([ "$M1" = "true" ] && echo '没打扰 ✓' || echo '键盘被收起 ✗')"
}

echo "== 造一块副屏,上面放个计算器 =="
bc -a $A.RUN --es goal "'打开计算器'" >/dev/null 2>&1
for i in $(seq 1 20); do python -c "import time;time.sleep(2)"; sh logcat -d -s WPSvc:* | grep -q "结束 done=" && break; done
D=$(sh dumpsys display | grep -oE "mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
echo "   副屏 = $D"
echo

echo "== 逐个操作归因(每个用例前都把机主的输入法重新叫出来)=="
case_ "无障碍 点击(普通按钮)"      "bc -a $A.CLICK --ei display $D --es text 7"
case_ "无障碍 写文本(带 FOCUS)"    "bc -a $A.TEXT --ei display $D --es text 1"
case_ "注入 tap"                    "sh input -d $D tap 540 1750"
case_ "注入 keyevent 返回"          "sh input -d $D keyevent 4"
case_ "注入 swipe"                  "sh input -d $D swipe 540 1400 540 900 300"
case_ "am start 往副屏启 App"       "sh am start --display $D --activity-multiple-task -n com.sec.android.app.popupcalculator/.Calculator"
echo
echo "== 还焦点这一招到底能还回什么 =="
arm && {
  sh input -d "$D" tap 540 1750 >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
  printf "  %-34s 焦点=%-4s 输入法=%s\n" "注入之后" "$(focus)" "$(ime)"
  sh input -d 0 keyevent 0 >/dev/null 2>&1
  python -c "import time;time.sleep(1.5)"
  printf "  %-34s 焦点=%-4s 输入法=%s\n" "keyevent 0 还焦点之后" "$(focus)" "$(ime)"
  echo "  焦点能推回去,软键盘推不回来 —— 所以只能一开始就不碰,补救不成立。"
}
