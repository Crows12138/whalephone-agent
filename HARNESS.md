# 测试环境

一套让开发者(或 agent)独立跑测试的工具,不依赖人肉观察。

## 前置

    source scripts/lib.sh    # 环境变量 + sh/vdid/foc/ime 辅助函数

## 虚拟屏生命周期

    bash scripts/vd.sh start [包名]   # 起一块虚拟屏,可选直接启动某 App
    bash scripts/vd.sh id             # 打印当前虚拟屏 id
    bash scripts/vd.sh status         # 显示器列表 + 焦点 + IME 状态
    bash scripts/vd.sh stop           # 关掉

holder 用 `--no-window` 起,不在电脑上弹窗口。它必须持续录制到 `.vd_live.mkv`
才能维持虚拟屏(scrcpy 规则:`--new-display` 需要视频通道),那个文件只是垃圾桶,
0 字节是正常的 —— scrcpy 退出时才 flush。

## 看

    bash scripts/see.sh          # 主屏 -> shots/main_HHMMSS.png
    bash scripts/see.sh <id>     # 指定屏 -> shots/dN_HHMMSS.png

主屏走 `adb exec-out screencap`,直接出图。
虚拟屏 **screencap 抓不到**(它只认 SurfaceFlinger 的物理显示器 ID),
所以走「第二个 scrcpy 按需录 2 秒 → ffmpeg 抽末帧」,约 4 秒一张。

## 动

    bash scripts/act.sh <id> tap X Y
    bash scripts/act.sh <id> swipe X1 Y1 X2 Y2 [ms]
    bash scripts/act.sh <id> text STR         # 仅 ASCII
    bash scripts/act.sh <id> key N | back | home
    bash scripts/act.sh <id> launch 包名

## 读 UI 树

    sh uiautomator dump //sdcard/t.xml
    "$ADB" shell cat //sdcard/t.xml > /tmp/t.xml

注意:`--display` 参数是**假的**,永远只返回全局焦点所在那块屏。
要读非焦点屏必须用 on-device 无障碍服务的 `getWindowsOnAllDisplays()`。

## 已验证的闭环

    vd.sh start 计算器 -> see.sh 18 (看到计算器)
      -> act.sh 18 tap 1/+/2/= -> see.sh 18 (看到 3)
      -> see.sh (主屏纹丝不动)

## 路径规则(踩过三次)

Git Bash 和 Windows 程序对路径的理解不同,凡是两边都要碰的文件:

- **一律用 `C:/...` 正斜杠形式**(`$WROOT` / `$LIVE_MKV` / `$SNAP_MKV`),两边都认
- 传给 adb 的**设备内**路径写 `//sdcard/x`,并设 `MSYS_NO_PATHCONV=1`
- `$ADB` 本身必须是 Windows 形式,否则 scrcpy 起不来
- 反面教材:给 scrcpy `/tmp/a.mkv`,它写到 `C:\tmp\a.mkv`,
  而 bash 的 `/tmp` 是 `AppData\Local\Temp`,两边永远对不上
