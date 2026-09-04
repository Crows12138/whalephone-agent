#!/bin/bash
# 操作:act.sh <displayId> tap X Y | text STR | key N | launch PKG | back | home
cd "$(dirname "$0")/.."; source scripts/lib.sh
D="$1"; shift
case "$1" in
  tap)    sh input -d "$D" tap "$2" "$3" ;;
  swipe)  sh input -d "$D" swipe "$2" "$3" "$4" "$5" "${6:-300}" ;;
  text)   sh input -d "$D" text "$2" ;;
  key)    sh input -d "$D" keyevent "$2" ;;
  back)   sh input -d "$D" keyevent 4 ;;
  home)   sh input -d "$D" keyevent 3 ;;
  launch) C=$(sh cmd package resolve-activity --brief "$2" | tail -1); echo "  -> $C"; sh am start --display "$D" -n "$C" ;;
  *) echo "用法: act.sh <displayId> tap X Y|swipe X1 Y1 X2 Y2|text STR|key N|back|home|launch PKG"; exit 1 ;;
esac
