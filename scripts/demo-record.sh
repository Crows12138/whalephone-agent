#!/usr/bin/env bash
# 配合手机自带录屏拍演示视频。不接相机、不接电脑做画中画。
#
# 为什么手机录屏就够:副屏本身是看不见的,但「看副屏」取景窗已经把它搬到主屏一角,
# 而录屏录的是主屏合成后的画面 —— 悬浮窗在里面(实测确认:录出来的帧里取景窗和
# 里面的计算器都在)。于是一段录屏同时包含「机主的界面」和「agent 在干什么」,
# 正好是题目要的「镜头能同时看到用户屏幕和任务完成」。
#
# 为什么用系统录屏而不是 adb screenrecord:系统录屏能录旁白、没有 3 分钟上限、
# 文件直接进相册。而 adb 那个停不干净 —— 实测 `kill -INT` 打过去进程不退、
# mp4 停在 33 KB 收不了尾,得靠 --time-limit 自然结束,而任务耗时是不定的。
#
# 脚本负责机器能做的:查环境、开取景窗、按你的节奏掐时间发任务、播报任务结束。
# **打字的人只能是你**,而且要真人用输入法打中文 —— `input text` 注入的是按键
# 事件,不经过输入法的组词缓冲,而「正在组词」恰恰是这个项目最难的一条判据
# (见 FINDINGS)。注入录出来的视频看着一样,却没演示到那条路。
#
# 用法:
#   bash scripts/demo-record.sh                 # 默认目标(比价)
#   bash scripts/demo-record.sh "你的目标"
#   LEAD=20 bash scripts/demo-record.sh         # 改开录到发任务之间的间隔
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. scripts/lib.sh

A=ai.whalephone.agent
GOAL="${1:-帮我看看 AirPods Pro 2 在淘宝上现在最便宜多少,前三个价格都告诉我}"

# 你按下回车之后,留多少秒让你切到微信、把光标放进输入框、开始打字
LEAD="${LEAD:-15}"

say() { echo; echo "▶ $*"; }
die() { echo "✗ $*"; exit 1; }

# ---------------------------------------------------------------- 开录前的体检
# 这几条任何一条不成立,任务都会在录像已经开始之后才失败 —— 那时你要重录一遍。
say "体检"
[ -n "$(sh pidof shizuku_server)" ] || die "Shizuku 没在跑。手机上重开一次,或 bash scripts/start-shizuku.sh"
echo "  Shizuku 在跑"

sh "run-as $A cat shared_prefs/whalephone.xml" | grep -q 'LLM_API_KEY' \
  || die "模型密钥还没填(设置页第一栏)"
echo "  模型接口已配置"

# 副屏和主屏共用电源组,主屏一灭副屏跟着灭,那一轮什么都做不了
sh dumpsys power | grep -q "mWakefulness=Awake" || die "先把屏幕点亮(副屏跟着主屏的电源组走)"
echo "  屏幕亮着"

# ------------------------------------------------------------------ 打开取景窗
# 坐标不写死:布局一变就点空,而点空了你要到录像里才发现。
say "打开取景窗"
if sh dumpsys window windows | grep -qE "Window\{[a-f0-9]+ u0 $A\}"; then
  echo "  已经开着"
else
  sh am start -n $A/.MainActivity >/dev/null
  python -c "import time;time.sleep(2)"
  sh uiautomator dump /sdcard/wp-ui.xml >/dev/null 2>&1
  B=$(sh cat /sdcard/wp-ui.xml | tr '<' '\n' | grep -F 'text="看副屏"' \
      | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
  sh rm -f /sdcard/wp-ui.xml >/dev/null 2>&1
  [ -n "$B" ] || die "界面上找不到「看副屏」开关"
  set -- $B
  sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null
  python -c "import time;time.sleep(2)"
  sh dumpsys window windows | grep -qE "Window\{[a-f0-9]+ u0 $A\}" \
    || die "点了开关但取景窗没起来 —— 多半是「显示在其他应用上层」权限还没给"
  echo "  已打开"
fi

# ------------------------------------------------------------------------ 开拍
cat <<TIP

────────────────────────────────────────────────────────
  目标:$GOAL

  顺序:
    1. 下拉快捷面板,点「屏幕录制」,开始录
    2. 回来按这里的回车
    3. 立刻切到微信(或备忘录),**用中文打字,一直不停**
    4. 第 ${LEAD} 秒 agent 自己开工。别管它,继续打
    5. 脚本会播报「任务结束」。再录十几秒,回微信核对这四条:
         · 刚才打的字一个不少,中间没有断点
         · 页面没有莫名其妙后退
         · 长按输入框粘贴,还是你演示前复制的那段
         · 最近任务里没多出你没开过的界面
    6. 停止录屏。视频在相册里

  取景窗挡住输入框的话拖开它,整个窗口哪里都能拖。
  录之前建议先复制一段文字(用来核对第 3 条),并且用一个不介意入镜的聊天窗口。
────────────────────────────────────────────────────────
TIP
read -r -p "录屏已经开始了?按回车,${LEAD} 秒后发任务…" _

for i in $(seq "$LEAD" -1 1); do printf "\r  %2d 秒后发任务,开始打字… " "$i"; python -c "import time;time.sleep(1)"; done
printf "\r%40s\r" " "

say "发任务"
bc -a $A.RUN --es goal "'$GOAL'" >/dev/null

# 等任务结束。看 app 自己落盘的对话记录,不看 logcat —— 这台机器日志转得太快,
# 长任务的开头会被冲掉,而这里只关心「最后一行是不是结论」。
say "等它做完"
for _ in $(seq 90); do
  python -c "import time;time.sleep(2)"
  K=$(sh "run-as $A cat files/chat.json" | python -c "
import sys,json
try: d=json.loads(sys.stdin.read())
except Exception: raise SystemExit
t=d[-1]
print(t[-1].get('k','') if t else '')
" 2>/dev/null)
  case "$K" in RESULT|FAIL) break;; esac
done

sh "run-as $A cat files/chat.json" | python -c "
import sys,json
d=json.loads(sys.stdin.read()); t=d[-1]
last = t[-1] if t else {}
print()
print('══ 任务结束 ══', last.get('k',''))
print((last.get('t') or '(空)'))
print()
print('现在回微信核对那四条,核完停止录屏。')
" 2>/dev/null

echo
echo "不满意就再跑一次,这个脚本可以反复来。"
echo "全部录完之后,回主界面把「看副屏」关掉。"
