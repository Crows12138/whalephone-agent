#!/usr/bin/env bash
# 「机主在打字」这个判据,分不分得清键盘是为谁开的。
#
# 为什么要单独测:这个判据挂在每一个动作前面,它说的和想说的差一点点就会出大事。
# 想说的是「机主正在往**他自己的**东西里打字,现在抢焦点会让他丢掉推不回来的输入
# 状态」;写出来的是「主屏上有输入法窗口」。两者在一种情况下分道扬镳 ——
# 机主在 WhalePhone 自己的输入框里打完任务、点「开始」。点按钮不会自动收键盘,
# 于是 agent 判定「机主在打字」,等满上限,回一句「这一轮先不开工」。
# **他刚下的任务被他下达任务的动作挡住了**,而且这是主演示路径。
#
# 两个方向都要测,只测一边等于没测:
#   A 键盘为我们自己开着   -> 判 false,任务照常开工
#   B 键盘为别的 app 开着   -> 判 true,任务避让(这一半由 test-a11y-gate.sh 的 5b 覆盖,
#                              这里只复验判据本身,不再跑整轮)
#
# 用法: ANDROID_SERIAL=emulator-5554 bash scripts/tests/test-owner-typing.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
naps() { python -c "import time;time.sleep($1)"; }
PASS=0; FAIL=0
ok() { PASS=$((PASS+1)); printf "   ✓ %s\n" "$1"; }
no() { FAIL=$((FAIL+1)); printf "   ✗ %s\n" "$1"; }
ime() { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+" | head -1 | cut -d= -f2; }

# 这个脚本测的是探针,不是整轮任务,所以 A11yGate 不会被调用 —— 无障碍得自己开。
# 上一轮任务收工时 A11yGate 会把它关掉(按设计不常驻),不开的话 EyesAndHands 里的
# 动态接收器根本不存在,TYPING 广播静默地没人收,probe 返回空字符串,
# 而那个空字符串会被下面的判断报成「判据说错了」—— 测试在冤枉产品。栽过一次。
SVC=$A/.EyesAndHands
PREV_SVC=$(sh settings get secure enabled_accessibility_services)
PREV_EN=$(sh settings get secure accessibility_enabled)
restore() {
  if [ "$PREV_SVC" = "null" ]; then sh settings delete secure enabled_accessibility_services >/dev/null 2>&1
  else sh settings put secure enabled_accessibility_services "$PREV_SVC" >/dev/null 2>&1; fi
  sh settings put secure accessibility_enabled "${PREV_EN:-0}" >/dev/null 2>&1
}
trap restore EXIT
sh settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
for _ in $(seq 1 20); do
  sh logcat -d -s WPEyes:* 2>/dev/null | grep -q "无障碍服务已连接" && break
  naps 1
done

# TYPING 探针会把两路判断都打出来。取无障碍那一路的结论。
probe() {
  sh logcat -c; bc -a $A.TYPING >/dev/null 2>&1; naps 2
  # 只认「判据结论」这一行 —— 那是让路机制真正用的值。
  # 原始信号(无障碍/IMMS)只用来排查,不能拿来当结论。
  sh logcat -d -s WPEyes:* | grep -m1 "判据结论:" | grep -oE "(true|false)" | head -1
}

# 探针没人收的时候 probe 返回空。空不是「判错了」,是「没测到」,必须分开 ——
# 混在一起的话,无障碍一关整份报告就变成一串看着很像产品 bug 的假失败。
need_verdict() {
  [ -n "$1" ] && return 0
  echo "   TYPING 探针没有输出 —— 广播没人收(无障碍是不是没起来?),这一条测不了"
  exit 1
}

echo "== A. 键盘为 WhalePhone 自己的输入框开着 =="
sh input -d 0 keyevent 4 >/dev/null 2>&1
sh am start --display 0 -n $A/.MainActivity >/dev/null 2>&1; naps 4
sh uiautomator dump /sdcard/wp.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/wp.xml 2>/dev/null | tr '<' '\n' > "$UIDUMP"
B=$(grep -F 'class="android.widget.EditText"' "$UIDUMP" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '\n' ' ')
[ -z "$B" ] && { echo "   找不到本 app 的输入框,测不了"; exit 1; }
set -- $B; sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1; naps 3
[ "$(ime)" = "true" ] || { echo "   键盘没弹起来,测不了"; exit 1; }
echo "   键盘已弹出,前台是 $(sh dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+' | head -1)"
P=$(probe)
need_verdict "$P"
if [ "$P" = "false" ]; then ok "判据说「不是机主在打字」—— 这是我们自己的键盘"
else no "判据说 $P —— 机主刚下的任务会被他下达任务的动作挡住"; fi

# 判据对了还不够,要看整条链:任务真的开工了吗
echo "   下一个任务,看它是开工还是避让(键盘保持弹着)"
sh logcat -c
bc -a $A.RUN --es goal "'打开时钟'" >/dev/null 2>&1
R=timeout
for _ in $(seq 1 40); do
  naps 2
  sh logcat -d -s WPSvc:* | grep -q "先不开工" && { R=refused; break; }
  sh logcat -d -s WPSvc:* WPAgent:* | grep -qE "副屏 [0-9]+ 就绪|第 1 步" && { R=started; break; }
done
case "$R" in
  started) ok "任务照常开工了" ;;
  refused) no "任务被挡住了 —— 判据虽然对,链路上还有别处在拦" ;;
  *)       no "80 秒内既没开工也没避让,读不出结论" ;;
esac
sh input -d 0 keyevent 4 >/dev/null 2>&1

echo
echo "== B. 键盘为别的 app 开着 =="
for _ in $(seq 1 60); do naps 2; sh logcat -d -s WPSvc:* | grep -q "收工:" && break; done
sh input -d 0 keyevent 3 >/dev/null 2>&1; naps 2
if raise_ime; then
  echo "   键盘已弹出,前台是 $(sh dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.]+' | head -1)"
  P=$(probe)
  need_verdict "$P"
  if [ "$P" = "true" ]; then ok "判据说「机主在打字」—— 这是别人的键盘,该让"
  else no "判据说 $P —— 机主真在打字却判不出来,让路机制一次都不会触发"; fi
  sh input -d 0 keyevent 4 >/dev/null 2>&1
else
  echo "   输入法没叫起来,这一半测不了(不计入结论)"
fi

echo
echo "== 结论:$PASS 条过,$FAIL 条不过 =="
[ "$FAIL" -eq 0 ] || exit 1
