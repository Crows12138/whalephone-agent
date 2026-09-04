#!/bin/bash
# 心跳:每 45 秒发一次 WAKEUP,阻止设备息屏(电池充满时 stayon 不生效)
cd "$(dirname "$0")/.."; source scripts/lib.sh
echo "keepawake 启动 (Ctrl-C 或 kill 停止)"
while true; do
  sh input keyevent 224 >/dev/null 2>&1
  sleep 45
done
