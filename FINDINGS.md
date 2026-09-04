# 设备侧实测发现

设备:Samsung Galaxy S24 FE (SM-S721B) / Android 16 / One UI 8.5 / SDK 36
补丁:2026-07-05    shell uid=2000(含 input 组), 无 root
工具:adb 37.0.1, scrcpy 4.1

## 结论速查

| 能力 | 结果 | 证据 |
|---|---|---|
| 创建虚拟显示器 | ✅ | scrcpy `New display: 1080x2340/450 (id=N)` |
| 微信/淘宝/美团/京东等启动到虚拟屏 | ✅ 10/10 | 见「App 兼容性」 |
| `input -d <id>` 注入 tap/keyevent | ✅ | BACK 键关掉网易云,退回计算器 |
| `am start --display <id>` | ✅ | 焦点不在该屏时依然生效 |
| 主屏内容不被篡改 | ✅ | 全程知乎保持在 Display 0 |
| **读取非焦点屏的 UI** | ❌ | `uiautomator dump --display` 参数被忽略 |
| **不抢全局焦点** | ❌ | 每次操作 FocusedDisplayId 都跳过去 |
| `screencap -d` 截虚拟屏 | ❌ | 只认 SurfaceFlinger 物理 ID,返回 80 字节空图 |
| 每屏独立焦点开关 | ❌ | `settings list global` 无此键,ROM 编译期常量 |
| 多用户 | ❌ | `Maximum supported switchable users: 1` |

## App 兼容性(全部落在虚拟屏)

com.tencent.mm(微信)、com.taobao.taobao、com.sankuai.meituan、
com.jingdong.app.mall、com.netease.cloudmusic、com.autonavi.minimap、
tv.danmaku.bili、com.whatsapp、com.zhihu.android、三星计算器

老版本 Android 上常见的 `resizeableActivity=false` 被弹回主屏,One UI 8.5 上一个都没出现。

## 焦点模型(实测)

每块屏各自维护 `mCurrentFocus` 和 `mImeInputTarget`,另有一个全局
`FocusedDisplayId` 指向"最后被碰的屏"。

    [T0] 虚拟屏起来      FocusedDisplayId: 13
    [T1] 点主屏          FocusedDisplayId: 0
    [T2] agent 点虚拟屏  FocusedDisplayId: 13
    [T3] 再点主屏        FocusedDisplayId: 0

状态可恢复:用户碰一下主屏,主屏焦点窗口就回来。

### 焦点抢夺不影响用户输入(已实测)

用设置搜索框做输入目标,分阶段注入按键并读回字段内容:

| 时刻 | mInputShown | 用户按键是否落入主屏输入框 |
|---|---|---|
| 基线 | true | AAAA 进了 |
| **虚拟屏创建瞬间** | **false** | 键盘被收掉(一次性) |
| 用户点回输入框 | true | BB 进了 |
| **agent 点虚拟屏** | **true** | **CC 进了** |
| agent 连点 5 次 | true ×5 | 键盘一次都没掉 |

最终字段内容 `AAAABBCC`,一个字符没丢。

结论:`FocusedDisplayId` 是全局指针,每次操作都会被抢走,但**输入投递按 display
路由,不受它影响**。两套机制。全局焦点只影响没有 display 归属的事件(如硬件按键)。

打扰是**一次性的**,只发生在创建虚拟显示器那一刻 → 设计上要挑时机建屏。

`mDisplayIdToShowIme=0` 全程不变:**输入法永远渲染在主屏**。所以 agent 若需打字,
键盘会弹到用户脸上 → 必须设 per-display IME policy 为 local,或走 a11y SET_TEXT。

保留:上述用 `input -d 0 text` 注入,直投 display 0 焦点窗口;真人打字走
IME → InputConnection。终点相同(mImeInputTarget 稳定指向主屏字段),但路径不同,
残留风险未完全清零,待真人 30 秒确认。

## 已知的「抢资源」冲突(题目加分项)

1. **task 搬移**:目标 App 若已在主屏前台,`am start --display` 会把整个 task
   搬到虚拟屏,用户界面凭空消失。实测 t1781 从 Display 0 搬到 Display 7。
2. **焦点抢夺**:每次 agent 操作都把 FocusedDisplayId 拽走。
3. **退出时倾泻**:`--no-vd-destroy-content` 会在虚拟屏关闭时把上面所有 App
   全部倒到主屏。实测 10 个 App 一次性糊到用户脸上。
4. 剪贴板全局共享(未测)
5. IME 若不设 local 策略会渲染到主屏(未测,`cmd window get-ime-display-policy` 不存在,需找正确命令)

## 架构结论

感知被平台堵死 → **on-device AccessibilityService 是必需品,不是可选项**。
只有 `getWindowsOnAllDisplays()`(API 30+)能越过焦点限制读所有屏。

待验证假设:用 `performAction(ACTION_CLICK)` 替代 `input tap`,不合成触摸事件,
可能不抢焦点。若成立,感知+操作全在无障碍服务内,运行时不需要电脑。

## 可观测性(已解决)

| 目标 | 方法 | 状态 |
|---|---|---|
| 看主屏 | `adb exec-out screencap -p` | ✅ 直接出图 |
| 看虚拟屏 | scrcpy 录 2 秒 + ffmpeg 抽末帧 | ✅ 约 4 秒一张 |
| 看虚拟屏 | `screencap -d <逻辑id>` | ❌ 80 字节空图 |
| 看虚拟屏 | `screencap -a` (所有活动显示器) | ❌ 只出主屏一张 |
| 读焦点屏 UI 树 | `uiautomator dump` | ✅ |
| 读非焦点屏 UI 树 | `uiautomator dump --display` | ❌ 参数被忽略 |

工具封装见 `HARNESS.md`。已验证完整闭环:起虚拟屏 → 截图确认计算器 →
注入 `1 + 2 =` → 截图确认显示 `3` → 截主屏确认纹丝不动。

## 环境坑

- Git Bash 会把 `/sdcard/x` 改写成 `C:/Files/Git/sdcard/x`。
  需 `export MSYS_NO_PATHCONV=1`,设备路径写 `//sdcard/x`。
- 但 `ADB` 环境变量必须是 Windows 形式 `C:/...`,否则 scrcpy 起不来。
  两者都在 `scripts/lib.sh` 里处理好了。
