#!/usr/bin/env bash
# 机主的输入法,在 agent 干活的全程有没有被收起过。
#
# 为什么不是数「字有没有丢」:
#   之前的并发测试用 `input -d 0 text` 一秒打一个字,跑完比对字符串,26/26 通过,
#   于是我在文档里写了「不打扰」。机主用真手指一试就断了。
#   原因是那个指标错了 —— **键盘被收起来之后,重新点一下输入框还能接着打**,
#   字并不会丢。丢的是机主的连续性,而那个测试看不见。
#
# 直接测输入法自己的状态:`dumpsys input_method` 的 mInputShown。
# 它是真实 IME 的真实状态,跟字是怎么进去的无关。任何一次 true -> false
# 都是一次实打实的打扰。同时记 FocusedDisplayId,用来定位是谁把焦点拽走的。
#
# 用法: bash scripts/tests/test-ime.sh ["任务"]

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
GOAL="${1:-打开淘宝,搜索「保温杯」,告诉我前两个商品的价格和店铺}"

ime()   { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
focus() { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }

# 分两段,顺序不能反。
#
# 为什么:造副屏这一步现在是**事前避让** —— 机主在打字就干脆不造(造屏必然抢一次
# 焦点、收一次键盘)。如果一上来就把键盘顶着再发任务,agent 会一直等、等满 3 分钟
# 放弃,任务根本不会启动,读数是空的却看不出来。真机上就这么白跑了一轮。
#
# 而这个脚本要测的是另一条路径:**逐步让路** —— 副屏已经在、任务已经在跑,
# 机主中途开始打字,agent 每个动作前检查一次并停手。这才是「机主用着手机的同时
# agent 在干活」那个核心场景。所以:
#
#   第一段:机主没打字,先跑一轮把副屏造出来(副屏按设计跨任务保留)
#   第二段:再发一个任务,等它真的动起来,这时才叫键盘并顶住,只在这段窗口计掉落
echo "== 第一段:机主没打字,先把副屏造出来 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
python -c "import time;time.sleep(1)"
[ "$(ime)" = "true" ] && { echo "键盘还开着,收不掉,测不了"; exit 1; }
sh logcat -c
bc -a $A.RUN --es goal "'打开计算器'" >/dev/null 2>&1
for _ in $(seq 1 60); do python -c "import time;time.sleep(2)"; sh logcat -d -s WPSvc:* | grep -q "收工:" && break; done
D=$(sh logcat -d -s WPSvc:* | grep -oE "副屏 [0-9]+ 就绪" | grep -oE "[0-9]+" | tail -1)
[ -z "$D" ] && { echo "副屏没造出来,后面测不了"; sh logcat -d -s WPSvc:* | tail -3; exit 1; }
echo "   副屏 $D 已就绪,会留到下一个任务复用"

echo
echo "== 第二段:机主先开始打字,然后 agent 收到任务 =="
# 顺序很重要,而且改过一次。
#
# 让路现在只挡 launch(唯一会收键盘的动作,见 FINDINGS 的逐个操作归因表),
# 别的动作不再等 —— 否则机主一打字 agent 就整个停摆,而这个项目的命题恰恰是
# 「机主用着手机的同时 agent 也在用」。
#
# 于是测试的时序也得跟着变:原来是「等 agent 动起来再叫键盘」,那时第一个 launch
# 已经过去了,让路一次都不触发,读数全 0 但什么也没测到。现在副屏已经在(第一段
# 造好的),所以先把键盘叫起来再下任务,第一个 launch 正好撞上让路。
raise_ime || { echo "输入法没起来(mInputShown=$(ime)),测不了"; exit 1; }
echo "   机主的输入法已弹出 mInputShown=true  焦点屏=$(focus)"
sh logcat -c
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null 2>&1

echo
echo "== 开始采样(任务已经在跑,键盘已经弹着)=="

# 机主不会一直开着键盘不动。让键盘全程弹着,测的就变成「等到超时之后会怎样」,
# 那不是真实场景 —— 真人打字是几秒到几十秒然后停。
# 所以只在前 TYPING_S 秒保持弹出,之后自己收掉;判定只看这段窗口内的掉落。
TYPING_S="${TYPING_S:-45}"
T0=$(date +%s)
DROPS=0; AFTER=0; STEAL=0; PREV=true; CLOSED=0
for i in $(seq 1 600); do
  NOW=$(( $(date +%s) - T0 ))
  if [ "$CLOSED" = "0" ] && [ "$NOW" -ge "$TYPING_S" ]; then
    echo "   [${NOW}s] 机主打完字,自己收起键盘(之后的掉落不算 agent 头上)"
    # 用返回键收键盘,不用 ESC:讯飞对 keyevent 111 没反应(真机实测),
    # 结果就是键盘一直顶着,agent 一直在等,任务从头到尾没启动而读数看着正常。
    # 软键盘弹着时返回键先被 IME 吃掉,不会真的往回导航。
    sh input -d 0 keyevent 4 >/dev/null 2>&1
    CLOSED=1; python -c "import time;time.sleep(1)"; PREV=$(ime); continue
  fi
  M=$(ime); F=$(focus)
  if [ "$PREV" = "true" ] && [ "$M" = "false" ]; then
    if [ "$CLOSED" = "0" ]; then
      DROPS=$((DROPS+1)); echo "   [${NOW}s] 机主还在打字,键盘却被收起(第 $DROPS 次)  焦点屏=$F"
    else AFTER=$((AFTER+1)); fi
  fi
  [ -n "$F" ] && [ "$F" != "0" ] && STEAL=$((STEAL+1))
  PREV="$M"
  sh logcat -d -s WPSvc:* | grep -q "结束 done=" && break
  python -c "import time;time.sleep(0.4)"
done

echo
echo "== agent 那边做了什么 =="
sh logcat -d -s WPAgent:* | grep -oE "第 [0-9]+ 步 [a-z_]+" | tr '\n' ' '; echo
sh logcat -d -s WPSvc:* | grep "结束 done=" | sed -E 's/.*WPSvc  : /   /'

echo
# 让路机制到底有没有触发。DROPS=0 但一次都没让过,说明这次机主根本没打字,
# 测试没测到东西 —— 那是「没测出来」,不是「解决了」,两者必须分得开。
YIELDS=$(sh logcat -d -s WPConflict:* 2>/dev/null | grep -c "机主在打字")
ANRS=$(sh logcat -d -b events 2>/dev/null | grep -c "am_anr")
echo "== 结论 =="
echo "   期间 ANR 次数        $ANRS   (要求 0)"
echo "   打字期间键盘被收起  $DROPS   (要求 0 —— 这一项才是「有没有打扰机主」)"
echo "   打字结束后被收起    $AFTER  (不算 agent 头上,机主已经不在打字了)"
echo "   agent 主动让路次数  $YIELDS   (为 0 说明这次机主没打字,这轮测试无效)"
echo "   焦点不在主屏的采样  $STEAL 次 (要求 0;短暂离开随即还回来也会被采到)"
echo "   最终 mInputShown=$(ime)  焦点屏=$(focus)"
if [ "$YIELDS" -eq 0 ]; then echo "   这轮不算数:agent 一次都没让过,说明机主全程没打字"
elif [ "$DROPS" -eq 0 ]; then echo "   机主的键盘全程没被动过 ✓"
else echo "   机主的键盘被打断了 ✗"; fi
