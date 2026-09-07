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
# 脚本负责机器能做的:查环境、开悬浮窗、发任务(或等你从悬浮球发)、播报任务结束。
# **打字的人只能是你**,而且要真人用输入法打中文 —— `input text` 注入的是按键
# 事件,不经过输入法的组词缓冲,而「正在组词」恰恰是这个项目最难的一条判据
# (见 FINDINGS)。注入录出来的视频看着一样,却没演示到那条路。
#
# 两种发任务的方式:
#
#   FROM=ball bash scripts/demo-record.sh       # 你自己点悬浮球下任务(推荐)
#   bash scripts/demo-record.sh "你的目标"       # 脚本用 adb 发
#
# 推荐前者:视频里全程不出现电脑,而且顺带演示了「不打开 app 也能下任务、点一下
# 就地展开语音、不抢你的键盘」—— 那是这个项目最能说明问题的一个动作。后者只在
# 语音识别不配合、或者你想反复跑同一个目标时用。
#
#   LEAD=20 bash scripts/demo-record.sh         # 改开录到发任务之间的间隔
set -u
cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1
. scripts/lib.sh

A=ai.whalephone.agent
GOAL="${1:-打开淘宝,搜索 AirPods Pro 2,把搜索结果里第一个商品加入购物车}"
FROM="${FROM:-adb}"

# 你按下回车之后,留多少秒让你切到那个 App、把光标放进输入框、开始打字
LEAD="${LEAD:-15}"

# 开录前把这个包停掉,让任务从「刚打开的 App」开始而不是上一轮的残留页面。
# 设成空串就不重置。
RESET_PKG="${RESET_PKG-com.taobao.taobao}"

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

# 每次 adb install 之后 One UI 都会把无障碍关掉,而表现是任务一发就 FAIL。
# 这条不提醒、直接修 —— 它没有第二种正确状态。
bash scripts/ensure-a11y.sh || die "无障碍打不开,任务发出去也没有眼睛"

# 看图那条路:淘宝的商品详情、购物车整页自绘,无障碍树里读不出内容,只能靠截图。
# VLM_MODEL 留空的话整条路是关的,而失败要等任务跑到那一步才暴露。
if sh "run-as $A cat shared_prefs/whalephone.xml" | grep -q '<string name="VLM_MODEL"></string>'; then
  echo "  ! 看图那条路没开(VLM_MODEL 是空的)。纯读的任务不受影响,淘宝这类会卡住"
else
  echo "  看图那条路开着"
fi

# 通知弹进画面就得重录。这条只提醒不拦 —— 有人就是想录真实环境。
case "$(sh settings get global zen_mode)" in
  1|2|3) echo "  勿扰开着" ;;
  *)     echo "  ! 勿扰没开,微信/邮件弹出来会直接进画面(下拉面板点一下)" ;;
esac

# App 上一轮被停在哪一页,下一轮 launch 回去还是那一页。实测过一次:上一个任务
# 停在淘宝购物车,这一轮的「搜索」就搜进了**购物车内搜索框**,模型照样报了个
# 「第一个商品」,而那是购物车搜不到时的降级列表。演示要从确定的起点开始。
# 只在机主此刻没在用它的时候停 —— 在用的话这一条跳过,别去动机主的前台。
if [ -n "$RESET_PKG" ]; then
  if sh dumpsys window displays | sed -n '/mDisplayId=0/,/mDisplayId=[1-9]/p'        | grep -q "mCurrentFocus.*$RESET_PKG"; then
    echo "  ! 机主正在用 $RESET_PKG,没去重置它 —— 任务会从他停的那一页开始"
  else
    sh am force-stop "$RESET_PKG" >/dev/null
    echo "  $RESET_PKG 已回到干净起点"
  fi
fi

# 副屏和主屏共用电源组,主屏一灭副屏跟着灭,那一轮什么都做不了
sh dumpsys power | grep -q "mWakefulness=Awake" || die "先把屏幕点亮(副屏跟着主屏的电源组走)"
echo "  屏幕亮着"

# ------------------------------------------------------------------ 打开悬浮窗
# 开关的坐标不写死:布局一变就点空,而点空了你要到录像里才发现。
# 从悬浮球下任务的话两个都要;脚本发任务的话只要取景窗,但都开着也不碍事。
say "打开悬浮窗"
WANT="看副屏"
[ "$FROM" = "ball" ] && WANT="看副屏 悬浮球"
HAVE=$(sh dumpsys window windows | grep -cE "Window\{[a-f0-9]+ u0 $A\}")
NEED=$(set -- $WANT; echo $#)
if [ "$HAVE" -ge "$NEED" ]; then
  echo "  已经开着($HAVE 个)"
else
  sh am start -n $A/.MainActivity >/dev/null
  python -c "import time;time.sleep(2)"
  sh uiautomator dump /sdcard/wp-ui.xml >/dev/null 2>&1
  for T in $WANT; do
    B=$(sh cat /sdcard/wp-ui.xml | tr '<' '\n' | grep -F "text=\"$T\"" \
        | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | tr '\n' ' ')
    [ -n "$B" ] || die "界面上找不到「$T」开关"
    set -- $B
    sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null
    python -c "import time;time.sleep(2)"
  done
  sh rm -f /sdcard/wp-ui.xml >/dev/null 2>&1
  sh dumpsys window windows | grep -qE "Window\{[a-f0-9]+ u0 $A\}" \
    || die "点了开关但悬浮窗没起来 —— 多半是「显示在其他应用上层」权限还没给"
  echo "  已打开"
fi

# 从悬浮球下任务时,得知道「新的一条任务开始了」。拿开录前的行数当基线:
# 对话记录是只追加的,多出来的那些就是这一轮。
BASE=$(sh "run-as $A cat files/chat.json" | python -c "
import sys,json
try: print(len(json.loads(sys.stdin.read())[-1]))
except Exception: print(0)
" 2>/dev/null)

# ------------------------------------------------------------------------ 开拍
cat <<TIP

────────────────────────────────────────────────────────
  目标:$GOAL

  发任务的方式:$([ "$FROM" = ball ] && echo '你点悬浮球(脚本只旁观)' || echo "脚本在第 ${LEAD} 秒用 adb 发")

  顺序:
    1. 下拉快捷面板,点「屏幕录制」,开始录
    2. 回来按这里的回车
    3. 立刻切到那个 App,**用中文打字,一直不停**
    4. $([ "$FROM" = ball ] && echo '打一会儿之后,手不离开这个界面,点一下悬浮球,说出目标' || echo "第 ${LEAD} 秒 agent 自己开工")。别管它,继续打
    5. 脚本会播报「任务结束」。再录十几秒,回去核对这四条:
         · 刚才打的字一个不少,中间没有断点
         · 页面没有莫名其妙后退
         · 长按输入框粘贴,还是你演示前复制的那段
         · 最近任务里没多出你没开过的界面
    6. 停止录屏。视频在相册里

  取景窗挡住输入框的话拖开它,整个窗口哪里都能拖。
  录之前先复制一段文字(用来核对第 3 条)。录屏会把屏上一切都录进去,
  所以打字的目标选一个不介意入镜的 —— AI 聊天、备忘录都行,别用真实聊天记录。
────────────────────────────────────────────────────────
TIP
read -r -p "录屏已经开始了?按回车…" _

if [ "$FROM" = "ball" ]; then
  say "等你点悬浮球下任务(最多 3 分钟)"
  for _ in $(seq 90); do
    python -c "import time;time.sleep(2)"
    N=$(sh "run-as $A cat files/chat.json" | python -c "
import sys,json
try: print(len(json.loads(sys.stdin.read())[-1]))
except Exception: print(0)
" 2>/dev/null)
    [ "${N:-0}" -gt "${BASE:-0}" ] && { echo "  收到了"; break; }
  done
else
  for i in $(seq "$LEAD" -1 1); do printf "\r  %2d 秒后发任务,开始打字… " "$i"; python -c "import time;time.sleep(1)"; done
  printf "\r%40s\r" " "
  say "发任务"
  bc -a $A.RUN --es goal "'$GOAL'" >/dev/null
fi

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
print('现在回去核对那四条,核完停止录屏。')
" 2>/dev/null

echo
echo "不满意就再跑一次,这个脚本可以反复来。"
echo "全部录完之后,回主界面把「看副屏」关掉。"
