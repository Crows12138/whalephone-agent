# WhalePhone Agent

在机主正常使用手机的同时,agent 在**同一台设备**上完成任务,全程不占用用户的屏幕、焦点与键盘。

## 核心问题

Android 从设计上假设「一人一屏」:输入路由、窗口焦点、输入法目标,每一层都继承这个假设。
让 agent 与用户并发使用同一台手机,需要在系统层面把两者隔离开。

## 架构

```
┌─────────────────────────────┐     ┌──────────────────┐
│          手机                │     │      云端         │
│  ┌────────┐   ┌───────────┐ │     │                  │
│  │ 主显示器│   │ 虚拟显示器 │ │     │   规划 / VLM      │
│  │ Display0│   │ Display N │ │◄───►│                  │
│  │  用户   │   │   agent   │ │     │                  │
│  └────────┘   └───────────┘ │     └──────────────────┘
│         ▲            ▲       │
│         │            │       │
│    ┌────┴────────────┴────┐ │
│    │  无障碍服务(感知+操作) │ │
│    │ getWindowsOnAllDisplays│ │
│    │ performAction(CLICK)   │ │
│    └───────────────────────┘ │
└─────────────────────────────┘
```

手机是眼睛和手,云端是大脑。感知与操作都走无障碍服务,不注入触摸事件。

## 为什么不用别的方案

| 方案 | 否决原因 |
|---|---|
| PC + ADB + scrcpy 镜像主屏 | 占用用户屏幕,且运行时依赖电脑 |
| 无障碍 `dispatchGesture` | 手势画在真实屏幕上,必然打扰 |
| 多用户 / 工作资料 | 本机 `Maximum supported switchable users: 1`,且切换用户会切屏 |
| 应用分身容器 | 渲染目标问题未解决,不比虚拟显示器更优 |
| 云手机 | 违背「同一台手机」的前提 |
| `uiautomator dump --display` | **参数被系统忽略**,只能读全局焦点所在的屏(实测) |

## 部署

见 `HARNESS.md`(开发测试环境)与下方步骤。

### 环境变量

| 变量 | 说明 |
|---|---|
| `LLM_API_KEY` | 云端模型密钥 |
| `LLM_BASE_URL` | 模型服务地址 |

### 步骤

1. 手机开启开发者选项与 USB 调试
2. `adb install -r app-debug.apk`
3. 放行受限设置(Android 13+ 对侧载应用的无障碍限制):
   `adb shell appops set ai.whalephone.probe ACCESS_RESTRICTED_SETTINGS allow`
4. 启用无障碍服务(或在系统设置里手动开启)
5. 启动虚拟显示器

## 实测结论

设备侧的全部实测数据见 [`FINDINGS.md`](FINDINGS.md),包括:

- 虚拟显示器上 10/10 真实 App(微信/淘宝/美团/京东等)可正常启动
- 焦点抢夺**不影响**用户输入(键盘不掉、按键不丢),实测数据在案
- 已识别并量化三类「抢资源」冲突

## 开发

    bash scripts/build.sh build     # 编译
    bash scripts/build.sh install   # 安装
    bash scripts/vd.sh start [pkg]  # 起虚拟屏
    bash scripts/see.sh [id]        # 截图
    bash scripts/act.sh <id> ...    # 操作
