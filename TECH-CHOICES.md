# 技术选型说明

## 一、问题的实质

「agent 和机主同时用同一台手机」听起来像是个调度问题,其实不是。Android 的整条输入
和显示链路都建立在「一人一屏」的假设上:

- 触摸事件注入到全局的 `InputDispatcher`,不区分谁在操作
- 窗口焦点是全局单例(`FocusedDisplayId`),输入法只在一块屏上显示(`mDisplayIdToShowIme`)
- 无障碍服务的全局动作(返回/主页)没有显示器维度
- 剪贴板全机一份

所以真正要解决的是:**把 agent 的操作和用户的操作,在这些全局单例上分开**。
不是让它们排队,是让它们互相看不见。

## 二、候选路线和淘汰过程

### 淘汰:PC + ADB 驱动

最省事,几十行 Python 就能跑。但运行时依赖电脑,拔线就死,且用户的手机屏幕会被
agent 的操作直接占用。题目要的是「跑在真机上的 agent」,这条从定义上就不成立。

保留它作为**开发期夹具** —— 本项目的全部实测都是通过 ADB 做的,但运行时不需要。

### 淘汰:无障碍 `dispatchGesture`

`AccessibilityService.dispatchGesture()` 能合成触摸手势。问题是它把手势画在**真实屏幕**上:
用户会看到光标一样的东西在自己的界面上点来点去,焦点也会跟着跑。这是最直接的打扰。

对比之下 `performAction(ACTION_CLICK)` 是直接调节点的点击回调,不经过触摸层。

### 淘汰:多用户 / 工作资料

Android 支持多用户,理论上可以让 agent 在另一个用户下跑。实测本机:

```
Maximum supported switchable users: 1
```

而且切换用户会整块切屏,用户的界面直接消失。方向不对。

### 淘汰:云手机 / 应用分身

云手机违背「同一台手机」的前提。应用分身(如各厂商的双开)解决的是同一个 App 多账号,
渲染目标还是那块屏,不解决并发问题。

### 淘汰:开发者选项的模拟副屏

`settings put global overlay_display_devices "1080x2340/420"` 能造一块副屏,不需要任何
特殊权限。但它是 **overlay** —— 会在真实屏幕上画一个悬浮窗口,直接盖在用户界面上。
名字里的 overlay 就是字面意思。

### 淘汰:纯 app 自己造虚拟显示器

普通 app 可以调 `DisplayManager.createVirtualDisplay()`,不需要任何权限。但造出来的屏
不是 trusted 的,而 `ActivityStarter` 对非受信显示器的规则是:**只能启动创建者自己 uid
的 Activity**。淘宝、微信、美团都上不去。

这条边界决定了必须借到更高的身份。

### 选中:受信虚拟显示器 + 无障碍服务 + Shizuku

三个部件各自不可替代:

| 部件 | 不可替代的原因 |
|---|---|
| 受信虚拟显示器 | 唯一能承载第三方 App 且不占用真实屏幕的渲染目标 |
| 无障碍服务 | `getWindowsOnAllDisplays()` 是唯一能读**非焦点显示器**内容的 API |
| Shizuku | 唯一能在手机上、不 root、不接电脑地拿到 shell UID 的方案 |

第二条是实测确认的硬约束:`uiautomator dump --display <id>` 的参数被系统忽略,
adb 侧只能读全局焦点所在的那块屏 —— 而「用户正在用手机」恰恰意味着焦点在主屏。
所以 on-device 的无障碍服务是**必需品,不是可选项**。

## 三、为什么是 Shizuku 而不是 root

造受信虚拟显示器需要 `ADD_TRUSTED_DISPLAY`,带显示器维度地注入按键需要 `INJECT_EVENTS`,
两个都是 `signature` 级,普通 app 拿不到。能拿到的身份有三种:

| 身份 | 获取方式 | 代价 |
|---|---|---|
| root | 解锁 bootloader,刷 Magisk | 绝大多数用户不会做,且保修和银行类 App 会出问题 |
| 电脑 ADB | USB 或网络连电脑 | 运行时离不开电脑 |
| Shizuku | 手机自带的「无线调试」,Android 11+ | 每次重启要重新激活一次 |

Shizuku 的代价最小:它用 Android 11 引入的无线调试功能,在手机上完成 ADB 配对,
拉起一个 shell UID 的常驻进程,再通过 binder 把这个身份借给普通 app。整个过程不接电脑。
代价是设备重启后要重新激活一次(这是 Android 的设计,不是 Shizuku 的缺陷)。

## 四、副屏标志位的选择

`DisplayManager.createVirtualDisplay()` 的 flags 参数里有一批 `@hide` 的位。逐位实测
(`probe/FlagProbe.java`),在 Galaxy S24 FE / Android 16 上 shell 身份全部可用:

```
0x5e08 = TRUSTED | OWN_CONTENT_ONLY | SHOULD_SHOW_SYSTEM_DECORATIONS
       | OWN_FOCUS | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED
```

其中两位是这个方案和常见做法(比如 scrcpy 的 `--new-display`)拉开差距的地方:

**`OWN_FOCUS`(Android 14+)** —— 文档上说副屏自己维护焦点。**实测在这台机器上没有
可观测的作用**:带与不带两组对照,焦点和输入法的表现完全一致(数据见 FINDINGS.md)。
代码里保留它,因为无害且别的 ROM 上可能有效,但不把它当作焦点问题的解法。

焦点问题的实际解法在别处,见下面第七节的「全局焦点」一行。

**`ALWAYS_UNLOCKED`(Android 14+)** —— 副屏不受 keyguard 管辖。实测在**锁屏状态下**
完成了:启动淘宝 → 点搜索框 → 输入「AirPods Pro 2」→ 提交 → 读出带价格的结果列表,
全程主屏保持 `mDreamingLockscreen=true`,没有被唤醒。没有这一位,无障碍在锁屏时
只能看见 keyguard。

**但它只解决锁屏,不解决息屏。** 副屏和主屏共用同一个电源组(这台机器 `dumpsys power`
只有 `groupId: 0`),主屏一灭副屏跟着灭,上面的 Activity 被停掉。`OWN_DISPLAY_GROUP`
分的是窗口组不是电源组,`DEVICE_DISPLAY_GROUP` 也没分出来;从副屏的 display context
取 `SCREEN_BRIGHT_WAKE_LOCK` 会把主屏一起点亮,更不能用。

所以 agent 的可工作时间等于用户的亮屏时间。这不算坏消息:题目要解决的本来就是
「用户正在用手机的时候」,那时候屏幕必然亮着;而手机闲置时 agent 一点电都不耗。
长时任务据此改成亮屏触发而不是定时轮询。

对不支持这些位的老设备,`Privileged.createAgentDisplayBestEffort()` 按重要性逐位降级,
并报出最终生效的组合,而不是整个失败。

## 五、感知:无障碍树而不是截图

多模态模型直接看截图是目前 GUI agent 的主流做法。这个项目选了无障碍树,三个理由按重要性:

1. **它是唯一原生带显示器维度的读取通道**。`uiautomator dump` 只读全局焦点那块屏,
   而焦点在用户手里 —— 实测主屏放设置、副屏放计算器,dump 出来的是**设置**,
   副屏上的计算器一个节点都没有。所有建立在 uiautomator 上的框架,指到副屏这件事
   在感知层就不成立。无障碍的 `getWindowsOnAllDisplays()` 是唯一的替代。
2. **成本差一个数量级**。一张 1080×2340 的截图按 detail=high 编码大约 1500~2000 token,
   而压缩后的元素清单(淘宝首页 50 行)大约 1200 token,还带 clickable / editable 语义。
   长时任务要跑几十上百步,这个差距是决定性的。
3. **动作能精确落点**。树里有 bounds 和可交互标记,模型按序号选元素,不用猜坐标。
   坐标猜错在副屏上不会被用户看见,但会静默走错一步。

截图能力保留(`AgentDisplay.capture()` 走 ImageReader),留给 WebView / Canvas / 游戏
这类树里读不出内容的场景,以及给人看的演示。

### 压缩

原始无障碍树里一个图标按钮常常是三个节点:可点的容器、不可点的包装、真正带文字的
TextView。三行说同一件事,模型看到三个一样的序号只会犯错。

做法是以可交互节点为锚,把它子树里的文字收上来当标签,再事后对消两类残留:
被可交互元素在位置和文字上都盖住的纯文本,以及标签完全相同的嵌套可点元素。
实测淘宝首页从一百多个节点压到 50 行,商品卡片连价格和销量一起成为一行。

## 六、输入:三级降级 + 回读校验

`ACTION_SET_TEXT` **会返回 true 但什么都没写**。淘宝的搜索框就是这样:它是自定义控件,
只是没拒绝这个动作而已。所以不能信返回值,必须回读。

三级降级,每级都校验:

1. `ACTION_FOCUS` + `ACTION_CLICK` + `ACTION_SET_TEXT` —— 标准控件在给了焦点之后就成了
   (最初直写失败正是因为没给焦点)
2. 剪贴板 + `ACTION_PASTE` —— 自定义控件通常认粘贴,走的是 App 自己的文本插入逻辑;
   剪贴板用完立刻还原
3. `input -d <屏号> text` —— 走输入事件通道但带显示器维度,落不到用户屏上

始终不用输入法:整机只有一个 IME,实测 `mDisplayIdToShowIme` 恒为 0,agent 一调
输入法就是把键盘弹到用户脸上。

## 七、资源竞争:八类冲突和各自的处理

并发用一台手机,冲突不在 CPU 和内存,在那些**全机只有一份**的东西上。
每一条都对应一次真机上量到的现象。

| 冲突 | 现象 | 处理 |
|---|---|---|
| 触摸注入 | `dispatchGesture` 把手势画在真实屏幕上 | 只用 `performAction`,不合成触摸 |
| 输入法 | 整机一个 IME,`mDisplayIdToShowIme` 恒为 0 | 文字走 `ACTION_SET_TEXT`,永不调输入法 |
| 导航键 | `performGlobalAction` 没有显示器维度,会让用户的 App 后退一页 | 返回走 `input -d <屏> keyevent 4`;HOME 那条按键对副屏无效,改用显式启动该屏的 `category.HOME` |
| 全局焦点 | 副屏上只要出现可获焦窗口(造屏、启 App、页面跳转),**主屏当场丢掉自己的焦点窗口** —— 机主的软键盘被收起,他的 App 报「应用未响应」 | 事前避让:机主键盘弹着时不做动作(`Conflict.yieldWhileOwnerTypes`);副屏跨任务复用,把造屏这一下压到一个会话一次 |
| 焦点指针漂移 | agent 的无障碍动作会移动焦点指针 | 不处理。输入投递按屏走,真人手指产生的事件自带 0 号屏归属,照样落进用户的输入框并把指针拽回来 —— 实测 agent 连点 5 下后用户接着打字一字未丢 |
| 剪贴板 | agent 复制会覆盖用户正在用的内容 | `ClipboardGuard` 用完立刻还原 |
| task 归属 | 启动用户正在前台用的 App,系统把他的 task 搬到副屏 | 启动前用无障碍读 Display 0 的活动窗口,冲突就不启 |
| 副屏销毁 | 保留内容会让副屏上的 App 一股脑掉到主屏 | 主动 release,task 随屏消失 |

前三条是「不打扰」的直接实现,后四条是隐蔽的那一半 —— 它们不抢焦点、不抢画面,
屏幕上毫无痕迹,但用户复制的文字会消失、正在用的 App 会跳走。这一半更值得写下来。

「全局焦点」那一行改过两次,两次都值得记:第一版是「事后补一个空按键把焦点还回去」,
实测发现那个补救本身就是投递到主屏的按键事件,而主屏此刻恰恰没有焦点窗口 ——
它制造的 ANR 比它要修的问题严重。第二版以为根子在 display group,于是走
VirtualDeviceManager 造带独立显示器组的副屏,建成了、`setDisplayImePolicy` 也接受了,
但 `displayGroupId` 仍是 0,而且 AOSP 源码里 IMMS 每个用户只维持一份 IME 绑定,
IME 层根本不存在「两块屏各一个键盘」。真正的原因是焦点,见 FINDINGS.md。

## 八、动作空间:和这个领域的标准对照

一开始这份动作表是我按无障碍 API 能做什么直接写的,没对照过既有工作。后来补了功课,
拿 [AndroidWorld](https://github.com/google-research/android_world)(Google,这个领域
事实上的基准)和 [AutoGLM-Phone](https://docs.bigmodel.cn/cn/guide/models/vlm/autoglm-phone)
(智谱)的动作空间比了一遍:

| 本项目 | AndroidWorld | AutoGLM-Phone |
|---|---|---|
| `click` | `CLICK` | `Tap` |
| `long_click` | `LONG_PRESS` | `Long Press` |
| `set_text` | `INPUT_TEXT` | `Type` |
| `scroll` | `SCROLL` | — |
| `swipe` | `SWIPE` | `Swipe` |
| `double_tap` | `DOUBLE_TAP` | `Double Tap` |
| `enter` | `KEYBOARD_ENTER` | — |
| `launch` | `OPEN_APP` | `Launch` |
| `back` / `home` | `NAVIGATE_BACK` / `NAVIGATE_HOME` | `Back` / `Home` |
| `wait` | `WAIT` | `Wait` |
| `done(summary)` | `STATUS` + `ANSWER` | — |
| `ask` | — | `Take_over` |
| `note` | (M3A 的逐步 summary) | — |

对照下来自己缺了三个,`swipe` / `double_tap` / `enter`,已经补上。前两个不是冗余:
`scroll` 走 `ACTION_SCROLL_*`,只对**声明了自己可滚动**的容器有效,轮播图、侧边抽屉、
左滑删除都不走这条;`enter` 的理由 AndroidWorld 在源码注释里写得很直白 ——
有些控件光靠点是控制不了的,而且搜索框按回车提交比在树里找「搜索」按钮可靠得多。

反过来有两个是自己想出来、后来发现和既有工作撞了的,算是旁证:
`ask` 对应 AutoGLM 的 `Take_over`,`note` 对应 M3A 的逐步 summary(ReAct + Reflexion 那一路)。

**感知方式的选择也在这里得到了印证。** AndroidWorld 同时给了两个基线:M3A 用
Set-of-Mark 标注截图,T3A 纯文本无障碍树。论文的结果是文本方案与多模态相当、
有时更好(T3A 用 GPT-4o 拿到 59.7% 的单步动作成功率)。这条独立支持了本项目
第五节的选择。

**但这些工作都不解决本题的核心问题。** AndroidWorld 跑在模拟器里,AutoGLM-Phone
接管的是用户正在看的那块屏,scrcpy 是远程控制 —— 三者都默认「这台设备此刻归 agent」。
本题要的恰恰是**人和 agent 同时用同一台手机**,所以真正的工作量不在动作空间,
而在第四、七节那些:副屏隔离、焦点归还、以及每个动作都得挑一条带显示器维度的实现。

### 那把现成的框架直接指到副屏上呢

这是对这个项目最该问的问题:如果 AutoGLM-Phone 或者 AndroidWorld 的 agent 加个
显示器参数就能在副屏上跑,那这个项目就没什么价值。实测三条(主屏放设置、副屏放计算器):

| 现成框架依赖的工具 | 指到副屏 | 实测 |
|---|---|---|
| `uiautomator dump` | ❌ 做不到 | 抓到的是**设置**,副屏的计算器一个节点都没有 |
| `screencap -d <逻辑id>` | ❌ | `Display Id '110' is not valid` |
| `screencap -d <SurfaceFlinger id>` | ✅ | 正常出图,150KB,内容是副屏的计算器 |
| `input tap x y`(不带 -d) | ❌ | 落在**用户**的设置页上,把他导航到了子页面 |
| `input -d <屏> tap x y` | ✅ | 精确落在副屏 |

结论分两层:

**执行层是机械改动。** `input` 家族本来就有 `-d`,加上就是了。真正的坑在少数几个
没有显示器维度的 API:`performGlobalAction`、HOME 键(见前文,按键无效、要用显式
intent)、以及 `am start` 不加 `--activity-multiple-task` 会把用户正在用的 task 搬走。

**感知层要看它是哪一路。**
- 截图路线(AutoGLM-Phone、AppAgent、M3A)**可以**,但 `screencap` 要传 SurfaceFlinger
  的显示器 ID,不是逻辑 displayId,而那个 ID 每次造屏都变、得先 dumpsys 解析。
- 无障碍树路线(AndroidWorld T3A、DroidBot 这类基于 `uiautomator` 的)**不行**,
  它在感知层就只能看到用户那块屏。得换成装一个无障碍服务、走 `getWindowsOnAllDisplays()`,
  这不是改配置,是换机制。

**但这两层都不是这道题的难点。** 那些框架从来不需要处理的是:副屏本身得先有人造
(需要 shell 身份和 `ADD_TRUSTED_DISPLAY`),造屏和启 App 会收走用户的输入法,
剪贴板全机唯一,以及「用户此刻正在用哪个 App」这个判断 —— 它们默认这台设备归 agent,
所以从来没有「另一块屏上有个人」这件事要考虑。

换句话说:**那些框架的脑子可以直接换进来**(这个项目的动作空间已经和它们对齐了),
身子一行都用不了。这个项目的工作量全在身子上。

## 九、模型的输出到底有多大权力

这套东西里有一个 uid 2000 的 shell(Shizuku 拉起的特权桥),所以「模型能让它执行什么」
必须是个说得清的答案。

**模型的输出是一个封闭词表加参数,不是代码。** 每一步只能是这 11 个动作之一
(click / long_click / set_text / scroll / launch / back / home / wait / note / done / ask),
参数是序号、包名、文本、毫秒数。解析器只认这些,别的一律丢弃。它没有「执行命令」这个动作。

**但是间接路径存在过,而且是我自己写出来的。** 有两处把模型给的字符串拼进了
`sh -c` 的命令行:`input -d N text $文本` 和 `am start ... $(cmd package resolve-activity --brief $包名)`。
只转义了 `%` 和空格,`;` `&&` 反引号 `$()` 全都能过去。

威胁不是「模型想搞破坏」,而是**无障碍树里的文字是攻击者可控的**:商品标题、
网页内容、通知文本都会进模型的上下文。一段构造过的文案让模型把 payload 当搜索词
填进去,就等于把 uid 2000 的 shell 交出去。这条链叫 prompt injection 提权。

补转义只是打补丁,注入面还在。改法是让这类字符串**永远不经过 shell 解析**:

- 特权桥加一条 `execArgs(List<String>)`,`ProcessBuilder` 收 argv 数组时直接 execve,
  参数里的 shell 元字符只是普通字符。
- 凡是命令里含模型给的字符串,一律走 `execArgs`。`exec(String)` 只留给代码里写死的字面量。
- `launch` 里的 `$(...)` 命令替换拆成两步:先 `execArgs` 解析组件名,在 Kotlin 里取最后一行,
  再 `execArgs` 启动。
- 纵深防御:包名和组件名各过一道正则,挡住「合法但不是你以为的那个命令」
  (比如在包名位置塞 `--user 0`)。

回归检查放在特权桥自检里,拿真实 payload 走一遍:

```
whoami: uid=2000(shell) ... context=u:r:shell:s0
注入面: 干净 —— 参数没有被 sh 解析
```

## 十、已知边界

- **Shizuku 每次设备重启要重新激活。** 这是 Android 无线调试的设计,不是能绕过的。
- **副屏上的 App 是独立实例。** 用户在主屏登录的账号,副屏上的同一个 App 共享登录态
  (同一个 uid 同一份数据),但如果用户此刻正在前台用这个 App,启动会把他的 task 搬走 ——
  已在 `Conflict.userIsUsing()` 里挡掉,代价是这种情况下 agent 得换做法或者请示。
- **不做需要用户凭证的不可逆操作。** 付款、发消息这类一律先通过通知请示,不自己拍板。

- **无障碍服务开着时,微信不让付款。** 真机上撞到的:微信收银台报「存在安全风险」并拒绝付款。
  这是微信自己的反劫持策略 —— 一个能 `performAction(ACTION_CLICK)` 的无障碍服务,在它眼里
  和盗刷木马没有区别,和本项目做了什么无关。
  (关掉之后付款是否恢复,机主没有回报,我没有验证过。)

  **不是二选一,是让权限的生命周期等于任务的生命周期。** agent 一天里真正干活只有几分钟,
  没有理由为这几分钟让机主全天付不了款。`A11yGate` 开跑前打开无障碍、收工后关掉,
  机主什么都不用管。这件事本身不难,难在它改的是**机主全局的系统设置**,写坏了不报错,
  只是悄悄把别人的东西关掉,所以三条不变量都得守住:

  - 机主自己开着的不关(只关我们自己开的那一次,靠 SharedPreferences 里一个记号区分)
  - 只在那张冒号分隔的表里增删自己这一项,绝不整表覆盖 —— 读屏、按键映射都在同一张表里
  - 读不到那张表的时候一步都不动(桥断了返回的空串不等于「表是空的」)

  另有两处边界:长时任务(「盯着降价」)期间不关,因为它的触发器就注册在无障碍服务里,
  关掉等于把自己的任务链掐断;机主可以设 `A11Y_AUTO=0` 让权限保持常开。
  `scripts/tests/test-a11y-gate.sh` 在模拟器上逐条验这七种情况。

- **息屏后 agent 停工。** 副屏和主屏共用电源组,主屏一灭副屏跟着灭。这不是能绕过的,
  实测过三条路(OWN_DISPLAY_GROUP / DEVICE_DISPLAY_GROUP / 副屏 display context 上的
  唤醒锁)都不行,细节见 FINDINGS.md。
