# 测试环境

一套让开发者(或 agent 自己)独立跑测试的工具,不依赖人肉观察屏幕。

运行时不需要这些 —— 这是开发夹具。真机上 agent 靠 Shizuku 拿 shell 身份,
不接电脑。

## 前置

    source scripts/lib.sh    # 环境变量 + sh/vdid/foc/ime 辅助函数

`lib.sh` 里处理了 Windows / Git Bash 的两个坑,别绕过它:

- Git Bash 会把 `/sdcard/x` 改写成 `C:/Files/Git/sdcard/x`。需要
  `export MSYS_NO_PATHCONV=1`,设备路径统一写 `//sdcard/x`。
- 但 `$ADB` 必须是 Windows 形式 `C:/...`,否则调 Windows 程序会失败。

## 副屏生命周期

    bash scripts/vd2.sh start [flags]   # 默认 0x5e08
    bash scripts/vd2.sh id
    bash scripts/vd2.sh png [输出路径]   # 抓一帧
    bash scripts/vd2.sh status
    bash scripts/vd2.sh stop

持屏的是 `probe/FlagProbe.java`,用 `app_process` 以 shell 身份跑,
直接调 `DisplayManager.createVirtualDisplay`。标志位可以自己给 —— 这是它
比 scrcpy 强的地方,scrcpy 给不了 `OWN_FOCUS` / `ALWAYS_UNLOCKED`。

**持屏进程必须挂在一个持续存在的 adb 连接上。** `adb shell "... &"` 起的
后台进程会在这次 adb 会话结束时被 SIGHUP 带走,`nohup` 也救不回来。
在 Claude Code 里就用 `run_in_background` 起持屏命令,让它跨工具调用存活:

    adb shell "CLASSPATH=//data/local/tmp/flagprobe.dex \
      app_process /data/local/tmp ai.whalephone.FlagProbe 86400 0x5e08"

重新编译持屏工具:

    export JAVA_HOME=C:/wp/tools/jdk17; export PATH=$JAVA_HOME/bin:$PATH
    AJ=C:/wp/tools/android-sdk/platforms/android-36/android.jar
    javac --release 17 -cp $AJ -d probe/out probe/FlagProbe.java
    C:/wp/tools/android-sdk/build-tools/36.1.0/d8.bat \
      --output probe/ --lib $AJ --min-api 30 probe/out/ai/whalephone/*.class
    adb push probe/classes.dex //data/local/tmp/flagprobe.dex

## 看

    bash scripts/see.sh          # 主屏 -> shots/main_HHMMSS.png
    bash scripts/vd2.sh png      # 副屏 -> .vd.png

主屏走 `adb exec-out screencap`。副屏 **screencap 抓不到**(它只认
SurfaceFlinger 的物理显示器 ID,实测返回 80 字节空图),走持屏进程里的
ImageReader 抓帧写 PNG,毫秒级。

旧的「scrcpy 录 2 秒 + ffmpeg 抽末帧」路径已经不用了,一张要 4 秒。

## 动

无障碍侧的动作由广播驱动,不需要 UI:

    A=ai.whalephone.agent
    sh am broadcast -a $A.DUMP                                   # 所有显示器和窗口
    sh am broadcast -a $A.SNAP  --ei display N                   # 那块屏的元素清单
    sh am broadcast -a $A.CLICK --ei display N --ei index I      # 按序号点
    sh am broadcast -a $A.TEXT  --ei display N --ei index I --es text "'带空格的文字'"
    sh am broadcast -a $A.BRIDGE                                 # 特权桥自检

**`--es` 的值带空格要在设备侧再套一层单引号**(`"'AirPods Pro 2'"`)。
adb 把参数拼成一行命令发给设备 shell,外层引号在本地就没了。

shell 侧的动作:

    sh input -d N keyevent 4                                     # 带显示器维度的返回键
    sh am start --display N --activity-multiple-task \
       "\$(cmd package resolve-activity --brief 包名 | tail -1)"

`am start` 没有 `--activity-new-task` 这个参数,NEW_TASK 是 am 自己加的。

## 看日志

    sh logcat -c                                # 先清
    sh logcat -d -s WPEyes:* WPSvc:* WPAgent:*  # 只看自己的

| tag | 来自 |
|---|---|
| `WPEyes` | 无障碍服务、感知、操作 |
| `WPSvc` | 前台服务和通知 |
| `WPAgent` | 决策循环 |
| `WPPriv` / `WPBridge` | 特权桥两侧 |
| `WPDisplay` | 副屏创建和截图 |
| `WPWatch` | 长时任务调度 |

## 构建

    bash scripts/build.sh build      # 或 install / clean

用项目内自带的 JDK 17 和 Android SDK,不依赖机器上的全局环境。
项目路径含中文,AGP 会拒绝 —— 用 `mklink /J C:\wp <项目路径>` 建目录联接,
对着 `C:/wp` 构建。

## 还原设备

    bash scripts/restore-a11y.sh                 # 还原无障碍设置
    adb uninstall ai.whalephone.agent
    adb shell settings put global stay_on_while_plugged_in 0
    bash scripts/vd2.sh stop
