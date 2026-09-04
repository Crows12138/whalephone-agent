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

sh()   { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
vdid() { sh dumpsys display | grep -oE "^  mDisplayId=[0-9]+" | grep -oE "[0-9]+$" | sort -n | tail -1; }
foc()  { sh dumpsys input | grep -oE "FocusedDisplayId: [0-9]+" | grep -oE "[0-9]+"; }
ime()  { sh dumpsys input_method | grep -oE "mInputShown=[a-z]+|mDisplayIdToShowIme=[0-9]+" | tr '\n' ' '; }
