#!/usr/bin/env bash
# 核心验收:用户正在用手机的同时,agent 把任务做完。
#
# 「不打扰」最难证明,因为**什么都没发生是看不见的**。这里把它变成可测量的:
# 让「用户」在整个任务期间持续打字,一秒一个字符。键盘被抢、焦点被夺、界面跳走,
# 任何一样发生,字就会缺。跑完比对预期字串,一个字不差才算过。
#
# 打字用 `input -d 0 text` —— 带显示器归属,和真人手指按在物理屏上产生的事件同类。
# 不带 -d 的 input text 模拟的不是用户,是一个没有来源的幽灵按键(见 FINDINGS.md)。
#
# 用法: bash scripts/tests/test-concurrent.sh ["任务"]

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
GOAL="${1:-打开淘宝,搜索「机械键盘」,告诉我第一个商品多少钱}"
TYPE="abcdefghijklmnopqrstuvwxyz"

snap0() {
  sh logcat -c
  bc -a $A.SNAP --ei display 0 >/dev/null 2>&1
  python -c "import time;time.sleep(1.6)"
  sh logcat -d -s WPEyes:*
}
idx()   { snap0 | grep -oE '\[[0-9]+\] AutoCompleteTextView' | head -1 | grep -oE '[0-9]+' | head -1; }
field() { snap0 | grep -oE '\[[0-9]+\] AutoCompleteTextView "[^"]*"' | head -1 | sed -E 's/.*"(.*)"/\1/'; }
top()   { sh dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-z0-9_.]+/[A-Za-z0-9_.]+' | head -1; }

echo "== 准备:主屏放一个真实的输入界面 =="
# 直接进设置的搜索页 —— 设置首页的搜索栏在无障碍树里读不出来
sh am start -n com.android.settings.intelligence/.search.SearchActivity >/dev/null 2>&1
python -c "import time;time.sleep(3)"
I=$(idx); [ -z "$I" ] && { echo "主屏找不到输入框"; exit 1; }
bc -a $A.CLICK --ei display 0 --ei index "$I" >/dev/null 2>&1
python -c "import time;time.sleep(1.5)"
I=$(idx)
bc -a $A.TEXT --ei display 0 --ei index "$I" --es text "''" >/dev/null 2>&1
python -c "import time;time.sleep(1.5)"
echo "   输入框已聚焦,内容=「$(field)」  用户前台=$(top)"

echo
echo "== 开跑:agent 接任务,同时用户一直打字 =="
echo "   任务: $GOAL"
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

# 用户在整个任务期间持续打字
( for c in $(echo "$TYPE" | grep -o .); do
    "$ADB" shell "input -d 0 text $c" >/dev/null 2>&1
    python -c "import time;time.sleep(2)"
  done ) &
TYPER=$!

# 等任务结束(或超时)
for i in $(seq 1 40); do
  python -c "import time;time.sleep(3)"
  if sh logcat -d -s WPSvc:* | grep -q "结束 done="; then break; fi
done
wait $TYPER 2>/dev/null

echo
echo "== 结果 =="
sh logcat -d -s WPAgent:* | grep -oE "第 [0-9]+ 步 [a-z_]+.*" | sed 's/^/   /'
echo
sh logcat -d -s WPSvc:* | grep "结束 done=" | sed -E 's/.*WPSvc  : /   agent: /'

GOT=$(field)
echo
echo "== 用户这边有没有被打扰 =="
echo "   期望输入框 = 「$TYPE」"
echo "   实际输入框 = 「$GOT」"
# 大小写不比:`input text` 不携带 shift,大写字母会以小写落地,
# 而且输入框自己还会把首字母自动大写。这是注入方式的限制,不是丢字。
L=$(echo "$GOT"  | tr '[:upper:]' '[:lower:]')
R=$(echo "$TYPE" | tr '[:upper:]' '[:lower:]')
if [ "$L" = "$R" ]; then echo "   键盘和焦点  ${#GOT}/${#TYPE} 个字符全部落在用户自己的输入框里 ✓"
else echo "   键盘和焦点  丢字或错序 ✗"; fi
echo "   用户前台    $(top)"
echo "   系统显示器  $(sh dumpsys display | grep -oE 'Logical Displays: size=[0-9]+')"
