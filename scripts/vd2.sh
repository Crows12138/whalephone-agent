#!/usr/bin/env bash
# agent 副屏的开发期夹具。
#
# 和上一版 vd.sh(拿 scrcpy 当持屏者)的区别:
#   - 持屏者是 app_process 里的一小段 Java,直接调 DisplayManager.createVirtualDisplay,
#     标志位由我们自己给 —— scrcpy 给不了 OWN_FOCUS / ALWAYS_UNLOCKED 这些。
#   - 画面出口是 ImageReader,截图就是读一个 PNG,不用录屏再抽帧。
#   - 进程用 nohup 脱离 adb 会话,不然一次 adb shell 结束就被 SIGHUP 带走。
#
# 用法: vd2.sh start [flags十六进制] | id | png [输出路径] | stop | status

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

DEX=//data/local/tmp/flagprobe.dex
DLOG=//data/local/tmp/hold.log
STATE2="$WROOT/.vd2_state"

case "${1:-status}" in
  start)
    FLAGS="${2:-0x5e08}"
    "$ADB" shell "pkill -f flagprobe" >/dev/null 2>&1
    "$ADB" shell "rm -f $DLOG" >/dev/null 2>&1
    "$ADB" shell "nohup sh -c 'CLASSPATH=$DEX exec app_process /data/local/tmp ai.whalephone.FlagProbe 86400 $FLAGS' > $DLOG 2>&1 &" >/dev/null 2>&1
    for i in $(seq 1 20); do
      python -c "import time;time.sleep(0.6)"
      ID=$("$ADB" shell "grep -oE 'HOLD displayId=[0-9]+' $DLOG" 2>/dev/null | grep -oE '[0-9]+$' | tail -1)
      [ -n "$ID" ] && break
    done
    if [ -z "$ID" ]; then echo "造屏失败:"; "$ADB" shell "cat $DLOG" | tr -d '\r' | tail -10; exit 1; fi
    echo "$ID" > "$STATE2"
    echo "副屏 id=$ID  flags=$FLAGS"
    ;;
  id)
    cat "$STATE2" 2>/dev/null
    ;;
  png)
    OUT="${2:-$WROOT/.vd.png}"
    "$ADB" pull //data/local/tmp/vd.png "$OUT" >/dev/null 2>&1 && echo "$OUT" || echo "没有截图"
    ;;
  stop)
    "$ADB" shell "pkill -f flagprobe" >/dev/null 2>&1
    rm -f "$STATE2"
    echo "已停止"
    ;;
  status)
    ID=$(cat "$STATE2" 2>/dev/null)
    ALIVE=$("$ADB" shell "pgrep -f flagprobe | head -1" 2>/dev/null | tr -d '\r')
    echo "记录的 id=${ID:-无}  持屏进程=${ALIVE:-没在跑}"
    "$ADB" shell "dumpsys display | grep -oE '^  mDisplayId=[0-9]+'" 2>/dev/null | tr -d '\r'
    ;;
esac
