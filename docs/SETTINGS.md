# HyperWin 的界面和配置

HyperWin 通过 LSPosed 的模块设置入口打开。使用 Android 原生 View、按钮、开关和对话框，提供浅色与深色布局。

## 配置

| 功能组 | 选项 | 对应阶段 |
| --- | --- | --- |
| Win 键 | 屏蔽本机 Win 动作 | 小米 Win 单击和长按界面处理 |
| Win 键 | 透传 Win 键 | Win 边沿及组合键的应用分发 |
| Alt+Tab | 跳过本机 Alt+Tab | Tab 分发阶段 |
| Alt+Tab | 保护按键序列 | Tab 排队阶段 |
| Win+Tab | 屏蔽本机多任务兜底 | 应用明确未处理组合键之后 |
| Win+Tab | 透传 Win+Tab | Win 已透传时，单独控制 Win+Tab 分发 |
| Fn 锁 | 启用小米键盘 Fn 锁 | 精确匹配键盘设备和扫描码后交换两排功能 |

关闭一个选项会交回该阶段的原系统处理。按键最终可否送到 Windows 也取决于远程客户端。Fn 锁支持在 Beta 2 验证的键盘接口。

应用未分组时使用个人设置，已分组时使用组设置。移动、移出和删除组不会覆盖个人设置。暂时从 LSPosed 移除的应用会立即退出生效范围，已有配置和组归属保留，以便重新加入作用域后使用。

## 维护入口

- `ShortcutFeatures` 定义稳定的功能位。新增功能时保留旧功能的位编号。
- `Profiles` 计算应用、分组和默认值的优先级，`ProfileStore` 按原子方式保存私有 JSON。
- `ModuleControl` 定义配置通信。系统通过受签名权限保护的广播接收命令，通过仅系统 UID 可读的 provider 获取配置。开机或手动刷新才重新向 LSPosed 读作用域，保存配置只同步已加载范围的功能设置。输入线程只读取已生成的内存配置。
- `WinModule` 按焦点窗口选用有效配置。Win、Alt+Tab、Win+Tab 各自使用对应常量。Fn 锁继续由 `FnLockController` 处理。
- `FeaturePage` 管理配置编辑，`ProbePage` 和 `KeyboardProbe` 提供前台测试。测试配置只施加到 HyperWin 自己的焦点窗口，在停止和超时时清理。选「原始输入」可直接观察系统发给测试页的事件。
