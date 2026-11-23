# 小米键盘 Fn 锁开发计划

基线：`0593d5d` / `v1.0.0-beta.1`。开发目标：作用域内默认 F1–F12，按 Fn 后恢复键帽功能；独立开关默认关闭。作用域仍只在启动时读取，不轮询。

## 设备与采样（2026-11-23）

实测设备 `jinghu` / `OS4.0.5.0.XOXCNXM`。键盘 vendor `0x15d9`，Keyboard product `0x00a3`，Consumer product `0x00a4`。仅名称、vendor、product 同时匹配才映射；不硬编码 event 节点或 Android deviceId，也不要求 `isExternal`（实机为 false）。未知键盘保持原样。

用户按顺序完成 Fn 单击两次、普通顶部十二键、Fn+顶部十二键。两路没有单独 Fn 事件；普通键输出与标准 F 键可逐一配对：

| 位置 | 普通输出（Linux） | Android keyCode | Fn 输出 |
| --- | --- | --- | --- |
| F1 | BRIGHTNESSDOWN / scan 224 | 220 | F1 |
| F2 | BRIGHTNESSUP / 225 | 221 | F2 |
| F3 | F20 / 190 | 333 常规，191 HyperOS | F3 |
| F4 | SYSRQ / 99 | 120 | F4 |
| F5 | F24 / 194 | 337 常规，190 HyperOS | F5 |
| F6 | F23 / 193 | 336 常规，189 HyperOS | F6 |
| F7 | PREVIOUSSONG / 165 | 88 | F7 |
| F8 | PLAYPAUSE / 164 | 85 | F8 |
| F9 | NEXTSONG / 163 | 87 | F9 |
| F10 | MUTE / 113 | 164 (VOLUME_MUTE) | F10 |
| F11 | VOLUMEDOWN / 114 | 25 | F11 |
| F12 | VOLUMEUP / 115 | 24 | F12 |

普通 F1/F2/F7–F12 来自 Consumer，其余来自 Keyboard。Fn 后 F1–F12 全部来自 Keyboard。映射同时校验 keyCode 和 scanCode，避免同码的其他键被转换。

首版在真机上通过了九颗键及 Windows App 的 Alt+F4。F3、F5、F6 的 Linux 事件与进入 Android 策略的键码不同。交互式采集按同一事件时间确认，物理 F3/F6 在 HyperOS 上分别进入 `BUTTON_4`（191）和 `BUTTON_2`（189），F5 在 `dev.2` 验证为 `BUTTON_3`（190）。`MiuiKeyEventUtil.matchMiuiKeyForIICKeyboard` 将 F3/F5/F6 对应的别名转换为 `9996`（麦克风静音）、`9997`（语音助手）、`9998`（勿扰）。`MiuiKeyInterceptExtend.interceptMiuiKeyboard` 在释放时调用对应动作。

修复版启动时验证 ROM 的三个别名，再接受正确扫描码和设备的别名输入。反向映射把两个边沿交给原策略，平板执行动作，应用不会收到单独的 `BUTTON_*` 按下事件。

## 实现流程

1. 先采样再编码。保存两路原始事件与 ROM 方法签名；原始输入不记录字符键。
2. 将设备白名单、双向映射、按键状态拆成可独立测试的逻辑。首次 DOWN 固定是否映射，UP 不得因开关、焦点改变变成另一颗键。
3. 系统排队回调标记需要转换的原事件并跳过原键的亮度/媒体策略；紧接着在 native 输入过滤回调中消费原事件，避免 policy 丢弃事件时取消同设备的 Ctrl/Alt。替代事件先经过原队列策略，再通过 native filtered injection 保留 deviceId 和修饰键。已有无障碍过滤器存在时，替代事件继续交给它，不替换其 filter 或 host。
4. 由 Android 原生分发器处理重复和长按；焦点变化、关闭开关、设备移除时清理按键配对。没有重复计时器或轮询。原输入过滤开关仅在第一次启用功能时打开，之后保留到 system_server 重启，以安全消费尚未释放的原键；关闭功能后原事件直接经过系统既有路径。映射状态有上限，失败时停用新映射并保留配对清理。
5. 在 LSPosed 模块设置页提供开关，不添加桌面入口。保存后用模块签名保护的广播通知系统进程；开机快照也包含开关。原作用域机制不变。
6. 本地验证双向十二键、非目标设备、作用域、跨焦点/切换开关、重复/松开与原快捷键回归；构建并验证签名和元数据。
7. 真机先测试开关关闭的回归，再在 MetaProbe 核对完整事件，最后验收远程 Windows 的 F1/F5/F11、Alt+F4、Ctrl+F4 与 Fn 原功能；测试未勾选应用及其他键盘。

## 验收与边界

不能用编译或单元测试代替真机验收。默认关闭；未知 ROM 签名不启用此扩展。原有 Win/Alt+Tab 功能与本扩展分开安装，扩展失败不应撤销原功能。仅主用户、默认屏幕、解锁的作用域焦点窗口。

原生注入与重复的参考：[Android 17 InputDispatcher](https://android.googlesource.com/platform/frameworks/native/+/android-17.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp)、[InputManagerService 的 filtered injection](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/services/core/java/com/android/server/input/InputManagerService.java)。实机 ROM 已核对六参数 native `injectInputEvent(InputEvent, boolean, int, int, int, int)` 和 `interceptKeyBeforeQueueing(KeyEvent, int)`。

## 当前验证

- 普通十二键与 Fn 十二键的原始 DOWN/UP 齐全。原始采集日志保存在本地 `docs/fn-lock-evidence/`。
- `test.ps1`：原有 85 项检查和新增 86 项 Fn 锁检查通过；全部 Java 源码编译通过。
- 同签名测试 APK 已构建，现代元数据与签名、单独的 signature 配置权限通过检查。默认关闭，模块设置入口没有 LAUNCHER 类别。
- `1.1.0-dev.1-fn-lock`（30009）与 MetaProbe 1.2 已覆盖安装。安装前平板运行的是 alpha07（30007），不是首发 beta；源码基线仍为正式 beta 的提交。
- `dev.1` 实机结果为九颗键与远程 Alt+F4 正常，F3/F5/F6 两个方向均有差异。
- `dev.2`（30010）实机修复了 F5。F3/F6 的 Android 键码对调，单按未命中，Fn 后两项系统动作互换。
- `dev.3`（30011）通过 197 项本地检查及签名校验。软重启后，MetaProbe 实机收到 F3/F6 完整按下和松开，用户确认 Fn+F3 切换麦克风静音、Fn+F6 切换勿扰。
- `1.1.0-beta.2`（30012）沿用 `dev.3` 的代码，仅调整版本号和发布文档。
