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
