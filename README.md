# WhalePhone Agent

在机主正常使用手机的同时,agent 在**同一台设备**上完成任务,全程不碰用户的屏幕、焦点、
键盘和剪贴板。手机锁着但屏幕亮着时(比如刚看完消息还没息屏),agent 一样在干活。

不需要电脑。配置一次之后,手机断开一切外部连接,agent 自己在机器上跑。

## 核心问题

Android 从设计上假设「一人一屏」:输入路由、窗口焦点、输入法目标、导航键,每一层都继承
这个假设。让 agent 和用户并发使用同一台手机,不是加一个后台线程的事,而是要在系统层面
把两者隔离开 —— 而系统提供的隔离能力,大半锁在 `signature|privileged` 权限后面。

## 架构

```
┌──────────────────────── 手机(自足运行)────────────────────────┐
│                                                                 │
│   ┌───────────────┐            ┌───────────────┐                │
│   │  Display 0    │            │  Display N    │                │
│   │  主显示器      │            │  agent 副屏    │                │
│   │  用户在用      │            │  0x5e08        │                │
│   └───────────────┘            └───────────────┘                │
│           ▲                            ▲                        │
│           │  从不触碰                   │  只在这块屏上动作         │
│           │                            │                        │
│   ┌───────┴────────────────────────────┴──────────┐             │
│   │  EyesAndHands(无障碍服务)= 眼睛和手           │             │
│   │  getWindowsOnAllDisplays  跨屏读窗口(API 30+)  │             │
│   │  performAction(CLICK / SET_TEXT / SCROLL)      │             │
│   └───────────────────┬────────────────────────────┘             │
│                       │                                          │
│   ┌───────────────────┴──────────┐  ┌──────────────────────────┐│
│   │  AgentService(前台服务)      │  │ ShellBridge(shell UID)   ││
│   │   Perception → LLM → Hands   │  │  Shizuku 拉起             ││
│   │   通知 = 唯一对用户的出口      │  │  造受信副屏 / 带屏号按键   ││
│   └───────────────────┬──────────┘  └──────────────────────────┘│
└───────────────────────┼──────────────────────────────────────────┘
                        │ HTTPS
                 ┌──────┴───────┐
                 │  LLM(任意    │
                 │  OpenAI 兼容) │
                 └──────────────┘
```

副屏的六个标志位 `0x5e08`,每一位都对应一个实测出来的冲突:

| 标志位 | 解决什么 |
|---|---|
| `TRUSTED` | 不受信的屏只能启自己 uid 的 Activity,淘宝微信都上不去 |
| `OWN_CONTENT_ONLY` | 不镜像主屏,用户屏上不会多出任何东西 |
| `SHOULD_SHOW_SYSTEM_DECORATIONS` | 副屏有自己的启动器和系统装饰 |
| `OWN_FOCUS` | 副屏自己维护焦点,agent 点什么都不把焦点从用户屏拽走 |
| `OWN_DISPLAY_GROUP` | `ALWAYS_UNLOCKED` 的前置条件 |
| `ALWAYS_UNLOCKED` | 锁屏后 agent 继续干活,而不是只能看见 keyguard(息屏不行,见下) |

### 一条硬边界:副屏和主屏共用电源组

实测这台机器 `dumpsys power` 只有 `groupId: 0`。`OWN_DISPLAY_GROUP` 分的是窗口意义上的
显示器组,不是电源组;`DEVICE_DISPLAY_GROUP`(1<<15)也没分出独立电源组。所以**主屏一息屏,
副屏跟着灭,上面的 Activity 被停掉**。想让副屏单独亮着也不行:从副屏的 display context
取 `SCREEN_BRIGHT_WAKE_LOCK`,连主屏一起点亮了。

结论:**agent 的可工作时间等于用户的亮屏时间。** 这个约束和题目是对齐的 —— 要解决的
本来就是「用户正在用手机的时候」,而那时候屏幕必然亮着。副屏蹭的是已经付过电费的那块屏,
手机闲置时 agent 一点电都不耗。长时任务因此改成**亮屏触发**而不是定时轮询。

## 资源竞争:七类冲突和各自的处理

并发用一台手机,冲突不在 CPU 和内存,在那些**全机只有一份**的东西上。

| 冲突 | 现象 | 处理 |
|---|---|---|
| 触摸注入 | `dispatchGesture` 把手势画在真实屏幕上 | 只用 `performAction`,不合成触摸 |
| 输入法 | 整机一个 IME,`mDisplayIdToShowIme` 恒为 0 | 文字走 `ACTION_SET_TEXT`,永不调用输入法 |
| 导航键 | `performGlobalAction` 没有显示器维度,会让用户的 App 后退一页 | 走 `input -d <屏号> keyevent` |
| 全局焦点 | 焦点指针被副屏抢走 | `OWN_FOCUS` 标志位从结构上消除 |
| 剪贴板 | agent 复制覆盖用户正在用的内容 | `ClipboardGuard` 用完立刻还原 |
| task 搬迁 | 启动用户正在前台用的 App,系统把他的 task 搬到副屏 | 启动前先查用户前台包名,冲突就不启 |
| 副屏销毁 | 保留内容会让副屏上的 App 一股脑掉到主屏 | 主动 release,task 随屏消失 |

## 为什么不用别的方案

| 方案 | 否决原因 |
|---|---|
| PC + ADB + scrcpy | 运行时依赖电脑,拔线就死;而题目要的是跑在手机上 |
| 无障碍 `dispatchGesture` | 手势画在真实屏幕上,必然打扰 |
| 纯 app 自己造虚拟屏 | 不受信的屏只能启自己 uid 的 Activity,第三方 App 上不去 |
| `overlay_display_devices` | 开发者选项的模拟副屏会在真实屏幕上画一个窗口,直接盖住用户 |
| 多用户 / 工作资料 | 本机 `Maximum supported switchable users: 1`,且切换用户会切屏 |
| 云手机 | 违背「同一台手机」的前提 |
| root | 绝大多数用户不会为一个 agent 解锁 bootloader |

## 部署

### 一次性配置(手机上完成,不需要电脑)

1. 装 [Shizuku](https://shizuku.rikka.app/),按它的引导用**无线调试**启动
   (Android 11+ 支持,不需要电脑)
2. 装本项目的 APK,打开后点「授权 Shizuku」
3. 点「打开无障碍设置」,启用 WhalePhone
   - Android 13+ 会拦截侧载应用的无障碍权限。系统会提示「出于安全考虑,此设置当前不可用」,
     在应用信息页右上角菜单里选「允许受限设置」
4. 填 LLM 接口的三个配置项,写任务,点「在副屏上开始」

### 开发者:从源码构建

```bash
git clone <repo>
cd whalephone
./gradlew assembleDebug            # 产物在 app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 上如果项目路径含非 ASCII 字符,AGP 会拒绝构建。用 `mklink /J C:\wp <项目路径>`
建一个 ASCII 的目录联接,对着联接构建。

### 配置项

手机上没有环境变量,等价物是 app 内的三个配置项,存在 SharedPreferences 里。
也可以用 adb 灌进去,方便无人值守测试。

| 键 | 说明 | 默认值 |
|---|---|---|
| `LLM_BASE_URL` | OpenAI 兼容接口的 base url | `https://api.deepseek.com/v1` |
| `LLM_API_KEY` | 接口密钥 | 无,必填 |
| `LLM_MODEL` | 模型名 | `deepseek-chat` |

DeepSeek / Kimi / 智谱 / OpenRouter / 自建 vLLM 都是同一套协议,换 base_url 和 model 即可。

## 开发夹具

`scripts/` 下是开发期用的工具,不参与运行时:

| 脚本 | 用途 |
|---|---|
| `lib.sh` | 共享环境变量。Windows / Git Bash 的路径改写坑都在这里处理 |
| `vd2.sh` | 起/停 agent 副屏,拉截图 |
| `see.sh` | 截主屏 |
| `act.sh` | 往指定显示器发点击/输入/按键 |
| `build.sh` | 用项目内自带的 JDK 和 SDK 构建,不依赖机器上的全局环境 |
| `restore-a11y.sh` | 还原被测试改过的无障碍设置 |
| `mock_llm.py` | 假 LLM,见下 |

### 为什么有一个假 LLM

`scripts/mock_llm.py` 实现 OpenAI 兼容的 `/v1/chat/completions`,但不调模型,
按快照里的元素做规则决策。它存在的唯一目的是**把「链路对不对」和「模型聪不聪明」拆开验**:
手机上跑失败时,如果不能确定是感知层没抓到元素、动作层没点中、JSON 契约对不上、
还是模型判断错了,就没法排查。

它走的路径和真模型完全一样 —— app 发 HTTP、收 `choices[0].message.content`、
按同一份 JSON 契约解析、由同一个 `Hands` 执行。唯一不同的是产生动作的是 if-else。

    python scripts/mock_llm.py
    adb reverse tcp:8765 tcp:8765
    adb shell am broadcast -a ai.whalephone.agent.CONFIG       --es key LLM_BASE_URL --es value "http://127.0.0.1:8765/v1"

`probe/FlagProbe.java` 是独立实验:用 `app_process` 逐位测试这台机器允许哪些
虚拟显示器标志位,不经过 app 也不经过 Shizuku,把「设备允许什么」和「代码写得对不对」
分开。它同时也是开发期的持屏工具。

实测记录在 [FINDINGS.md](FINDINGS.md),包括踩过的坑和被推翻的结论。
