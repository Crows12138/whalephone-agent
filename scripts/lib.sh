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

# 把机主的真实输入法叫出来,返回 0 = 叫起来了。
#
# 为什么用 uiautomator dump 而不是本 app 的 SNAP/CLICK 广播:那两个广播要求
# 无障碍服务已经在跑,而无障碍恰恰是被测对象之一。用被测对象搭测试脚手架,
# 它一坏,测出来的是「准备失败」而不是「功能坏了」,两者分不开。
# uiautomator 走的是另一条独立通路,不吃这个依赖。
#
# 载体优先用 Edge,没有就用系统设置的搜索框(模拟器上一定有)。
# 不用本 app 自己的输入框:焦点被挪走时前台 app 会「Input dispatching timed out」
# 而 ANR,ANR 对话框自己也会带走键盘 —— 拿会被待测现象弄坏的东西当载体,
# 量出来的掉落分不清是谁造成的。
raise_ime() {
  local B
  if sh pm path com.microsoft.emmx | grep -q package; then
    sh dumpsys activity activities | grep -m1 topResumedActivity | grep -q emmx || {
      sh am start --display 0 -n com.microsoft.emmx/com.microsoft.ruby.Main >/dev/null 2>&1
      python -c "import time;time.sleep(5)"; }
  else
    sh am start --display 0 -a android.settings.SETTINGS >/dev/null 2>&1
    python -c "import time;time.sleep(4)"
  fi
  # 循环三次是因为有的载体要两跳:系统设置首页的搜索条只是个 TextView,
  # 点开之后才出现真正的 EditText,键盘也是那时候才弹。
  for _ in 1 2 3; do
    sh uiautomator dump /sdcard/wp.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/wp.xml 2>/dev/null | tr '<' '
' > "$UIDUMP"
    # 先找有名字的输入框,找不到再退回「页面上第一个 EditText」。
    # 只按 search 之类的关键字匹配会命中整屏的 FrameLayout,点了不弹键盘。
    B=$(grep -iE 'resource-id="[^"]*(url_bar|search_box|location_bar|search_src_text|search_action_bar|search_bar_title)"' "$UIDUMP" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '
' ' ')
    [ -z "$B" ] && B=$(grep -F 'class="android.widget.EditText"' "$UIDUMP" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1 | grep -oE '[0-9]+' | tr '
' ' ')
    [ -n "$B" ] && { set -- $B
      sh input -d 0 tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )) >/dev/null 2>&1
      python -c "import time;time.sleep(2.5)"; }
    sh dumpsys input_method | grep -q "mInputShown=true" && return 0
  done
  return 1
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
