#!/bin/bash
# 虚拟屏生命周期:vd.sh start [pkg] | id | stop | status
cd "$(dirname "$0")/.."; source scripts/lib.sh
case "${1:-status}" in
  start)
    [ -f "$STATE" ] && { echo "已有虚拟屏 (id=$(cut -d: -f1 "$STATE")),先 stop"; exit 1; }
    rm -f "$LIVE_MKV"
    ARGS="--new-display=1080x2340/450 --no-audio --no-window --record=$LIVE_MKV --record-format=mkv --max-fps=5"
    [ -n "$2" ] && ARGS="$ARGS --start-app=$2"
    "$SCRCPY" $ARGS >/tmp/vd_scrcpy.log 2>&1 &
    P=$!; sleep 7
    ID=$(grep -oE "New display: [0-9x/]+ \(id=[0-9]+\)" /tmp/vd_scrcpy.log | grep -oE "id=[0-9]+" | cut -d= -f2)
    [ -z "$ID" ] && { echo "启动失败:"; tail -5 /tmp/vd_scrcpy.log; kill $P 2>/dev/null; exit 1; }
    echo "$ID:$P" > "$STATE"; echo "虚拟屏已启动 id=$ID pid=$P"
    ;;
  id)     [ -f "$STATE" ] && cut -d: -f1 "$STATE" || { echo "无"; exit 1; } ;;
  stop)   [ -f "$STATE" ] && { kill "$(cut -d: -f2 "$STATE")" 2>/dev/null; sleep 1; rm -f "$STATE"; echo "已停止"; } || echo "本来就没开" ;;
  status)
    if [ -f "$STATE" ]; then echo "虚拟屏 id=$(cut -d: -f1 "$STATE")  pid=$(cut -d: -f2 "$STATE")"
    else echo "未启动"; fi
    echo "系统显示器: $(sh dumpsys display | grep -oE '^  mDisplayId=[0-9]+' | grep -oE '[0-9]+$' | sort -nu | tr '\n' ' ')"
    echo "焦点屏=$(foc)  IME: $(ime)"
    ;;
esac
