#!/usr/bin/env bash
# 往副屏启 App,有没有一种方式不抢走机主那块屏的焦点。
#
# `am start --display N` 是全流程里唯一既抢焦点又(在某些条件下)收键盘的操作。
# 现在整套让路机制都围着它转:机主在打字就不启、启之前先等。如果能找到一种不抢焦点的
# 启动方式,让路就从「必需」降级成「保险」,而 ANR 那条链也就从源头断了。
#
# 四组对照,变量只有 ActivityOptions:
#   0 只 setLaunchDisplayId        —— 对照组,等价于 am start --display
#   1 加 setAvoidMoveToFront
#   2 加 setTransientLaunch
#   3 两个都加
#
# 判据三条,少一条都是假阳性:
#   键盘还在吗          —— 要解决的问题之一
#   它是副屏的顶层吗    —— 「不抢焦点但也没 resumed」看着最像成功,其实 App 在桌面后面
#   无障碍读得出元素吗  —— 读不出来 agent 就是瞎的,启了等于没启
#
# 用法: bash scripts/tests/test-launch-opts.sh [包名] [每组重复次数]
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
PKG="${1:-com.sec.android.app.popupcalculator}"
REP="${2:-2}"
DEX=/data/local/tmp/launchopts.dex
naps() { python -c "import time;time.sleep($1)"; }
ime()  { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }
foc()  { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+$" | head -1; }
ontop() { sh dumpsys activity activities | sed -n "/Display #$1 /,/^Display #/p" | grep -m1 topResumedActivity | grep -c "$PKG"; }
readable() {
  sh logcat -c
  bc -a $A.SNAP --ei display "$1" >/dev/null 2>&1
  naps 2
  sh logcat -d -s WPEyes:* | grep -cE '\[[0-9]+\]'
}

"$ADB" push "$WROOT/probe/outlaunch/classes.dex" $DEX >/dev/null 2>&1 || { echo "推 dex 失败"; exit 1; }

# 「元素」那一列靠无障碍读。A11yGate 会在任务收工后把权限关掉,那之后 SNAP 广播
# 没人接,这一列会整列变成 0 —— 而 0 看起来正好像「读不到」,足以让人得出反的结论。
# 所以先确认这条通路是活的,不活就别开跑。
sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
naps 3
sh logcat -c; bc -a $A.DUMP >/dev/null 2>&1; naps 2
sh logcat -d -s WPEyes:* | grep -q "显示器" || {
  echo "无障碍服务没响应,「元素」这一列会整列作废,先把它跑起来再测"; exit 1; }

echo "== 先让 agent 造一块副屏出来 =="
bc -a $A.RUN --es goal "'打开时钟'" >/dev/null 2>&1
for _ in $(seq 1 40); do naps 3; sh logcat -d -s WPSvc:* | grep -q "收工:" && break; done
D=$(sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1)
{ [ -z "$D" ] || [ "$D" = "0" ]; } && { echo "没有副屏,测不了"; exit 1; }
echo "   副屏 = $D,每组重复 $REP 次"
echo

for MODE in 0 1 2 3; do
  case $MODE in
    0) NAME="对照 只 setLaunchDisplayId" ;;
    1) NAME="+ setAvoidMoveToFront" ;;
    2) NAME="+ setTransientLaunch" ;;
    3) NAME="+ 两个都加" ;;
  esac
  for R in $(seq 1 "$REP"); do
    sh am force-stop "$PKG" >/dev/null 2>&1
    naps 1
    raise_ime >/dev/null 2>&1
    if [ "$(ime)" != "true" ]; then printf "  %-26s #%s 准备失败(键盘没叫起来)\n" "$NAME" "$R"; continue; fi
    F0=$(foc)
    sh "CLASSPATH=$DEX app_process /data/local/tmp ai.whalephone.LaunchOpts $D $PKG $MODE" >/dev/null 2>&1
    naps 3
    M=$(ime); F1=$(foc); TOP=$(ontop "$D"); EL=$(readable "$D")
    OK=""
    [ "$M" = "true" ] && [ "$TOP" -gt 0 ] && [ "$EL" -gt 3 ] && OK="← 可用"
    printf "  %-26s #%s 焦点 %s->%-3s 键盘=%-6s 顶层是它=%s 元素=%-3s %s\n" \
      "$NAME" "$R" "$F0" "$F1" "$M" "$([ "$TOP" -gt 0 ] && echo 是 || echo 否)" "$EL" "$OK"
  done
done

echo
echo "  标「可用」的才算数:键盘没被收起、App 是副屏的顶层、无障碍读得出它的元素。"
echo "  少任何一条都是假阳性 —— 尤其「不抢焦点但也没 resumed」那种,看着最像成功。"
