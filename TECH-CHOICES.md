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

**`OWN_FOCUS`(Android 14+)** —— 副屏自己维护焦点。没有这一位时,agent 在副屏上的每次
操作都会把全局焦点指针拽过去。实测发现这**不影响用户打字**(输入分发是按屏走的,
全局焦点指针只对没有显示器归属的事件起作用),但那是「碰巧不出事」;有了这一位是
「结构上不会出事」。

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

1. **虚拟屏的截图路径本来就窄**。`screencap -d <逻辑id>` 对虚拟屏返回 80 字节空图
   (它只认 SurfaceFlinger 的物理显示器 ID),`screencap -a` 也只出主屏。要截虚拟屏
   必须自己接 Surface。而无障碍树是**唯一原生带显示器维度**的读取通道。
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

## 七、已知边界

- **`OWN_FOCUS` 的实际效果还没在「主屏有真实前台 App」的条件下验证。** 目前的测试都在
  锁屏状态下做,keyguard 不争焦点,这个条件不算数。
- **Shizuku 每次设备重启要重新激活。** 这是 Android 无线调试的设计,不是能绕过的。
- **副屏上的 App 是独立实例。** 用户在主屏登录的账号,副屏上的同一个 App 共享登录态
  (同一个 uid 同一份数据),但如果用户此刻正在前台用这个 App,启动会把他的 task 搬走 ——
  已在 `Conflict.userIsUsing()` 里挡掉,代价是这种情况下 agent 得换做法或者请示。
- **不做需要用户凭证的不可逆操作。** 付款、发消息这类一律先通过通知请示,不自己拍板。
