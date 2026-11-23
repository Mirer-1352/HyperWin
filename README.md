# HyperWin

HyperWin 是面向小米平板 HyperOS 的 LSPosed API 102 模块。连接实体键盘时，它让选定应用接收 Win（Meta）、Alt+Tab 和 Win+Tab，适合 Windows App（远程桌面）和 Moonlight 等客户端。

## 功能

- 在所选应用获得键盘焦点时，抑制 Win 单击唤出的 Dock 和长按快捷键列表，应用仍可收到 Win 事件。
- 将 Alt+Tab 所需的按键事件交给应用，支持在 Windows 远程会话中切换窗口。
- 应用有机会接收 Win+Tab。若所选应用未处理，HyperWin 跳过本机的多任务兜底。
- 在 LSPosed 中选择应用。模块启动时读取作用域，平时使用内存中的名单。

## 兼容性

开发验证环境为小米平板 `jinghu`、Android 17、HyperOS `OS4.0.5.0.XOXCNXM`，使用支持现代 API 102 的 LSPosed。输入处理依赖 ROM 实现，其他机型和 ROM 版本尚待验证。当前支持主用户、默认屏幕与解锁状态下的焦点窗口。

## 安装与使用

1. 从 [GitHub Releases](https://github.com/Mirer-1352/HyperWin/releases) 下载并安装 `HyperWin-*.apk`。
2. 在 LSPosed 启用 **HyperWin**，勾选 **系统框架** 和需要生效的应用。若正在使用原版 MiuiPadMeta，先在 LSPosed 停用原版，避免两个模块同时修改输入分发。
3. 软重启并解锁平板。在已勾选的应用中测试 Win；连接远程会话后测试 Alt+Tab 和 Win+Tab。远端是否响应组合键取决于客户端的键盘重定向支持与设置。
4. 增删作用域后，再软重启并解锁一次。所有配置都在 LSPosed 中完成。

HyperWin 沿用本项目现代 API 102 测试版的应用 ID。使用同一签名的 alpha 版本可以覆盖安装。

## 排查

- 模块未生效时，先核对 LSPosed 中的「系统框架」和目标应用两项都已勾选。软重启、解锁后查看 `HyperWin` 模块日志里的 `READY` 和 `SCOPE_APPLIED source=boot`。
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
