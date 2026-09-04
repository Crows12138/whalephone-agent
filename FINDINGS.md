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

---

# 第二轮:自己造屏(2026-09-05 凌晨)

前一轮用 scrcpy 当持屏者,能力被 scrcpy 给的那套标志位限死。这一轮直接调
`DisplayManager.createVirtualDisplay`,标志位自己给,结果推翻了两个原以为无解的约束。

## shell 身份能拿到的虚拟显示器标志位(逐位实测)

在 Galaxy S24 FE / Android 16 / One UI 8.5 上,以 uid 2000 逐位叠加测试:

| 标志位 | 值 | 结果 | 对 agent 的意义 |
|---|---|---|---|
| `OWN_CONTENT_ONLY` | 1<<3 | 通过 | 不镜像主屏,用户屏上不会多出东西 |
| `TRUSTED` | 1<<10 | 通过 | **前提条件**,不受信的屏只能启自己 uid 的 Activity |
| `SHOULD_SHOW_SYSTEM_DECORATIONS` | 1<<9 | 通过 | 副屏有自己的启动器和系统装饰 |
| `OWN_FOCUS` | 1<<14 | 通过 | 副屏自己维护焦点(Android 14+) |
| `OWN_DISPLAY_GROUP` | 1<<11 | 通过 | `ALWAYS_UNLOCKED` 的前置条件 |
| `ALWAYS_UNLOCKED` | 1<<12 | 通过 | **锁屏状态下副屏照常工作** |

组合值 `0x5e08` 全部被接受。这台机器上 shell 身份拿到了全部六位。

## 关键前提:包名必须和 uid 对上

`ActivityThread.systemMain().getSystemContext()` 拿到的 Context 包名是 `android`
(uid 1000),而进程是 shell(uid 2000)。DisplayManagerService 会拿包名反查 uid
校验,直接抛:

```
SecurityException: packageName must match the owner uid
```

解法是套一层 ContextWrapper 把 `getPackageName()` / `getOpPackageName()` 改成
`com.android.shell`,并且 **DisplayManager 必须用这个 Context 反射现造** ——
`getSystemService()` 返回的是底层 ContextImpl 建好的缓存实例,包装层改的包名它看不见。
scrcpy 走的也是这条路(它的 `FakeContext`)。

## 锁屏下的完整闭环(已跑通)

手机处于锁屏状态(`mDreamingLockscreen=true`),全程没有解锁:

1. `app_process` 造 `0x5e08` 的副屏,拿到 id
2. `am start --display <id>` 启计算器 —— 成功
3. 无障碍 `getWindowsOnAllDisplays()` 读到 `Display 76: 2 个窗口`,26 个元素带全部标签
4. `performAction(ACTION_CLICK)` 按序号点 `7` `+` `8` `=`
5. ImageReader 抓帧存 PNG,拉回电脑:显示 **15**
6. 主屏全程 `mDreamingLockscreen=true`,没有被唤醒

这条推翻了前一轮的结论「锁屏时无障碍只能看见 keyguard」——那是对**主屏**成立,
带 `ALWAYS_UNLOCKED` 的副屏不受 keyguard 管辖。

意味着 agent 可以在用户把手机揣兜里锁屏时继续干活,这比「用户在用手机时不打扰」
更进一步:大部分时间手机是锁着的,那才是 agent 真正的工作时间。

## 截图:换掉 scrcpy + ffmpeg

虚拟显示器必须有 Surface 承接画面。之前拿 scrcpy 录屏当水槽再抽帧,一张图约 4 秒。
换成 `ImageReader` 之后,截图是 `acquireLatestImage()` + `Bitmap.compress()`,
毫秒级,不落视频文件,也不需要 scrcpy 进程。

| 目标 | 旧方法 | 新方法 |
|---|---|---|
| 看虚拟屏 | scrcpy 录 2 秒 + ffmpeg 抽末帧,约 4 秒 | ImageReader 抓帧写 PNG,毫秒级 |

## 两个耽误时间的自造 bug

- **打补丁把 `System.exit(0)` 挪进了 `grab()`**:持屏进程第一次截图就自杀,
  显示器随之消失。表现出来是 `am start --display` 报
  `Permission Denial ... with launchDisplayId=N` —— 看着像权限问题,
  实际是 `getDisplayContentOrCreate()` 对一个已经不存在的显示器返回 null。
  排查时一度以为是某个标志位不被允许,做了五组标志位对照实验才发现全都失败,
  说明变量不在标志位上。**报「权限拒绝」的时候先确认对象还活着。**
- **`adb shell "... &"` 起的后台进程会在工具调用结束时被 SIGHUP 带走**,
  `nohup` 也救不回来(adb 的会话管理层就断了)。持屏进程必须挂在一个
  持续存在的 adb 连接上。

## 还没验的

`OWN_FOCUS` 到底管不管用。锁屏状态下 `FocusedDisplayId` 会跟着副屏跑
(76),但此时主屏只有 keyguard,keyguard 不争焦点,这个测试不算数。
需要主屏上有真实前台 App 时再测一次。
