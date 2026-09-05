#!/usr/bin/env bash
# 把模拟器准备成能跑测试的样子。开机后、或者跑久了状态脏了重启之后,跑这个。
#
# 为什么需要它:模拟器不像真机那样有人手动配过。Shizuku 要手动起(它不是开机自启),
# 无障碍要预置,而且 `adb root` 会重启 adbd,紧跟着的 settings put 会被静默丢掉。
#
# 另外记一笔:连续跑几十轮任务之后 IMMS 里会堆一大堆历史虚拟屏的 ClientState,
# 软键盘会叫不起来。那不是被测代码的问题,是模拟器脏了 —— 重启一下再跑这个脚本。
#
# 用法: ANDROID_SERIAL=emulator-5554 bash scripts/tests/emu-setup.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
A=ai.whalephone.agent
SVC=$A/.EyesAndHands
APK="$WROOT/app/build/outputs/apk/debug/app-debug.apk"
naps() { python -c "import time;time.sleep($1)"; }

echo "== 等开机 =="
# 先等设备起来再判断它是不是模拟器 —— 重启途中 getprop 读回来是空的,
# 会被误判成「连的不是模拟器」。
"$ADB" wait-for-device
until [ "$(sh getprop sys.boot_completed | tr -d '\r')" = "1" ]; do naps 2; done
sh getprop ro.build.characteristics | grep -q emulator || {
  echo "连的不是模拟器,这个脚本只在模拟器上跑"; exit 1; }
"$ADB" root >/dev/null 2>&1; naps 3; "$ADB" wait-for-device

echo "== 装包 =="
"$ADB" install -r "$APK" 2>&1 | tr -d '\r' | tail -1

echo "== 起 Shizuku =="
# libshizuku.so 是 ELF 可执行文件,不是脚本 —— 用 sh 去跑它会喷一屏乱码。
DIR=$(sh pm path moe.shizuku.privileged.api | head -1 | sed 's/package://;s/\/base.apk//')
ABI=$(sh getprop ro.product.cpu.abi)
sh "$DIR/lib/${ABI//-/_}/libshizuku.so" 2>&1 | grep -E "pid is|exit with" | sed 's/^/   /'
sh pm grant $A moe.shizuku.manager.permission.API_V23 >/dev/null 2>&1
naps 2
echo "   shizuku_server: $(sh ps -A | grep -c shizuku_server) 个"

echo "== 预置无障碍 =="
# adb root 重启了 adbd,紧跟着的 settings put 会被静默丢掉(实测读回是 null)。
# 写完必须读回来确认,不然后面整套测试都在测一个没启用的服务,而且看不出来。
for _ in 1 2 3 4 5; do
  sh settings put secure enabled_accessibility_services "$SVC" >/dev/null 2>&1
  sh settings put secure accessibility_enabled 1 >/dev/null 2>&1
  naps 2
  [ "$(sh settings get secure enabled_accessibility_services)" = "$SVC" ] && break
done
echo "   $(sh settings get secure enabled_accessibility_services)"
echo
echo "准备好了。测试:bash scripts/tests/test-a11y-gate.sh"
