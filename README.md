# HyperWin

HyperWin 是面向小米平板 HyperOS 的 LSPosed API 102 模块。它让选定应用接收实体键盘的 Win（Meta）、Alt+Tab 和 Win+Tab，并支持小米妙控键盘 Fn 锁。2.0.0 可按应用或分组配置这些功能，也可在模块内直接测试按键。

## 功能

- 在所选应用获得键盘焦点时，抑制 Win 单击唤出的 Dock 和长按快捷键列表，应用仍可收到 Win 事件。
- 将 Alt+Tab 所需的按键事件交给应用，支持在 Windows 远程会话中切换窗口。
- 应用有机会接收 Win+Tab。若所选应用未处理，HyperWin 跳过本机的多任务兜底。
- 在 LSPosed 中选择应用。模块启动时读取作用域，也可在 HyperWin 主页手动刷新。
- 可选的小米键盘 Fn 锁。开启后在作用域内直接使用 F1–F12，按住 Fn 使用亮度、媒体等原功能。默认关闭。当前仅匹配已验证的 Keyboard `15d9:00a3` 和 Consumer `15d9:00a4`。

## 兼容性

开发验证环境为小米平板 `jinghu`、Android 17、HyperOS `OS4.0.5.0.XOXCNXM`，使用支持现代 API 102 的 LSPosed。输入处理依赖 ROM 实现，其他机型和 ROM 版本尚待验证。当前支持主用户、默认屏幕与解锁状态下的焦点窗口。

## 安装与使用

1. 从 [GitHub Releases](https://github.com/Mirer-1352/HyperWin/releases) 下载并安装 `HyperWin-*.apk`。
2. 在 LSPosed 启用 **HyperWin**，勾选 **系统框架** 和需要生效的应用。若正在使用原版 MiuiPadMeta，先在 LSPosed 停用原版，避免两个模块同时修改输入分发。
3. 软重启并解锁平板。在已勾选的应用中测试 Win；连接远程会话后测试 Alt+Tab 和 Win+Tab。远端是否响应组合键取决于客户端的键盘重定向支持与设置。
4. 增删目标应用后，在 HyperWin 主页点「刷新作用域列表」。首次勾选系统框架或安装升级后，需软重启并解锁。

从 LSPosed 的 HyperWin **模块设置**打开界面。HyperWin 不在桌面创建图标。

- **主页**显示作者、系统模块状态和刷新作用域按钮。
- **测试**记录前台收到的测试按键，持续 30 秒。可查看原始输入，或在测试页临时使用默认配置和某个作用域应用的生效配置。
- **功能**按应用或分组配置 Win、Alt+Tab、Win+Tab 和 Fn 锁。每个应用可加入一个组，加入后使用组配置，移出或删除组后恢复个人配置。

保存配置后会通知系统进程应用。升级时保留 Beta 2 的默认行为，包括原来的 Fn 锁状态。Win+Tab 透传依赖 Win 透传。其他型号的键盘保留系统原有 Fn 行为。

[Fn 锁实测映射和验证范围](docs/FN_LOCK_PLAN.md)

Beta 1、Beta 2 和同签名的测试版均可直接覆盖升级。

[配置说明和代码维护入口](docs/SETTINGS.md)

## 排查

- 模块未生效时，确认 LSPosed 已勾选「系统框架」及目标应用。软重启并解锁后，在 HyperWin 主页核对激活状态并刷新作用域。
- 自 2.0.1 起，HyperWin 在系统日志中仅保留启动状态与错误信息。需要检查按键时，使用模块内的「测试」页。
- 若只在某个界面出问题，反馈时请注明设备代号、ROM、Android 和 LSPosed 版本，以及应用包名和复现步骤。可附上相关模块日志，分享前删去个人信息。
- 若要恢复系统默认的键盘行为，在 LSPosed 停用 HyperWin 后软重启。

## 从源码构建

使用 PowerShell 7、JDK 17 或更新版本、Android SDK 平台 `android-37.0` 和 Build Tools `36.0.0`。设置 `JAVA_HOME` 和 `ANDROID_HOME`，也可向脚本传入 `-Jdk` 和 `-Sdk`。

```powershell
.\test.ps1
.\build.ps1
```

`build.ps1` 运行测试并在 `dist/` 生成未签名 APK。生成可安装 APK 时，提供自己的密钥库和别名，并在当前 PowerShell 会话中设置 `HYPERWIN_STORE_PASSWORD` 和 `HYPERWIN_KEY_PASSWORD`。

```powershell
.\build.ps1 -Keystore 'C:\path\to\release.p12' -KeyAlias 'hyperwin'
```

[vendor/README.md](vendor/README.md) 记录构建依赖的来源和校验值。

## 许可

HyperWin 基于 [CwithW/MiuiPadMeta](https://github.com/CwithW/MiuiPadMeta) 的 Android 12+ 实现移植，沿用 [GPL-3.0](LICENSE)。感谢原作者 Chara White。libxposed 依赖采用 Apache-2.0。
