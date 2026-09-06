# 公共环境 —— 所有脚本 source 这个
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"
export ADB="C:/Users/12916/platform-tools/adb.exe"
export WROOT="C:/Users/12916/Desktop/工作/whalephone"          # Windows 形式,bash 和 Windows 程序都认
export LIVE_MKV="$WROOT/.vd_live.mkv"
export SNAP_MKV="$WROOT/.snap.mkv"
export FFMPEG="/c/Users/12916/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-8.0.1-full_build/bin/ffmpeg"
SCRCPY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/tools/scrcpy/scrcpy.exe"
STATE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.vd_state"
UIDUMP="${TMPDIR:-/tmp}/wp-ui.xml"   # 界面 dump 落在临时目录,不脏仓库

sh()   { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
vdid() { sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1; }
foc()  { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+"; }
ime()  { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+|mDisplayIdToShowIme=[0-9]+" | tr '\n' ' '; }

# 把机主的真实输入法叫出来,并且**光标真的落在一个可编辑控件里**。返回 0 = 成功。
#
# 为什么不能只看 mInputShown=true:真机上 Edge 的地址栏点下去键盘弹了,但输入焦点
# 没落在 url_bar 上(dump 里 focused="false")。此后所有注入都打了水漂,而脚本
# 以为准备就绪 —— 读数依然漂亮,测的却是空气。所以成功的判据是「有一个获焦的
# 可编辑控件」,不是「键盘露头了」。
#
# 载体按顺序试:Edge、系统设置的搜索。不用本 app 自己的输入框:焦点被挪走时
# 前台 app 会「Input dispatching timed out」而 ANR,ANR 对话框自己也会带走键盘 ——
# 拿会被待测现象弄坏的东西当载体,量出来的掉落分不清是谁造成的。
#
# 也不用本 app 的 SNAP/CLICK 广播找输入框:那要求无障碍已经在跑,而无障碍恰恰是
# 被测对象之一。用被测对象搭测试脚手架,它一坏,测出来的是「准备失败」而不是
# 「功能坏了」。uiautomator 走的是另一条独立通路,不吃这个依赖。
dumpui() {
  sh uiautomator dump /sdcard/wp.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/wp.xml 2>/dev/null | tr '<' '
' > "$UIDUMP"
}

# 此刻有没有一个获焦的可编辑控件 —— 注入进不进得去,全看这一条
focused_editor() {
  grep -E 'focused="true"' "$UIDUMP" | grep -qE 'class="[^"]*(EditText|AutoCompleteTextView)"'
}

# 在当前这个 app 里找输入框点进去。循环三次是因为有的载体要两跳:
# 系统设置首页的搜索条只是个 TextView,点开之后才出现真正的 EditText。
_poke_editor() {
  local B
  for _ in 1 2 3; do
    dumpui
    focused_editor && sh dumpsys input_method | grep -q "mInputShown=true" && return 0
    # 先找有名字的输入框,找不到再退回「页面上第一个 EditText」。
    # 只按 search 之类的关键字匹配会命中整屏的 FrameLayout,点了不弹键盘。
    B=$(grep -iE 'resource-id="[^"]*(url_bar|search_box|location_bar|search_src_text|search_action_bar|search_plate|search_bar_title)"' "$UIDUMP"         | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '
' ' ')
    [ -z "$B" ] && B=$(grep -F 'class="android.widget.EditText"' "$UIDUMP"         | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '
' ' ')
    [ -n "$B" ] && { set -- $B
      sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1
      python -c "import time;time.sleep(2.5)"; }
  done
  dumpui
  focused_editor && sh dumpsys input_method | grep -q "mInputShown=true"
}

raise_ime() {
  if sh pm path com.microsoft.emmx | grep -q package; then
    sh am start --display 0 -n com.microsoft.emmx/com.microsoft.ruby.Main >/dev/null 2>&1
    python -c "import time;time.sleep(5)"
    _poke_editor && return 0
  fi
  sh am start --display 0 -a android.settings.SETTINGS >/dev/null 2>&1
  python -c "import time;time.sleep(4)"
  _poke_editor
}

# 让机主「敲一下」。**必须带上提交那一步**。
#
# 机主用的是讯飞拼音:`input text x` 只是把 x 送进输入法的拼音串,候选栏在动,
# 输入框的文本一个字都没变 —— 真机上为此白测了两轮。空格提交当前候选,
# 没有候选时就落一个空格,两种情况下输入框的内容都会变。
owner_types() {
  sh input -d 0 text "${1:-x}" >/dev/null 2>&1
  sh input -d 0 keyevent 62 >/dev/null 2>&1
}

# 让机主「打拼音但不上屏」—— 中文输入的常态,也是让路判据最容易看漏的状态:
# 输入框内容一个字都不变(讯飞把拼音留在自己窗口里,连 setComposingText 都不调),
# 人却确确实实在输入。少了这条,测试里所有「机主在打字」都是英文式的
# (每敲一下都上屏、指纹每次都变),测不出中文用户真正会遇到的那一种。
owner_composes() {
  for c in $(echo "${1:-nihao}" | grep -o .); do
    sh input -d 0 text "$c" >/dev/null 2>&1
    python -c "import time;time.sleep(0.3)"
  done
}

# 给 app 发广播。必须带 -p。
#
# 无障碍关着的时候(A11yGate 收工后就是这个状态),EyesAndHands 里的动态接收器
# 根本不存在,只有 manifest 里的 CommandReceiver 能收 —— 而 Android O 之后
# 隐式广播到不了 manifest 接收器。少了 -p 的后果不是报错,是任务**静默地不启动**,
# 脚本照样跑完并给出一份全 0 的漂亮读数。实测栽过一次。
# --include-stopped-packages 是给测试脚本用的:用例之间要 force-stop 把 app 的
# 内存状态清干净,而 force-stop 会把包打成 stopped,之后广播就进不去了 ——
# 现象同样是静默失败。真机日常使用碰不到这个状态(app 装完总被点开过一次)。
bc() { sh am broadcast --include-stopped-packages -p ai.whalephone.agent "$@"; }
