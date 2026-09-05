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
| `screencap -d` 截虚拟屏 | ✅ | 要传 SurfaceFlinger 的显示器 ID,不是逻辑 displayId(这一条最初测错了,见文末) |
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
| 看虚拟屏 | `screencap -d <逻辑id>` | ❌ 报 `Display Id 'N' is not valid` |
| 看虚拟屏 | `screencap -a` (所有活动显示器) | ❌ 只出主屏一张 |
| 看虚拟屏 | `screencap -d <SurfaceFlinger 显示器id>` | ✅ 正常出图 |
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

## 真机 App 覆盖(全程锁屏)

在 `0x5e08` 的副屏上、手机保持锁屏的条件下跑通:

| App | 结果 |
|---|---|
| 三星计算器 | 无障碍按序号点 `7 + 8 =`,ImageReader 截图确认显示 15 |
| 淘宝 | 完整渲染且是登录态;点搜索框 → 填「AirPods Pro 2」→ 提交 → 读出带价格的结果 → 点卡片进详情页 |
| 京东 | 完整渲染,分类导航元素可读 |

主屏全程 `mDreamingLockscreen=true`。

## 这一轮排掉的自造 bug

- **`ACTION_SET_TEXT` 返回 true 但什么都没写。** 淘宝搜索框是自定义控件。
  根因是没给焦点:补上 `ACTION_FOCUS` + `ACTION_CLICK` 之后直写就成了。
  但返回值本身不可信这一点是普遍的,所以保留三级降级和回读校验。
- **压缩嵌套可点元素时方向搞反了。** 商品卡片是「外层容器 + 内层视图」都可点、
  标签一样,原本留外层。实测点外层 `ACTION_CLICK` 返回 true 却什么都不发生,
  点内层才真跳转 —— 有点击回调的是靠近叶子的那个节点。
  这个错误是静默的:返回 true、界面不动,模型只会以为自己选错了元素反复重试。
- **`am start` 没有 `--activity-new-task` 参数**,会抛 IllegalArgumentException。
  NEW_TASK 由 am 自己加。
- **`topResumedActivity` 是全局的**,agent 在副屏活动时它指副屏。用它判断
  「用户在用什么」结论会反过来 —— 而这个判断正是用来避免把用户 task 搬走的。
  改用无障碍读 Display 0 的活动窗口。
- **`adb shell "... &"` 起的后台进程会被 SIGHUP 带走**,`nohup` 也救不回来。
  持屏进程必须挂在一个持续存在的 adb 连接上。

## 推翻前面的一条结论:锁屏 ≠ 息屏

前面写过「agent 可以在手机锁屏揣兜里时继续干活」。**这条是错的**,来源是当时的测试
都在「锁屏但屏幕还亮着」的状态下做的,没把息屏单独试。

补测的结果:

| 主屏状态 | 副屏 State | 副屏上启 App | 无障碍能读到 |
|---|---|---|---|
| 亮屏解锁 | ON | 成功 | 能 |
| 亮屏锁屏 | ON | 成功 | 能 |
| 息屏 | **OFF** | 命令返回成功但界面起不来 | 0 个元素 |

根因是电源组。`dumpsys power` 在这台机器上只有一个 `groupId: 0`:

- `OWN_DISPLAY_GROUP`(1<<11)分的是**窗口意义上**的显示器组,不是电源组
- `DEVICE_DISPLAY_GROUP`(1<<15)创建成功,但 `dumpsys power` 里仍然只有 groupId 0
- 从副屏的 `createDisplayContext()` 取 `SCREEN_BRIGHT_WAKE_LOCK`:锁能取到
  (`held=true`),但**主屏跟着亮了** —— 锁作用在 0 号组上,因为副屏就在 0 号组里

所以在这台设备上没有办法让副屏单独亮着。`ALWAYS_UNLOCKED` 解决的是 keyguard,
不是屏幕电源。

设计上的处理:长时任务从 AlarmManager 定时唤醒改成 **ACTION_SCREEN_ON 触发 + 最小间隔**。
agent 的可工作时间等于用户的亮屏时间,而这恰好就是题目要解决的场景。副屏蹭的是已经
付过电费的那块屏;手机闲置时 agent 一点电都不耗,比定时轮询更省。

代价写清楚:时效性由用户的使用习惯决定。一整夜不碰手机就一轮都不跑。对「盯降价」
这类需求可以接受,对「几点几分必须做完」的需求不适用。

## HOME 键:按键无效,显式 intent 有效

`input -d <屏> keyevent 4`(返回)是按屏走的,实测有效 —— 能让副屏上的淘宝
从商品详情页退回搜索结果页,主屏不受影响。

但 `input -d <屏> keyevent 3`(HOME)**对副屏无效**:发下去之后副屏上的计算器
纹丝不动(`focused=true active=true` 仍是计算器),同时主屏也没被送回桌面。
原因是 HOME 由窗口策略层处理,那一层认的是全局焦点屏,不认事件带的屏号。

有效的写法是显式启动这块屏自己的桌面 Activity:

```
am start --display <屏> -a android.intent.action.MAIN -c android.intent.category.HOME
```

发完副屏的焦点窗口变成 `com.sec.android.app.launcher / One UI 主屏幕`,
主屏保持 `mDreamingLockscreen=true` 不变。

这条对长时任务有用:下一轮开始时副屏往往还停在上一轮的界面,模型需要一个
「重来」的手段,只靠连按返回遇到不响应返回的页面就卡死。

## 焦点:OWN_FOCUS 在这台机器上没用,但问题另有解法

前面一直挂着「OWN_FOCUS 未在主屏有真实前台 App 的条件下验证」。补上了,
条件是主屏停在设置的搜索页、输入框已聚焦、输入法已弹出(`mInputShown=true`),
两组对照只差 `OWN_FOCUS`(1<<14)这一位。脚本:`scripts/tests/test-ownfocus.sh`。

| 动作 | 0x5e08(带 OWN_FOCUS) | 0x1e08(不带) |
|---|---|---|
| 起点 | 焦点 0 · 输入法 on | 焦点 0 · 输入法 on |
| 造副屏 | 焦点 → 副屏 · **输入法 off** | 焦点 → 副屏 · **输入法 off** |
| `input -d 0 keyevent 0` | 焦点 → 0 · 输入法 on | 焦点 → 0 · 输入法 on |
| 往副屏启 App | 焦点 → 副屏 · **输入法 off** | 焦点 → 副屏 · **输入法 off** |
| `input -d 0 keyevent 0` | 焦点 → 0 · 输入法 on | 焦点 → 0 · 输入法 on |
| 无障碍连点三下 | 焦点 → 副屏 · 输入法 **on** | 焦点 → 副屏 · 输入法 **on** |

**两组一模一样。`OWN_FOCUS` 在 One UI 8.5 / Android 16 这台机器上没有可观测的作用** ——
系统接受这个标志位、创建成功,但行为和不带完全一致。代码里保留它(无害,别的 ROM 上
可能有用),但文档里不再把它当成焦点问题的解法。

### 真正的结论

**打断只有两处,都不是 agent 的动作:造副屏、往副屏启 App。** 这两下会收起用户
正在用的输入法。解法是紧跟一个带显示器维度的空按键:

```
input -d 0 keyevent 0        # KEYCODE_UNKNOWN
```

界面上不产生任何效果,但它带着显示器归属进了 InputDispatcher,焦点指针回到 0 号屏,
输入法跟着回来。已编进 `AgentDisplay.create()` 和 `Hands.launch()`。

**agent 的无障碍动作不需要处理。** 它们确实会移动焦点指针,但输入投递是按屏走的。
用带屏号的方式模拟真实按键(手指按在物理屏上产生的事件天然带 0 号屏归属):

```
起点               框=「AAA」
agent 连点 5 下     焦点→副屏,但用户输入法保持 on
用户打字 BBB       框=「AAABBB」,焦点自己弹回 0
agent 再点 3 下 + 用户打字 CC   框=「AAABBBCC」
```

一个字都没丢。**焦点指针移动 ≠ 用户被打扰** —— 那个指针只对没有显示器归属的事件
起作用,真人的手指从来不产生这种事件。

(反过来说,用 `input text` **不带** `-d` 去测会得出错误结论:那种事件确实没有显示器
归属,会落到指针指的那块屏。它模拟的不是用户,是一个没有来源的幽灵按键。)

### 由此改掉的设计

副屏改成**跨任务复用**。原来每个任务造一次屏、结束销毁,意味着每个任务都让用户的
输入法抖一下。现在造一次留着,只在用户点「停止」时销毁 —— 一整个会话只抖一次,
而且那一次也被空按键补偿掉了。

---

# 接上真模型:端到端跑通(2026-09-05 中午)

## 生产路径全通,不经过电脑

Shizuku 授权后,**app 自己造出了副屏**:

```
Shizuku 在运行=true 已授权=true
桥连上=true
whoami: uid=2000(shell) ... context=u:r:shell:s0
agent 屏 id=87 1080x2340@450  标志位=0x5e08  期望标志位=0x5e08 全拿到
无障碍能否看到这块屏: 0(2窗口), 87(2窗口)
```

模型用 DeepSeek(`deepseek-chat`),OpenAI 兼容接口。

## 核心验收:用户打字的同时 agent 把任务做完

`scripts/tests/test-concurrent.sh`。主屏停在设置搜索页、输入框聚焦、输入法弹出;
整个任务期间每 2 秒注入一个字符,共 26 个;跑完比对。

```
第 1 步 launch    副屏当前没有打开任何应用，需要先启动淘宝
第 2 步 click     点击搜索栏以进入搜索界面
第 3 步 set_text  当前搜索框已聚焦，直接输入关键词
第 4 步 click     当前界面是搜索联想词列表，需要点击搜索按钮执行搜索
第 5 步 scroll    搜索结果没直接显示价格，向下滚动查找第一个商品
第 6 步 done      淘宝搜索「保温杯」第一个商品是钛光织影施美乐埃及纯钛保温杯，799.00元

用户输入框 = 「abcdefghijklmnopqrstuvwxyz」  26/26 全部落在自己的框里
用户前台   = com.android.settings.intelligence(没跳走)
```

京东同样跑通:9 步,ROG魔导士Ace HFX ¥1249。

模型做过一次比规则脚本更好的判断:搜「AirPods Pro 2」时第一条结果其实是
PRO **3** 代的广告(¥818),它识别出来并报了第一个真正的二代(¥1059)。

## 关于「丢字」的对照实验

有一轮测出用户少了 1 个字符,15 变 14。这是核心主张,不能靠再跑一次通过就翻篇,
所以做了对照:同样的打字压力,一次跑 agent,一次不跑。

| | 期望 | 实到 | 内容 |
|---|---|---|---|
| 不跑 agent | 30 | 28 | `Abcdefghijklmnopqrstuvwxyz23` |
| 跑 agent | 30 | 28 | `Abcdefghijklmnopqrstuvwxyz23` |

**两边完全一致,连丢的是哪两个字符都一样。** 说明丢字来自注入工具本身,
和 agent 无关。换成纯字母重跑,两边都是 26/26。

(注:我一度以为是 `input` 对数字参数的解析问题,单独试 `input -d 0 text 0`
却是好的,所以机制没查清。但这不影响结论 —— 对照组已经把 agent 排除了。
用 `input` 注入本来就不是真实用户:真人的手指不经过这个命令。)

## 这一轮排掉的问题

- **`am start` 是异步的。** 命令返回成功只表示 Intent 递出去了。淘宝冷启动十几秒,
  而循环 600ms 后就拍下一帧,模型看到空屏以为没启成功,再启一次,三次被判卡死。
  改成同步轮询到目标 App 出现,超时 25 秒。
- **binder 线程没有 Looper。** `ActivityThread.systemMain()` 内部 `new Handler()`
  绑的是当前线程的 Looper,而 AIDL 调用落在 binder 线程上。只判断主 Looper 存不存在
  不够 —— Shizuku 的 user service 主线程本来就有,守卫会跳过,然后在 binder 线程炸掉。
- **`--es value ""` 传不进空串**,清配置只能靠「不带 value」表达。之前因此没退出
  开发模式,agent 对着一块早就不存在的屏干活。
- **模型不知道 `set_text` 自带聚焦**,在京东上连点 23 步搜索框想「让它变成输入状态」,
  一次都没试过直接写。提示词里点明之后降到 9 步。
- **「空屏」对模型是歧义信号**(没加载完 / 压根没开 App),不说清楚它会反复按 home
  想退回去,而这块屏根本没有可退的地方。快照渲染时分开讲。
- **状态栏进了快照**:时间、信号格、电量百分比每帧都变,既费 token 又干扰判断,
  模型说过「顶部混入了系统栏」而多走一步。按窗口类型过滤掉 TYPE_SYSTEM。
- **`targetSdk` 35+ 强制 edge-to-edge**,app 界面顶部被系统栏盖住,盖住的正好是
  那块「还差哪一步」的状态面板。自己吃 insets 补上。

## ACTION_CLICK 返回 true ≠ 点动了

在淘宝商品详情页,任务连着三次跑挂在同一个地方:模型点底栏的「店铺」按钮,
点三下,界面纹丝不动,被卡死检测判死。

一开始我以为是模型的问题,先加了草稿纸(`note`)、又加了步数预算和「拿不到就
带着部分结果收尾」的指令。步数从 24 降到 10,**但还是挂在同一个元素上**。
后来把「界面没有变化」这个事实回写进模型看得到的历史里,它看见了、也照样再点 ——
到这里才说明不是模型的问题。

真因:`AccessibilityNodeInfo.performAction(ACTION_CLICK)` 走的是 `View.performClick()`,
只触发 `OnClickListener`。App 自己用 `onTouchListener` 处理触摸时,这个节点
`isClickable` 为 true、`performAction` 也返回 true,但**什么都不会发生**。
淘宝详情页底栏就是这种控件。

这是整套设计里最危险的一类失败:**静默**。返回值是成功的,日志是干净的,
只有界面知道什么都没发生。

### 解法:补一次带屏号的真实触摸

实测 `input -d <显示器> tap x y` 能打进虚拟显示器,而且只打进那一块 ——
在副屏的计算器上注入 `tap 250 1750` 精确按下了「4」,用户那块屏没有任何反应。

于是 `click` 改成两级降级,和 `setText` 一样不信返回值、只信界面动没动:

1. `ACTION_CLICK` —— 标准控件都吃这套,且不产生任何真实触摸事件
2. 界面没动 → `input -d <屏> tap 中心点` —— 只认触摸的自定义控件靠这级

判据用无障碍事件:`onAccessibilityEvent` 里按 displayId 记下最近一次
`WINDOW_CONTENT_CHANGED / WINDOW_STATE_CHANGED / VIEW_SCROLLED` 的时刻,
点完 500ms 看有没有新事件。比重新拍一次快照便宜得多。

两级都没反应就如实告诉模型「这个元素点不动,换一个」—— 静默失败变成显式失败。

结果:同一个任务同一个元素,`第 7 步 click -> 已点击 [68] 店铺,按钮(无障碍点击无效,
补了一次真实触摸)`,8 步完成,答案经截图核对无误(¥799 / simelo旗舰店)。

### 附带的教训

前两轮改的是提示词,方向错了。**模型反复做同一件无效的事,先怀疑那件事是不是
真的无效,而不是先怀疑模型**。判断依据很明确:把失败信号显式喂给它、它看见了
还照做,就不是模型的问题。


## 模型的字符串拼进了 sh -c

回答「DeepSeek 输出的是不是操控手机的 shell」时顺手查的,结果查出个洞。

模型的输出确实只有 JSON、只有封闭词表 —— 但有两处把它给的字符串拼进了
`ProcessBuilder("sh","-c",cmd)` 的命令行:

```kotlin
val esc = text.replace("%","%%").replace(" ","%s")   // 只转义了这两个
Privileged.exec("input -d $displayId text $esc")
Privileged.exec("am start ... \$(cmd package resolve-activity --brief $pkg | tail -1)")
```

`;` `&&` 反引号 `$()` 全部能过去,而这个 shell 是 uid 2000。

真正的威胁面不是模型,是**无障碍树里的文字是攻击者可控的** —— 商品标题、网页
内容、通知文本都进模型上下文。构造一段文案诱导模型把 payload 当搜索词填进去,
就是 prompt injection 提权到 shell。

解法见 TECH-CHOICES 第八节:加 `execArgs(List<String>)` 走 argv 直接 execve,
凡含模型字符串的命令一律走它,`launch` 里的 `$(...)` 拆成两步。回归检查进了特权桥自检:

```
注入面: 干净 —— 参数没有被 sh 解析
```

改完跑同一个任务:9 步完成,launch / set_text / click+触摸降级 全部照常。

教训:**「模型只能输出结构化动作」不等于安全**,得看那些动作的参数最后流到哪儿。


## 推翻一条自己的结论:screencap 其实能截虚拟屏

之前记的是「`screencap -d` 只认 SurfaceFlinger 物理 ID,对虚拟屏返回 80 字节空图」,
这条错了,而且被我写进了三处源码注释和三份文档。

真相:`screencap -d` 要的确实是 SurfaceFlinger 的显示器 ID(传逻辑 displayId 会报
`Display Id 'N' is not valid`,连主屏的 0 也一样报),**而虚拟显示器在 SurfaceFlinger
里是有 ID 的**:

```
$ dumpsys SurfaceFlinger --display-id
Display 4633128672291736003 (HWC display 0): displayName="samsung lcd"
Display 11529215048113231701 (Virtual display): displayName="whalephone-agent"

$ screencap -d 11529215048113231701 -p /sdcard/sc.png   # 150483 字节,内容是副屏
```

错在哪:当初那个实验脚本里写的是

```bash
PHYS=$(dumpsys SurfaceFlinger --display-id | grep -oE "Display [0-9]+" | ... | head -1)
```

`head -1` 取的是**物理 LCD**那一条,所以「方案2:screencap -d 物理ID」从头到尾
测的都是主屏,压根没试过虚拟屏那个 ID。结果被我记成了「虚拟屏抓不到」。

**这个错误的类型和之前几次一样**:实验设计里有个隐含假设(「物理 ID」只有一个),
假设不成立,但失败现象看起来完全符合预期,于是就当结论收下了。
`head -1` 这种取值方式在探索阶段特别危险 —— 它会安静地替你做一个你没意识到的选择。

**对设计的影响**:选无障碍树而不是截图,原来列的第一条理由(截图路径窄)不成立,
已删。剩下两条(token 成本差一个数量级、按序号落点不用猜坐标)仍然成立,
而且 AndroidWorld 的 T3A/M3A 对比是外部旁证。`AgentDisplay` 继续用 ImageReader,
但理由改成了实事求是的那个:自己持有 Surface,不走 shell、不用每次造屏都去解析一次
SurfaceFlinger 的 ID,取景窗要连续出帧这条更直接 —— 而不是「别无选择」。

## 「不打扰机主」这条,我之前测的是错的指标

机主用真手指试了一次,报告:任务能跑完,但**输入法一直被打断**,而且 WhalePhone
自己**一直显示「没有响应」**。而我的并发测试一直是绿的。

### 为什么原来的测试测不出来

原来数的是「字有没有丢」:`input -d 0 text a` 一秒一个,跑完比对字符串,26/26 通过。
问题是**键盘被收起来之后,下一个 `input text` 照样能写进那个还聚焦着的输入框**,
所以字一个不丢。丢的是机主的连续性 —— 手指悬在半空、键盘没了 —— 而那个指标看不见。

换成直接读 `dumpsys input_method` 的 `mInputShown`(真实 IME 的真实状态),
一跑就是 6 次 true→false。

### 逐个操作归因(主屏 Edge 地址栏聚焦,副屏计算器)

| 操作 | 全局焦点屏 | 机主的软键盘 |
|---|---|---|
| 无障碍 `performAction` 点击 | 0 → 0 | 不受影响 |
| 无障碍 `ACTION_FOCUS + SET_TEXT` | 0 → 0 | 不受影响 |
| `input -d N tap` | 0 → N | 不受影响 |
| `input -d N keyevent` | 0 → N | 不受影响 |
| `input -d N swipe` | 0 → N | 不受影响 |
| **`am start --display N`** | 0 → N | **被收起** |
| 副屏上的**页内跳转** | 0 → N | 不受影响 |

**我怀疑了一整轮的 tap / swipe / enter 全是无辜的。** 唯一会收键盘的是往副屏启 App。

### ANR 和输入法消失是同一条链

```
am start --display N / 注入  →  全局焦点移到副屏
                             →  机主前台那个 app 没有聚焦窗口
                             →  他一碰屏幕,输入派发超时
                             →  Input dispatching timed out (Application does not
                                have a focused window)  →  ANR 对话框  →  键盘随之消失
```

`am_anr` 日志坐实了这条。**触发条件是机主的手指碰屏幕** —— 所以合成注入的测试
永远测不出来:我的测试从不"碰屏"。

而且第一次量出的 6 次里有几次是我自己造成的:那次我拿 WhalePhone 自己的输入框
当载体,而它正因为上面这条在 ANR。**用一个会被待测现象弄坏的东西去测那个现象。**

### 为什么不能事后补救

`input -d 0 keyevent 0` 能把焦点指针推回主屏,但**不会重新弹出已经收起的软键盘**。
所以"抢了再还"这条路本身不成立,只能从源头不碰。

### 根子上的限制

副屏申请到了 `FLAG_OWN_FOCUS` 和 `FLAG_OWN_DISPLAY_GROUP`,但实际拿到的是:

```
DisplayInfo{"whalephone-agent", displayId 132, displayGroupId 0, ... FLAG_OWN_FOCUS}
```

**`displayGroupId 0` —— 和主屏同一个组。** 传进去的 `DEVICE_DISPLAY_GROUP`(1<<15)
被系统静默丢弃。焦点是整组共享的,所以副屏上一出现新窗口,机主的键盘就没了。
这和之前那条「电源组也只有 0」是同一类:**这些 flag 申请得到,但在这台机器上不生效**。

### 改了什么,还剩什么

- 每次真实注入之后立刻还焦点,收进 `inject()`,调用方想忘也忘不掉
- `launch` 的冷启动轮询期间每轮都还一次焦点 —— 那是最长的敞口(最多 25 秒),
  也正是机主碰屏最容易撞上 ANR 的时候
- 每步收尾兜底还一次焦点

实测从 6 次降到 2 次,任务结束时 `mInputShown=true`、焦点回到主屏(之前是 false / 留在副屏)。
**但没有归零**,而且期间仍有 1 次 ANR。剩下的是 `am start` 那一次固有的收键盘,
以及一次尚未定位的。这条没做完,不写成做完。


## 「还焦点」这个机制本身就是错的

上一节把 `handBackFocus`(`input -d 0 keyevent 0`)当成解药,还为它加了冷启动期间
每 250ms 钉一次的线程。机主随后报告:输入法不收了,但 **Edge 一直显示「没有响应」**。

查 `am_anr`:

```
am_anr: com.microsoft.emmx, Input dispatching timed out
        (Application does not have a focused window)
```

**是我自己造成的。** `handBackFocus` 靠的是往主屏注入一个空按键 —— 而那**也是一个
输入事件**。焦点在副屏时,主屏那个 app 没有聚焦窗口,这个事件送不进去,派发超时
5 秒就是 ANR。我为了"不打扰"每 250ms 灌一次,等于持续制造 ANR。

对照实验(主屏 Edge 聚焦,同一个淘宝任务):

| | ANR | 输入法被收起 | 结束时 |
|---|---|---|---|
| 还焦点 | 1 | 1 | 键盘还在 |
| **不还焦点** | **0** | 2 | 键盘已收起 |

ANR 是弹在机主脸上的模态对话框,比键盘被收起严重得多,所以默认改成不还。
何况「还」本来也救不回键盘 —— `keyevent 0` 只推得回焦点指针,推不回已经收起的
软键盘。**这个机制从根上就不解决它要解决的问题,还制造了一个更严重的问题。**

`cmd window` / `wm` 里没有设置焦点显示器的命令,所以「不注入输入就改焦点」这条路
在 shell 层不存在。

**后来修正了一半:这条否掉的是「进行中反复灌」,不是「收尾推一次」。**
删掉整个机制之后留下一个洞:副屏按设计在任务结束后保留(跨任务复用),而副屏上有
可获焦窗口,它一直占着全局焦点 —— 于是任务跑完,机主那块屏 `mCurrentFocus=null`
长期存在。真机上直接读到过这个状态:任务结束、机主的 Keep 还好好在屏上、看不出
任何异常,但主屏没有获焦窗口,他下一次按键就落在
「Application does not have a focused window」上。

补回来的版本和被否的那版差三处,缺一不可:只在收尾做(进行中副屏本来就该持有焦点,
agent 才操作得了)、只做一次(循环是上一版 ANR 的直接来源)、只在主屏确实没有获焦
窗口时做(没坏就不修)。真机实测:

```
主屏原本没有获焦窗口,推回一次 -> Window{...com.gotokeep.keep...VpSummaryActivity}
```

两轮任务、机主在场、新增 ANR 0。

这一条**模拟器上验不了**:纯 AOSP 的副屏在任务结束后不留可获焦窗口,主屏焦点根本
没丢,`test-focus-return.sh` 在模拟器上会全绿但什么都没验到(日志是「主屏本来就有
焦点,不动」)。丢焦点是三星特有的 —— DeX 往受信副屏上常驻一个桌面/任务栏。
这是这个项目里「模拟器和真机各能验什么」最干净的一个例子。

### 现在的实际状况

- ANR:**0**(复现确认)
- 输入法:每个任务被收起约 2 次,任务结束时是收起状态
- 无障碍动作(点击、写文本、滚动)对机主**完全无影响**,焦点都不动

一次来自 `am start`(实测唯一会收键盘的操作),另一次尚未定位。

根子还是那条:副屏拿不到独立的 display group(`displayGroupId 0`),
窗口焦点是整组共享的。这台机器上没有绕过去的办法 —— 除非不往副屏启新 App,
而那等于不干活。**这条没解决,写在这里。**

## 推翻上面那条:根子不是 display group,是**焦点**

上一节结尾我写的是「根子还是副屏拿不到独立的 display group」。那是错的,而且错得
有代表性:我把一个**相关**的读数当成了原因,因为它看上去正好能解释现象。

### 先按那条思路走到了尽头

Android 14 起独立的显示器组由**虚拟设备**提供,而且 `VirtualDevice` 还带着
`setDisplayImePolicy(displayId, policy)` —— 看起来正好对症。这条路走通了:

- shell(uid 2000)持有 `CREATE_VIRTUAL_DEVICE`(`granted=true, GRANTED_BY_ROLE`)
- 缺的是 CDM 关联,`cmd companiondevice associate 0 com.android.shell <mac>
  android.app.role.COMPANION_DEVICE_APP_STREAMING true` 能自己造一个
- 走 `VirtualDeviceManager` 包装类会报 `No association with ID N` —— 它取的是底层
  ContextImpl 的 AttributionSource,包名是 `android` 而不是 `com.android.shell`。
  直接调 `IVirtualDeviceManager` 的 AIDL、自己显式传 AttributionSource 就成了
  (两个 listener 不能传 null,用动态代理给个空实现)
- `createVirtualDisplay` 的 flags 传 0 时 `setDisplayImePolicy` 报
  `Attempted to set IME policy to an untrusted virtual display`;补上 `TRUSTED` 就成功了

结果:虚拟设备建成,`FLAG_OWN_DISPLAY_GROUP` 这次**真的挂在** DisplayDeviceInfo 上了
(老路 `DisplayManager.createVirtualDisplay` 传这个位会被静默丢掉),
`setDisplayImePolicy(7, HIDE)` 也接受了。**但 `displayGroupId` 仍然是 0。**

### 然后 AOSP 源码说这条路本来就走不通

`InputMethodManagerService` 每个用户只维持**一份** IME 绑定:

- `DISPLAY_IME_POLICY_HIDE` 的分支里直接调 `hideCurrentInputLocked(...)` ——
  拿它当实验组等于自己给自己下毒,它不是「别在副屏显示输入法」,是「把当前输入法收起来」
- 虚拟设备自带输入法(`VirtualDeviceParams.Builder.setInputMethodComponent`,这台
  ROM 上有)走的是 `setInputMethodLocked(deviceMethodId, ...)`,那是**换绑**,
  机主那个 IME 一样要被解绑

所以 IME 层不存在「两块屏各自一个键盘」这回事。**问题根本不在 IME 层。**

### 决定性的一次测量:密闭试验台

`scripts/tests/test-focus-rig.sh`。用两块虚拟屏当替身,全程不碰机主的主屏
(那天机主在通话,`dumpsys power` 里有 `PROXIMITY_SCREEN_OFF_WAKE_LOCK
'wechat:screen multi-talk'`,屏幕是贴脸灭的 —— 顺带一提,那会儿截图全黑、
`mInputShown=false`,和 agent 一点关系都没有;不先看一眼手机在干什么,
这两个读数能骗人很久)。

只做一件事:建一块副屏,什么都不启动,然后读主屏自己的焦点窗口。

| | 主屏焦点窗口 | 顶层焦点屏 |
|---|---|---|
| 基线 | `ginlemon.flower.HomeScreen` | 0 |
| 建了一块副屏之后 | **null** | 9 |
| 释放之后 | `ginlemon.flower.HomeScreen` | 0 |

三星会自动往「受信 + 带系统装饰」的虚拟屏上放一个 DeX 桌面,那就是一个可获焦窗口。
**副屏上只要有一个可获焦窗口,主屏当场丢掉自己的焦点窗口。**

### 机制

`DisplayContent.findFocusedWindowIfNeeded(topFocusedDisplayId)`:

```java
return (hasOwnFocus() || topFocusedDisplayId == INVALID_DISPLAY)
        ? findFocusedWindow() : null;
```

`RootWindowContainer.updateFocusedWindowLocked` 从子节点列表的末端往前遍历显示器,
`topFocusedDisplayId` 边遍历边定;新建的显示器排在主屏前面,先被遍历到,于是它先
把 `topFocusedDisplayId` 占下。轮到主屏时 `topFocusedDisplayId` 已经不是 INVALID 了,
而主屏 `hasOwnFocus()` 是 false —— `OWN_FOCUS` 是**造屏时的 flag**,主屏是内置屏,
加不上。于是主屏返回 null。

主屏没有焦点窗口,直接导致两件事:

1. IMMS 收起机主的软键盘
2. 投递到主屏的按键事件没有收件人,5 秒后超时 →
   `Input dispatching timed out (Application does not have a focused window)`

**「应用未响应」和「输入法被收起」是同一个原因的两个后果。** 之前我把它们当成两件事
分别在修,所以两边都只修到表象。

唯一的全局开关是 `config_perDisplayFocusEnabled`(它会让每块屏都保留自己的焦点),
那是编译进 framework 的资源,没有运行时入口,`wm` / `cmd window` 里也没有。

### 由此改掉的设计

事后补救整个删掉,换成事前避让:

- 删掉 `Privileged.handBackFocus` 和冷启动期间那条每 250 毫秒钉一次焦点的线程。
  那条线程等于每 250 毫秒给机主的前台 App 递一颗 ANR 定时炸弹
- 新增 `Conflict.ownerTyping()`:判据是**机主那块屏上有没有 `TYPE_INPUT_METHOD`
  窗口**,走无障碍的窗口列表,自带显示器维度,不需要 shell 往返
- `Agent.execute()` 里每个动作前过一道 `yieldWhileOwnerTypes()`。不按动作分类,
  因为「会不会引发副屏窗口切换」不是按动作分的:点一下可能跳页,写文本可能弹搜索
  建议,启动一定会
- 让了多久会写进那一步的结果里,模型和机主都看得见

代价说清楚:机主打字期间 agent 停工。这是主动选的 —— 题目要求里「不打扰」是硬的,
吞吐不是。等待有 60 秒上限,防止机主开着键盘走开导致任务永远做不完。

### 在模拟器上验掉的部分(纯 AOSP Android 16,API 36,和真机同 API)

真机不在手边,先在模拟器上把属于 AOSP 的那半边验完。三条,一条一条来。

**一、根因链在第二个平台上独立复现。** 只建一块虚拟屏,什么都不启动:

| | 主屏焦点窗口 | 机主的键盘 |
|---|---|---|
| 建屏前 | `...intelligence.modules.search.SearchActivity` | true |
| 建屏后 | **null** | **false** |
| 往副屏启 App 后 | null | false |
| 释放副屏后 | `...SearchActivity` | **true** |

和三星那次一致。这条链是 AOSP 行为,不是厂商魔改。

顺带修正一条我之前说死了的话:「键盘推不回来」只对 `keyevent 0` 那个办法成立。
这里把副屏整个释放掉之后,机主的键盘**自己回来了** —— 抢焦点的窗口一消失,
IMMS 就把 IME 恢复了。任务进行中做不到这一点(副屏上得有 App),但话不能说绝对。

**二、`ownerTyping()` 靠的那个判据读得对。** 这是整个修复里唯一没量过的假设:

```
键盘没弹:  无障碍: false   (主屏窗口类型: 3 1)
键盘弹着:  无障碍: true    (主屏窗口类型: 3 2 1)      ← 2 = TYPE_INPUT_METHOD
```

**三、闸门本身按设计工作。** 键盘弹着时发 `YIELD` 广播:

```
---- YIELD ---- 开始等(机主在打字的话这里会卡住)     ← 6 秒后仍然只有这一行
(手动收起键盘)
机主在打字,让了 6642 毫秒
---- YIELD ---- 让了 6642 毫秒后放行
```

等待的这 6.6 秒里机主的键盘一直是 true —— agent 停手,就什么也没发生。
对照组是上面第一条:同样键盘弹着,建一块屏,键盘当场 false。这就是完整的 A/B。

顺带修掉一个真实的报告 bug:特权桥没连时 IMMS 那一路返回 `NO_BRIDGE`,
原来的代码拿它去和无障碍比对,报出一个假的「两路不一致」—— 比没有这条检查更糟。

### 完整一轮真任务:机主打字 60 秒,agent 同时干活

前面三条只验了机制的零件。这一条把它们串起来跑:agent 先开始做任务,第 5 秒机主
开始在设置的搜索框里打字并保持 60 秒,期间 agent 做了 6 步、其中 3 次 `launch`
(`am start --display N`,唯一实测会收键盘的操作)。

```
ANR 次数            0
打字期间键盘被收起  0
agent 主动让路次数  1
```

**为什么这一轮才算数**:先顶键盘再开任务只能测到「造屏」那一处;真实场景是 agent
已经在干活了,机主这时才拿起手机。测试脚本因此改成任务跑起来之后再架键盘,
并且只统计那段窗口内的掉落 —— 之后是脚本自己收的键盘,不算 agent 头上。

这一轮还第一次看到了整套设计依赖的那个共存状态:

```
机主打字时:  全局焦点屏=0
  Display 0 : 输入法窗口 + Settings(focused=true active=true)
  Display 14: DeskClock (focused=true active=false)
```

两块屏**同时**各自持有焦点窗口,机主的键盘留着,agent 照样读得到自己那块屏。
`OWN_FOCUS` 在原生 Android 16 上是有效的 —— 这也推翻了之前「本机实测无效」那句话
的普适性:那句话只对那台三星成立,原因还没查。

### 跑这一轮才发现的两个真 bug

**一、造副屏那一步没过闸门。** 我在 AgentDisplay 的注释里写了「避让在 AgentService
的调用点做」,但那个调用点根本没加。后果是动作全让了,开场第一秒还是把机主打断:

| | 打字期间键盘被收起 |
|---|---|
| 修之前 | 1(第 1 秒) |
| 修之后 | 0 |

注释和代码对不上,只有真跑一遍才看得出来。

**二、这套东西在原生 Android 上根本跑不起来,而我一直不知道。** 模拟器上无障碍
**看不见**副屏,agent 是瞎的。逐位试出来是 `FLAG_PRIVATE`:

| flags | 无障碍看到几块屏 |
|---|---|
| 0x5e08 / 0x1e08 / 0x5608 / 0x0e08 | 1(看不见副屏) |
| 0x5e09 / 0x0e09(加了 `PUBLIC`) | 2(看得见) |

对应 AOSP `AccessibilityManagerService.isValidDisplay`:虚拟屏带 `FLAG_PRIVATE`
就不跟踪。三星放宽了这一条,所以在机主那台手机上从没暴露过 —— 换台原生机器整套
东西就是瞎的,而且**不报错**,只是「什么都读不到」。

改法不是写死加 `PUBLIC`,是造完屏当场问一句无障碍看不看得见,看不见才补 `PUBLIC`
重造。判据是能力本身,不是我对某个 ROM 的假设。

### 还没查清的一处

有一轮任务里,agent 在第 3 步用真实触摸兜底点击之后,第 4 步的快照突然报「副屏
没有打开任何 App」,而 `am start` 说 App 明明在,任务因此判卡死。单独复现时
(机主打字 + 副屏有 App)无障碍读得到副屏,所以不是系统性的失明。原因没查清,记在这里。

顺带量到:`input -d N tap` 打在副屏上之后,机主那块屏的所有窗口变成 `focused=false`
—— 主屏丢了焦点窗口,但键盘**没**被收(没有新窗口向 IMMS 上报焦点)。这是 ANR 的
前置条件;不过闸门保证这一步只在机主没打字时发生,那时也就没有按键事件,实测 ANR 仍是 0。

### 模拟器验不了、必须在真机上补的部分

- **三星 One UI 8 的差异**。DeX 会自动往「受信 + 带系统装饰」的虚拟屏上放一个桌面,
  那本身就是个抢焦点的窗口 —— 也就是说在三星上,副屏一建起来就一直占着顶层焦点,
  而模拟器上的副屏是空的。这会影响「机主的键盘能不能靠他自己点一下恢复」。
- ~~**Shizuku 那条特权路径**在模拟器上是 `NO_BRIDGE`~~ —— 这条后来推翻了:
  模拟器上装了 Shizuku,用 `<apkdir>/lib/x86_64/libshizuku.so` 直接执行就能起服务
  (它是 ELF 不是脚本,`sh` 它会报一堆乱码),桥连得上。差别只剩身份:
  模拟器上 shizuku_server 跑在 root 下,真机上是 shell(uid 2000)。
- **第三方输入法**。机主用的是讯飞,模拟器上是 AOSP 键盘。无障碍上报 IME 窗口
  这件事讯飞会不会不一样,没测过。
- **完整的一轮真任务**,机主真手指打字,量 ANR 次数和键盘被收起次数。
  `scripts/tests/verify-fix.sh` 一条命令跑完,手机回来就能跑。

### 一类目前无解的

副屏上的窗口不是被 agent 的动作触发的(弹窗、广告、视频结束),agent 停手的时候
它们照样会抢焦点。闸门管不到,先记着。

## 权限跟着任务走(A11yGate):六个用例和它们各自防的那件蠢事

机主要的是「跑的时候自动开无障碍,不跑自动关」。动机是真的:一个能
`performAction(ACTION_CLICK)` 的无障碍服务在微信收银台眼里等于木马,开着就付不了款,
而 agent 一天里真正干活只有几分钟 —— 为几分钟让机主全天付不了钱不划算。

但这个便利的风险和收益不对称:它改的是**机主全局的系统设置**,写坏了不报错,
只是悄悄把别人的东西关掉。`enabled_accessibility_services` 是一张冒号分隔的表,
读屏、按键映射都在里面,整表覆盖等于断依赖读屏的人的手脚。所以每条不变量单独量。
`scripts/tests/test-a11y-gate.sh`,模拟器上跑,19 条断言。

| 用例 | 防的是 | 结果 |
|---|---|---|
| 1 没开权限时自己开,跑完自己关 | 功能本身 | 过 |
| 2 拿 TalkBack 当对照,跑完它还在 | 整表覆盖把别人连坐 | 过 |
| 3 机主自己开着的不许关 | 分不清是谁开的 | 过 |
| 4 进程被杀,下一轮把残留清掉 | 状态只活在内存里 | 过 |
| 5 长时任务的一轮结束不关权限 | 自己把自己的触发器拆了 | 过 |
| 5b 长时任务撞上机主打字、提前收尾也不关 | 同上,但走的是另一条路径 | 过 |
| 6 机主设了别自动就真的别动 | 开关本身要能关掉 | 过 |

跑这套测试挖出来的四件事,三件是代码/工具链的真问题:

**`am broadcast` 少了 `-p`,任务静默不启动。** 无障碍关着的时候 `EyesAndHands` 里的
动态接收器根本不存在,只有 manifest 里的 `CommandReceiver` 能收,而 Android O 之后
隐式广播到不了 manifest 接收器。后果不是报错,是脚本照常跑完、给出一份全 0 的
漂亮读数 —— 真机上那一轮 `打字期间键盘被收起 0 / 让路 0 次` 就是这么来的,
任务压根没启动。A11yGate 上线之后这个坑才暴露出来:以前无障碍一直开着,
动态接收器一直在,`-p` 加不加都一样。现在统一走 `lib.sh` 里的 `bc()`。

**`finish()` 不打日志,所有提前收尾的理由在 logcat 里无痕。** 只有正常跑完那条
路径打了「结束 done=」。机主一直在打字、副屏没造成、没有眼睛 —— 这三条提前退出
都悄无声息,出问题时看到的只是「agent 没反应」。在 `finish()` 里统一记一次收工原因。

**`am force-stop` 模拟不了「进程被系统杀掉」。** AccessibilityManagerService 收到
包被强停的通知会主动把这个包的无障碍服务从 enabled 表里剔掉,于是用例 4 一开始
测出「杀完权限自己没了」,看着像通过,其实是系统替我们收拾了,MARK 那条路根本没走到。
改成直接 `kill` 进程才测得到。顺带一个好消息:机主自己去设置里强停 app,
权限会自动清干净,不用靠 MARK。

**用例 5 一开始「过得不对」。** 权限确实留住了,但 `AgentService` 在 Watch 分支里
直接 `return`,压根没调 `close()` —— 结论对,理由不对,`A11yGate` 里那道
「还有长时任务在盯着」的闸门一次都没被走到。补了 5b 才真正打到它:盯降价的某一轮
亮屏触发时机主正在打字,agent 按设计不开工、走 `finish()` 提前收尾,这时如果关掉
无障碍,亮屏触发器就跟着没了,后面所有轮次一起哑掉且不报错。5b 不只看权限还在,
还要求闸门自己那行日志在,否则算归因失败。

**真机上抓到一个模拟器测不出的 bug:组件名有两种写法。** 在三星上验「shell 身份
(uid 2000)能不能改这条 secure setting」时,顺手往表里塞了一项不存在的组件,读回来
是这样的:

```
写入:  ai.whalephone.agent/.EyesAndHands : ai.whalephone.probe/.Nope
读回:  ai.whalephone.agent/ai.whalephone.agent.EyesAndHands
```

写是写进去了(所以那条路径成立)。是 AccessibilityManagerService 立刻重写了这张表:
剔掉解析不出组件的项,并把短名 `pkg/.Cls` 展开成全名 `pkg/pkg.Cls`。

`A11yGate` 原来拿全名字符串做相等比较来找自己那一项。表里存的可能是短名 ——
机主从设置里开的、脚本写的、AMS 归一化前的,三者未必同一种写法。按字符串比就会
「明明有却认不出」:开的时候重复追加一遍,关的时候摘不掉,权限留在那儿而且不报错。
改成按 `ComponentName` 比较,两种写法自动等价。

模拟器上测不出来是因为那边表里的值一直是 A11yGate 自己写的全名,而测试里判断
「表里有没有我们」用的是 `grep whalephone` 这种粗匹配 —— 重复追加和摘不掉都能蒙混
过去。补了两条断言:表里只能有一项是我们(防重复追加),短名写法的残留也要摘干净。

**模拟器跑久了会自己坏掉,而且坏得像被测代码的错。** 连着跑几十轮任务之后软键盘
叫不起来了,`raise_ime` 连续失败;查 `dumpsys input_method`,IMMS 里堆着几十个我们
app 的 `ClientState`,`mSelfReportedDisplayId` 是 7 / 17 / 30 / 67…—— 全是历史虚拟屏
留下的。重启模拟器后 3/3 全成。这类污染在真机上不明显(副屏跨任务复用,一天造不了
几块),但在模拟器上会伪装成功能故障。`scripts/tests/emu-setup.sh` 把重启后的准备
步骤固化下来了。

**顺带在模拟器上看到的:** `无障碍看不见副屏 2(flags=0x5e08),补 PUBLIC 重造` →
`无障碍看得见副屏 3,保持私有屏`。纯 AOSP 上 `FLAG_PRIVATE` 失明的兜底确实触发,
三星上从不触发。这条只有模拟器能验。

## 判据说的和想说的差一点点:机主被自己下达任务的动作挡住

机主问了一句「机主在打字就不造副屏,这个判定有问题吧」。查下来有问题,而且问题
正好落在主演示路径上。

`Conflict.ownerTyping()` 想表达的是「机主正在往**他自己的**东西里打字,现在抢焦点
会让他丢掉推不回来的输入状态」。写出来的是「主屏上存在 IME 窗口」。两者在一种情况
下分道扬镳:

机主在 WhalePhone 自己的输入框里打完任务 → 点「开始」(`MainActivity` 没有收键盘,
点按钮也不会自动收)→ 键盘还开着 → `ownerTyping()` 判 true → agent 等满 180 秒 →
`finish("机主一直在打字,这一轮先不开工")`。**他刚下的任务被他下达任务的动作挡住了**,
而且不报错,看起来就是「点了没反应」。

修法是让判据去问「聚焦的窗口是谁的」:输入法服务的是本 app 自己的输入框时,那个
前提不成立 —— 那是我们自己的窗口,而且机主刚按下开始,他的意图就是让 agent 开工。
判不出来时按「是机主的」算,守着比放过安全。两条路径(无障碍、IMMS 兜底)都要加,
少一条就只是换个代码路径重现同一个 bug。

### 顺带发现:探针测的是一份副本,不是产品

改完之后 `TYPING` 探针仍然报「机主在打字」,而任务照常开工了 —— 两者矛盾。
原因是 `typingCheck()` 把判断逻辑**重新实现了一遍**(直接查 IME 窗口存不存在),
没有调用真正上线的 `Conflict.ownerTyping()`。于是新加的那一层它完全看不见。

这类探针比没有更危险:它给出的是一个看着很像结论的东西,而那个东西和产品行为无关。
改成调用真函数,原始信号(无障碍 / IMMS)降级成排查用的辅助信息。

### 三条发起路径,受不受这道闸门约束是不一样的

| 路径 | 时机 | 机主在干什么 | 该不该拦 |
|---|---|---|---|
| `MainActivity` 按钮 | 他刚点完按钮 | 正看着我们自己的界面 | 不该 —— 他就是要 agent 开工 |
| `Watch` 亮屏触发 | 他刚点亮屏幕 | 很可能正要用手机 | 该 —— 这是闸门真正的用武之地 |
| 外部广播 | 不确定 | 不确定 | 该 —— 按保守处理 |

「等满 3 分钟然后整轮放弃」这套重机制,本来看着和「机主自己点的按钮」很不搭 ——
排掉路径一之后它就只服务于路径二了,而对一个亮屏触发的长时任务来说,这一轮不跑、
下次亮屏再来,是合理的。

## 先量再改:任务全程 89% 的时间,机主那块屏没有获焦窗口

「每次抢完焦点就还回去」这个念头,形状正是当年 A/B 测出会造成 ANR 的那个模式
(还回去、再抢走、反复)。所以先量,别凭推理改。`scripts/tests/measure-focus-gap.sh`
在任务期间采样机主那块屏的 `mCurrentFocus`,采样期间**绝不碰主屏**——任何一次
`input -d 0` 都会把焦点带回来,读数就废了。

基线:19 秒的任务,17 秒主屏没有获焦窗口,**89%**。也就是说机主在这段时间里碰一下
自己的屏幕就可能 ANR。风险窗口是整个任务,不是边角情况 —— 那就值得改。

关键是找一个不重蹈覆辙的形状。当年那个循环的致命处不在「还焦点」本身,在于它挑了
`am start` 冷启动期反复灌:App 正在副屏上建窗口,焦点来回抢,注入落不了地就是
派发超时。而 agent 的时间线里绝大部分是**在等模型返回**,那几秒它什么都不做,
不存在竞争。所以改成:每一轮去问模型之前还一次,只还一次,而且只在主屏确实没有
获焦窗口时还。

| | 主屏失焦占比 | ANR |
|---|---|---|
| 改前(基线) | 89% | 0 |
| 改后 · 这一轮需要冷启动 App | 50%(全部集中在 `am start` 等待那 8 秒) | 0 |
| 改后 · App 已在副屏上 | 0% | 0 |

剩下那 50% 全在 `am start` 之后等 App 起来的轮询里 —— 那正是当年出事的同一个窗口,
不碰。留了 `FOCUS_RETURN=0` 的口子,万一在别的机器上量到 ANR,不改代码就能退回去。

**真机复量(机主正在微信里看文章,任务跑在副屏上):89 秒的任务,131 次采样里
10 次主屏无获焦窗口,占比 8%,新增 ANR 0。** 比模拟器还好 —— 三星上 App 冷启动快,
`am start` 那个未覆盖的窗口就短。机主的微信全程在前台。
(采样中途他的微信从文章页回到了会话列表。`keyevent 0` 是空按键不会导航,
判断是他自己退的,但没法证明,记在这里。)

### 顺带纠正自己一句

之前说过「任务结束后主屏失焦这条模拟器上验不了」。那是根据单次观察下的,太满了。
这一轮测量里模拟器上它也触发了。可靠的说法是:**进行中**失焦模拟器稳定复现,
**结束后**的残留时有时无;三星上两者都稳定出现。

## 两个问题的真机终值

`scripts/tests/test-ime.sh`,三星 SM-S721B / One UI 8 / Android 16,任务是演示用的
「打开淘宝,搜索保温杯,报前两个商品的价格和店铺」,机主打字窗口 45 秒。

```
期间 ANR 次数        0
打字期间键盘被收起  0
打字结束后被收起    0
agent 主动让路      1 次
```

让路那一次的归因(不看这个就不知道读数是「解决了」还是「没测到」):

```
WPConflict: 机主在打字,让了 23131 毫秒
WPAgent   : -> 机主刚才在打字,agent 让了 23 秒没动手。这段时间界面可能已经变了,
             上面这一帧是最新的,重新看一眼再决定
```

闸门触发了、agent 停手了、让完之后没有照着旧决策动手,而是退回去重新拍帧。

ANR 那一侧不止这一个数。同一台机器上另外量到的:任务全程机主那块屏没有获焦窗口的
占比,从 89% 降到 8%(`measure-focus-gap.sh`,机主当时正在微信里看文章),
以及任务收尾后主屏 `mCurrentFocus` 不再残留 null。三处加起来才是「应用未响应」
这条链被断掉,单看哪一个都不够。

### 同一轮里暴露的一个会毁掉演示的 bug

第 3 步还在淘宝搜索框里 `set_text` 成功,第 4 步快照就变成「副屏当前没有打开任何 App」,
接着连着四次重启淘宝,最后判卡死。这就是之前记在「还没查清的一处」里的那个间歇现象 ——
现在它在完整任务里稳定出现了一次,有日志可查。这条和「不打扰机主」无关,是任务
能不能做完的问题,单独查。

## 「副屏跨任务复用」这条设计,在真机上一次都没生效过

连跑两个任务,日志每次都是:

```
WPSvc: 副屏 20 已经不存在了(特权桥重启过?),重造一块
WPSvc: 副屏 21 就绪
```

先排掉了两个显然的猜测:app 进程没死(pid 全程 21012 不变),桥进程也在。
而且任务**结束后**去看,虚拟屏还好好地在(`dumpsys display` 里有
`uniqueId="virtual:com.android.shell,2000,whalephone-agent,18"`)。屏是在**下一个任务
开始时**被判死的。

判定这么写的:

```kotlin
getSystemService(DisplayManager::class.java)?.getDisplay(old.displayId) == null
```

这是 **app 进程(u0_a344)**的 DisplayManager,去查一块**属主是 shell、带 FLAG_PRIVATE**
的虚拟屏。私有虚拟屏只有属主看得见 —— 所以这个查询**永远**返回 null。
不是「屏没了」,是「你没资格看见它」。两者读数一模一样,后果差很远。

代价是:造副屏是全流程里唯一必须避让机主的动作(它必然抢一次焦点、收一次键盘),
按设计它一辈子只该发生一次,实际上每个任务都发生一次。整套让路机制护着的那个动作,
被这个 bug 放大了 N 倍。而它不报错,日志里那句「特权桥重启过?」还给了一个听起来
合理的错误解释,把人往别处引。

修法是问屏的属主:AIDL 加 `displayAlive(int)`,由桥自己回答(它手上握着 VirtualDisplay,
而且它就是属主,看得见)。改 AIDL 要同时升 Shizuku user service 的版本号,
否则会复用旧进程,那里面没有新方法。

验证:连跑两个任务,第二个直接复用副屏 25,系统里始终只有 1 块虚拟屏。
