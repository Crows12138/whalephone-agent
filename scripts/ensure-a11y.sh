#!/usr/bin/env bash
# 确保这个 app 的无障碍服务是开着的。
#
# 为什么需要这个脚本:每次 `adb install` 重装之后,One UI 会把它关掉,而表现是
# 任务一发就 FAIL「无障碍服务没开,agent 没有眼睛」—— 演示当场撞上就得重来。
#
# **两条 setting 的写入顺序是有讲究的**,这是实测出来的:
#   先写服务列表、再写 accessibility_enabled  →  列表被系统回滚,我们的服务被抹掉
#   先写 accessibility_enabled=1、再写服务列表 →  留得住
# 大概是 AccessibilityManagerService 在总开关为 0 时会把列表规范化一遍。
# 顺序写反了不会报错,只是悄悄没生效 —— 所以下面写完一定要读回来核对。
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. scripts/lib.sh

SVC="ai.whalephone.agent/ai.whalephone.agent.EyesAndHands"

cur=$(sh settings get secure enabled_accessibility_services)
[ "$cur" = "null" ] && cur=""

if [ "$(sh settings get secure accessibility_enabled)" = "1" ] && case "$cur" in *"$SVC"*) true;; *) false;; esac; then
  echo "无障碍已经开着"
  exit 0
fi

# 别踩掉机主自己开的那些服务,只往后面追加
case "$cur" in
  *"$SVC"*) want="$cur" ;;
  "")       want="$SVC" ;;
  *)        want="$cur:$SVC" ;;
esac

sh settings put secure accessibility_enabled 1 >/dev/null
sh settings put secure enabled_accessibility_services "$want" >/dev/null
python -c "import time;time.sleep(3)"

back=$(sh settings get secure enabled_accessibility_services)
case "$back" in
  *"$SVC"*) echo "无障碍已打开" ;;
  *) echo "✗ 写进去又被系统抹掉了。多半是「受限设置」在拦(旁装的 app)——"
     echo "  手机上:设置 → 应用 → 鲸鱼助手 → 右上角 ⋮ → 允许受限设置,再打开无障碍"
     exit 1 ;;
esac
