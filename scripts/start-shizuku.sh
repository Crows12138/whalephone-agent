#!/usr/bin/env bash
# 从电脑把 Shizuku 的服务拉起来。手机每重启一次就要跑一次。
#
# 为什么需要这个脚本:Shizuku 官方文档给的是 `adb shell sh .../start.sh`,
# 新版本里那个脚本已经不在了,真正的启动器是 APK 里的 libshizuku.so(一个 ELF,
# 不是 shell 脚本)。而它的路径带随机后缀,每次装/更新都会变,记不住也抄不了。
# 这里现查现用。
#
# 手机上不用电脑的等价做法:开发者选项打开「无线调试」-> 打开 Shizuku ->
# 「通过无线调试启动」。app 自己代劳不了,原因见 MainActivity.onShizuku 的注释。
#
# 用法: [ANDROID_SERIAL=<设备>] bash scripts/start-shizuku.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
PKG=moe.shizuku.privileged.api

DIR=$(sh pm path $PKG | head -1 | sed 's|package:||;s|/base.apk||')
[ -z "$DIR" ] && { echo "手机上没装 Shizuku"; exit 1; }

# 目录名带 == 之类的字符,原样传给 adb shell 会被再解析一次,要引起来
ABI=$(sh ls "$DIR/lib/" | head -1)
sh "$DIR/lib/$ABI/libshizuku.so"

python -c "import time;time.sleep(2)"
PID=$(sh pidof shizuku_server)
[ -n "$PID" ] && echo "Shizuku 已在运行 pid=$PID" || { echo "没起来"; exit 1; }
