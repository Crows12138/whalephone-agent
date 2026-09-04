#!/bin/bash
# 设备指纹探测 —— 只读,不改手机任何状态
ADB="/c/Users/12916/platform-tools/adb.exe"

line() { echo; echo "───── $1 ─────"; }

line "设备基本信息"
for p in ro.product.manufacturer ro.product.model ro.product.name \
         ro.build.version.release ro.build.version.sdk ro.build.version.security_patch \
         ro.build.version.oneui ro.build.version.sem ro.build.version.sep \
         ro.build.characteristics ro.config.knox; do
  v=$("$ADB" shell getprop $p 2>/dev/null | tr -d '\r')
  [ -n "$v" ] && printf "%-38s %s\n" "$p" "$v"
done

line "shell 身份与权限"
echo "uid: $("$ADB" shell id 2>/dev/null | tr -d '\r')"
echo "root 可用: $("$ADB" shell 'which su >/dev/null 2>&1 && echo yes || echo no' | tr -d '\r')"

line "input 命令是否支持 -d (指定显示器)"
"$ADB" shell input 2>&1 | tr -d '\r' | head -8

line "当前显示器"
"$ADB" shell dumpsys display 2>/dev/null | tr -d '\r' | grep -E "mDisplayId=|uniqueId=|mBaseDisplayInfo|state=|DisplayDeviceInfo" | head -30

line "DeX 相关"
"$ADB" shell pm list features 2>/dev/null | tr -d '\r' | grep -iE "dex|desktop|multi.?display|freeform" 
"$ADB" shell getprop 2>/dev/null | tr -d '\r' | grep -iE "dex|desktop" | head -10

line "Shizuku 是否已安装"
"$ADB" shell pm list packages 2>/dev/null | tr -d '\r' | grep -iE "shizuku|rikka" || echo "未安装"

line "多用户支持"
echo "max users: $("$ADB" shell pm get-max-users 2>/dev/null | tr -d '\r')"
"$ADB" shell pm list users 2>/dev/null | tr -d '\r'

line "无线调试端口 (Shizuku 免电脑启动需要)"
"$ADB" shell settings get global adb_wifi_enabled 2>/dev/null | tr -d '\r'
