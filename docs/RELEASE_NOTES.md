# DSH Remote 1.0.0

用手机远程控制 DSH：看会话、收发消息、审批工具调用、双向传文件。

## 下载

| 文件 | 用途 | 装在哪 |
|---|---|---|
| `DSH-Remote-1.0.0-android.apk` | 手机客户端 | Android 8.0+ |
| `DSH-Remote-1.0.0-desktop-win-x64.zip` | 桌面客户端（自包含，免 .NET 运行时） | Windows 10 1809+ |
| `DSH-Remote-1.0.0-plugin.zip` | DSH 插件（服务端） | 跑 DSH 的电脑 |

## 安装顺序

1. **插件**先装：解压后把目录路径填到 DSH 的 `install_bundle`，启用插件，它会监听 `7317`
2. **桌面端**解压双击 `DshRemote.exe`，和插件同机免配对，左下角能看到手机访问地址和配对码
3. **手机**装 APK，填地址 + 配对码完成绑定

详细步骤：[docs/INSTALL.md](https://github.com/AintNico/DSH_RemoteServicePlugin/blob/main/docs/INSTALL.md)

## 这个版本有什么

- 会话按工作区分组，和 DSH 侧边栏一致
- 消息实时推送 + 流式增量渲染（Markdown、代码块、行内代码）
- 审批远程处理，超时自动回退到电脑
- 文件双向：手机多选上传 / 从微信 QQ 分享进来；电脑多选下载 / 分享到其他应用
- 桌面端托盘常驻，关窗不退出
- 远程访问一键 SSH 隧道（HTTPS + 令牌）
- 电脑 IP 变化后手机自动扫描找回

## 要求

- DSH 需提供 `sessionController`、`sessionQuery`、`workspaceRegistry` 服务
- 手机和电脑需在同一局域网（或自行开隧道）
- Windows 防火墙需放行 `7317` 入站（安装说明里有命令）

## 已知限制

- 没有中转服务器，公网访问依赖隧道工具
- QQ/微信聊天文件在应用私有目录，系统不允许第三方读取，需用「分享 / 用其他应用打开」递进来
- 桌面端必须和插件在同一台电脑上
