#!/bin/bash
# 截图: see.sh        -> 主屏
#       see.sh <id>   -> 指定显示器(第二个 scrcpy 按需录 2 秒,ffmpeg 抽末帧)
cd "$(dirname "$0")/.."; source scripts/lib.sh
mkdir -p shots
TS=$(date +%H%M%S)

if [ -z "$1" ] || [ "$1" = "0" ]; then
  OUT="shots/main_$TS.png"
  "$ADB" exec-out screencap -p > "$OUT" 2>/dev/null
else
  OUT="shots/d$1_$TS.png"
  CAP="$WROOT/.cap.mp4"
  rm -f "$CAP"
  "$SCRCPY" --display-id="$1" --no-audio --no-window \
            --record="$CAP" --record-format=mp4 --time-limit=2 >"$WROOT/.see.log" 2>&1
  if [ ! -s "$CAP" ]; then echo "录制失败:"; tail -5 "$WROOT/.see.log"; exit 1; fi
  "$FFMPEG" -y -sseof -0.5 -i "$CAP" -update 1 -frames:v 1 "$OUT" >/dev/null 2>&1
  [ -s "$OUT" ] || "$FFMPEG" -y -i "$CAP" -update 1 -frames:v 1 "$OUT" >/dev/null 2>&1
  rm -f "$CAP"
fi

if [ -s "$OUT" ]; then
  echo "$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")  ($(stat -c %s "$OUT") bytes)"
else
  echo "截图失败"; exit 1
fi
