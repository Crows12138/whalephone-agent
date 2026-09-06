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

主屏走 `adb exec-out screencap`。副屏也能 screencap,但要传 SurfaceFlinger 的显示器 ID(不是
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
    adb shell settings put system accelerometer_rotation 1   # 测试期间锁了竖屏
    bash scripts/vd2.sh stop

## 踩过的坑

- **别用盲点击驱动悬浮球。** 语音面板等不到回答 6 秒后会自己收起(这是产品行为,
  不是 bug),而截图-读图-再点击这一圈的往返时间就够它收起来了 —— 点空的那一下
  会穿到机主真实的桌面上。我这么点开过机主的微信聊天窗口。要驱动就把
  「点球 → 点面板上的按钮」写在同一条命令里,中间只 sleep,不要回来看图。

**后台跑着的 shell 脚本不能改。** bash 是按字节偏移增量读脚本的:一边跑一边改,
它会从一个陈旧的偏移继续读下去,正好落在多字节汉字中间。现象是脚本跑到一半开始
报一串莫名其妙的 `command not found`,而文件本身 `bash -n` 完全正常,查半天查不出。
要改就先停。

**模拟器跑久了会自己坏,而且坏得像被测代码的错。** 连着跑几十轮任务之后软键盘
叫不起来;`dumpsys input_method` 里堆着几十个历史虚拟屏留下的 `ClientState`。
重启模拟器再跑 `scripts/tests/emu-setup.sh` 即恢复。

**「键盘弹起来了」不等于「注入进得去」。** Edge 地址栏点下去 `mInputShown=true`,
但输入焦点没落在 `url_bar` 上(dump 里 `focused="false"`),之后所有 `input text`
都打了水漂,而脚本以为准备就绪。`raise_ime` 现在要求「有一个获焦的可编辑控件」,
不满足就换下一个载体。

**机主用讯飞拼音,`input text x` 不落字。** 按键只进了输入法的拼音串,候选栏在动,
输入框的文本一个字都没变。要模拟「敲一下」必须带上提交:`owner_types()`
= `input text` + `keyevent 62`(空格)。两轮读数因此白测。

**只用 `owner_types()` 测「机主在打字」,测的全是英文式输入。** 它带空格提交,
每敲一下都上屏、输入框指纹每次都变 —— 而中文输入的常态是拼音打进去、词还没选,
输入框一个字不动。这两种输入在判据眼里是完全不同的两条路径,只测前一条的话,
中文用户真正会踩的那个洞一次都测不到(真机上就是这么漏出去的)。
测后一条用 `owner_composes()`:只发字母,不提交。

**输入法窗口里有什么,不能指望。** 窗口本身是框架给的(`TYPE_INPUT_METHOD`),
在不在可以放心判;但树里有什么完全看输入法自己 —— 讯飞整棵树只有 1 个节点,
三星自带键盘有 95 个。两家都不暴露候选词。想从输入法窗口读「机主正在组词」,
这条路走不通,别再试(IMMS 的 `mCursorCandStart` 和窗口几何同样读不到,都验过了)。

**这台机器上后台线程的 `Thread.sleep(500)` 最长被拖到 49 秒。** 凡是靠墙上时钟
判定的断言都会随机过或不过。断言要卡被测代码自己算出来的量(绝对时间戳相减),
不要卡「这段代码跑了多久」。

**跑几十轮之后 IMMS 里会堆一堆 `ClientState`。** 真机上跑完这一夜是 123 条,
虚拟屏本身没泄漏(只剩 0 和当前那块)。模拟器上堆到几十条时软键盘会叫不起来;
真机这一夜没出现,但**演示前重启一次手机**更稳妥。查看:
`adb shell dumpsys input_method | grep -c ClientState`。

**`:bridge` 孤儿进程会让任务静默不启动 —— 已修,但值得知道它长什么样。**
原来这条记的原因是错的(以为是「反复改 user service 版本号」)。真正的原因常见得多:
**任何一次 `am force-stop`**。桥是 Shizuku 用 shell 身份起的独立进程,force-stop 杀不到它;
app 死过一次之后再来绑,Shizuku 不复用那个孤儿,会再起一个 —— 每 force-stop 一次漏一个
(实测 force-stop + 一个任务,桥从 1 变 2,每轮 +1)。而测试脚本每个用例都在 force-stop。

攒到两三个之后的现象是:广播发出去,`WPSvc`/`WPAgent` 一行日志都没有,脚本照样跑完
给出一份干净的读数。今天在测试里连撞三次才定位到。

现在新桥启动时会自己把同名孤儿收掉(`ShellBridge.reapOrphans`,日志「收掉了 N 个残留的
桥进程」)。看还有几个:`adb shell ps -A | grep :bridge` —— **正常只应该有 1 个**,
多于 1 个说明这条修复没生效,别继续跑测试。
