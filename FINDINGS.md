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

### 还没验证的部分(写在这里免得被当成已完成)

- `Conflict.ownerTyping()` 靠无障碍窗口列表里的 `TYPE_INPUT_METHOD` 判断机主在打字。
  这个判据在真机上还没量过 —— 无障碍是否稳定上报 IME 窗口、有没有延迟,都要测。
- 让路之后,机主全程键盘不被收起这件事,还没跑过一次真人打字的对照。
  在跑之前,上面那套设计只是「按已定位的根因推出来的解法」,不是「已验证的解法」。
- 还有一类避不掉:副屏上的窗口不是被 agent 的动作触发的(弹窗、广告、视频结束)。
  agent 停手的时候它们照样会抢焦点。目前没有处理办法,先记着。
