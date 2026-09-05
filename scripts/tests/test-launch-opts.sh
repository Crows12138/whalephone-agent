#!/usr/bin/env bash
# 往副屏启 App,有没有一种方式不收起机主的键盘。
#
# `am start --display N` 是全流程里唯一会收键盘的操作(见 FINDINGS 的逐个操作归因表)。
# 现在整套让路机制都是围着它转的:机主在打字就不启、启之前先等。如果能找到一种
# 不抢焦点的启动方式,让路就从「必需」降级成「保险」,agent 在机主打字时也能干活。
#
# 四组对照,变量只有 ActivityOptions:
#   0 只 setLaunchDisplayId        —— 对照组,等价于 am start --display
#   1 加 setAvoidMoveToFront
#   2 加 setTransientLaunch
#   3 两个都加
#
# 每组两个判据,缺一不可:
#   键盘还在吗   —— 这是要解决的问题
#   App 真起来了吗 —— 不抢焦点但也没启动的方式没有意义,而且它看起来会像「成功」
#
# 用法: bash scripts/tests/test-launch-opts.sh [包名]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
PKG="${1:-com.sec.android.app.popupcalculator}"
DEX=/data/local/tmp/launchopts.dex
naps() { python -c "import time;time.sleep($1)"; }
ime()  { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
foc()  { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }
onvd() { sh dumpsys activity activities | sed -n "/Display #$1 /,/^Display #/p" | grep -c "$PKG"; }

"$ADB" push "$WROOT/probe/outlaunch/classes.dex" $DEX >/dev/null 2>&1 || { echo "推 dex 失败"; exit 1; }

echo "== 先让 agent 造一块副屏出来 =="
bc -a $A.RUN --es goal "'打开时钟'" >/dev/null 2>&1
for _ in $(seq 1 40); do naps 3; sh logcat -d -s WPSvc:* | grep -q "收工:" && break; done
D=$(sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
[ -z "$D" ] || [ "$D" = "0" ] && { echo "没有副屏,测不了"; exit 1; }
echo "   副屏 = $D"
echo

for MODE in 0 1 2 3; do
  case $MODE in
    0) NAME="只 setLaunchDisplayId(对照)" ;;
    1) NAME="+ setAvoidMoveToFront" ;;
    2) NAME="+ setTransientLaunch" ;;
    3) NAME="+ 两个都加" ;;
  esac
  # 每组都从干净状态开始:目标 App 关掉,机主键盘重新叫出来
  sh am force-stop "$PKG" >/dev/null 2>&1
  naps 1
  raise_ime >/dev/null 2>&1
  if [ "$(ime)" != "true" ]; then printf "  %-30s 准备失败(键盘没叫起来),跳过\n" "$NAME"; continue; fi
  F0=$(foc)
  OUT=$(sh "CLASSPATH=$DEX app_process /data/local/tmp ai.whalephone.LaunchOpts $D $PKG $MODE" 2>&1 | tail -1)
  naps 3
  M=$(ime); F1=$(foc); N=$(onvd "$D")
  printf "  %-30s 焦点 %s->%-3s 键盘=%-6s 副屏上有目标App=%s  %s\n" \
    "$NAME" "$F0" "$F1" "$M" "$([ "$N" -gt 0 ] && echo 是 || echo 否)" \
    "$([ "$M" = "true" ] && [ "$N" -gt 0 ] && echo '← 两条都满足' || echo '')"
  echo "      $OUT"
done

echo
echo "  「两条都满足」的那一行才是可用的替代方案:键盘没被收起,而且 App 真的启到副屏上了。"
echo "  只满足前一条说明它根本没启动,是个假阳性。"
