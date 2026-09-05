#!/usr/bin/env bash
# 等机主解锁,解锁那一刻再接管:开着「充电保持亮屏」跑验收,跑完还原。
#
# 分成「等」和「跑」两段,是因为锁屏时把屏幕钉亮几小时是我强加给机主的副作用,
# 而验收本身又需要屏幕别中途锁上。只在真的开跑那一刻才占用这个设置。
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"
locked() { sh dumpsys window | grep -m1 -oE "mDreamingLockscreen=[a-z]+" | cut -d= -f2; }

for i in $(seq 1 720); do            # 最多等一小时
  [ "$(locked)" = "false" ] && break
  python -c "import time;time.sleep(5)"
done
[ "$(locked)" != "false" ] && { echo "等了一小时还锁着,没跑"; exit 1; }
echo "机主解锁了,开始验收"

PREV_STAYON=$(sh settings get global stay_on_while_plugged_in)
sh svc power stayon usb >/dev/null 2>&1
restore() { sh settings put global stay_on_while_plugged_in "${PREV_STAYON:-0}" >/dev/null 2>&1
            echo "亮屏设置已还原 = $(sh settings get global stay_on_while_plugged_in)"; }
trap restore EXIT

bash "$(dirname "${BASH_SOURCE[0]}")/verify-fix.sh"
