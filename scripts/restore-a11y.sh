#!/bin/bash
# 还原无障碍设置到装探针之前的状态
cd "$(dirname "$0")/.."; source scripts/lib.sh
SVC=$(sed -n 1p .backup/a11y.txt); EN=$(sed -n 2p .backup/a11y.txt)
[ "$SVC" = "null" ] && sh settings delete secure enabled_accessibility_services || sh settings put secure enabled_accessibility_services "$SVC"
[ "$EN" = "null" ] && sh settings delete secure accessibility_enabled || sh settings put secure accessibility_enabled "$EN"
echo "已还原: services=$SVC enabled=$EN"
