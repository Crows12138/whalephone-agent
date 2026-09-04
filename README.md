# WhalePhone Agent

机主正常用手机的同时,agent 在**同一台设备**上把任务办完,全程不碰用户的屏幕、
焦点、键盘和剪贴板。配置一次之后不需要电脑,agent 自己在手机上跑。

**思路:给 agent 单独造一块屏。** Android 的输入、焦点、输入法每一层都假设「一人一屏」。
与其在这些全局单例上排队,不如让两边看不见彼此 —— 用 shell 身份造一块**受信虚拟显示器**,
agent 的一切都发生在那上面;用无障碍服务跨屏读写,因为它是唯一带显示器维度的通道。

## 架构

```
┌───────────────────────── 手机(自足运行)─────────────────────────┐
│   ┌──────────────┐                    ┌──────────────┐          │
│   │  Display 0   │                    │  Display N   │          │
│   │  用户在用     │  ◄── 从不触碰       │  agent 副屏   │          │
│   └──────────────┘                    │  flags 0x5e08│          │
│          ▲                            └──────────────┘          │
│          │ 只读(判断用户在用哪个 App)         ▲                  │
│   ┌──────┴────────────────────────────────────┴───────┐         │
│   │  EyesAndHands(无障碍服务)                          │         │
│   │  getWindowsOnAllDisplays 跨屏读 · performAction 写   │         │
│   └──────┬──────────────────────────────────┬─────────┘         │
│          │                                  │                   │
│   ┌──────┴───────────────┐   ┌──────────────┴───────────────┐   │
│   │ AgentService 前台服务 │   │ ShellBridge(shell UID)       │   │
│   │ 感知→模型→动作 循环   │   │ Shizuku 拉起                  │   │
│   │ 通知 = 唯一对用户出口 │   │ 造受信副屏 / 带屏号的按键与启动 │   │
│   └──────┬───────────────┘   └──────────────────────────────┘   │
└──────────┼───────────────────────────────────────────────────────┘
           │ HTTPS
    ┌──────┴──────┐
    │ 任意 OpenAI  │
    │ 兼容的 LLM   │
    └─────────────┘
```

副屏那六个标志位不是凑的,每一位对应一个实测出来的冲突:`TRUSTED`(不受信的屏
启不了第三方 App)、`OWN_CONTENT_ONLY`(不镜像主屏)、`SHOULD_SHOW_SYSTEM_DECORATIONS`、
`OWN_FOCUS`(副屏自己维护焦点)、`OWN_DISPLAY_GROUP`、`ALWAYS_UNLOCKED`(锁屏后照常工作)。

**资源竞争**处理了七类:触摸注入、输入法、导航键、全局焦点、剪贴板、task 归属、
副屏销毁。现象和各自的处理见 [TECH-CHOICES.md](TECH-CHOICES.md)。

**一条实测出来的边界**:副屏和主屏共用电源组,主屏息屏副屏跟着灭。所以 agent 的
可工作时间等于用户的亮屏时间 —— 这和题目场景是对齐的,长时任务因此改成亮屏触发。

## 部署

手机上完成,不需要电脑:

1. 装 [Shizuku](https://shizuku.rikka.app/),按它的引导用**无线调试**启动(Android 11+)
2. 装本项目 APK,打开后点「授权 Shizuku」
3. 点「打开无障碍设置」启用 WhalePhone
   (Android 13+ 会拦侧载应用,需在应用信息页菜单里选「允许受限设置」)
4. 填下面的配置项,写任务,点「在副屏上开始」

从源码构建:

```bash
git clone https://github.com/Crows12138/whalephone-agent && cd whalephone-agent
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 上项目路径含非 ASCII 字符时 AGP 会拒绝构建,用 `mklink /J C:\wp <路径>`
建目录联接,对着联接构建。

## 配置项

手机上没有环境变量,等价物是 app 内的配置项(存 SharedPreferences,也可用 adb 广播灌入)。

| 键 | 说明 | 默认 |
|---|---|---|
| `LLM_BASE_URL` | OpenAI 兼容接口的 base url | `https://api.deepseek.com/v1` |
| `LLM_API_KEY` | 接口密钥 | 无,必填 |
| `LLM_MODEL` | 模型名 | `deepseek-chat` |
| `DEMO_FEED` | 设为 `1` 则任务期间把副屏画面写成 PNG,供录像取景窗使用 | 关 |

DeepSeek / Kimi / 智谱 / OpenRouter / 自建 vLLM 是同一套协议,换 base_url 和 model 即可。

## 其余文档

| 文件 | 内容 |
|---|---|
| [TECH-CHOICES.md](TECH-CHOICES.md) | 技术选型:候选路线的淘汰过程、七类资源竞争、每个决定的依据 |
| [FINDINGS.md](FINDINGS.md) | 真机实测记录,包括踩过的坑和**被推翻的结论** |
| [TASK-DESIGN.md](TASK-DESIGN.md) | 演示任务的选型理由和拍摄脚本 |
| [HARNESS.md](HARNESS.md) | 开发夹具:怎么独立跑测试,不靠肉眼看屏幕 |
