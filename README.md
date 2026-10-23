# DSH_RemoteServicePlugin

Control your DSH remotely using your smartphone.

## Status

- [x] P0 — 插件骨架、独立监听、配对鉴权、会话列表
- [x] P0.5 — 桌面端 WinUI 3 独立窗口（配对 + 会话列表）
- [x] P1 — 消息历史、实时推送、发送与停止（流式增量待补）
- [x] P2 — 审批推送与回传（策略开关、重试、超时回退、撤销）
- [x] P3 — 文件双向（附件上传、工作区浏览、签名下载）
- [ ] P3.5 — AI 主动推送文件（`send_file_to_phone` 工具 + 变更集准入）
- [x] P4 — 安卓原生应用初版（配对、会话、消息、审批）
- [ ] P4.5 — 安卓通知、前台服务、悬浮球、文件收发
- [ ] P5 — 外网访问

设计方案见 [docs/DESIGN.md](docs/DESIGN.md)。

## 组成

| 目录 | 内容 |
|---|---|
| 根目录 | DSH 插件（Node，HTTP/WebSocket 后端） |
| `app/desktop` | 桌面客户端（WinUI 3 / .NET 8 原生窗口） |
| `web/` | 调试用的网页版界面，正式客户端不使用 |

## 开发

```bash
# 插件语法与模块加载检查
node --input-type=module -e "await import('./index.js')"

# 桌面客户端
dotnet build app/desktop/DshRemote.csproj -c Debug -p:Platform=x64

# 桌面客户端（自包含发布，约 166 MB，可脱离 .NET 运行时运行）
npm run publish:desktop
```

安装到当前 profile：用 DSH 的 `plugin_manager` → `install_bundle`，目标指向本目录。

## 桌面端随插件自动启动

插件启用后会自己把桌面窗口拉起来，不需要单独启动：

- 优先使用 `app/desktop/dist/DshRemote.exe`（自包含发布版）
- 其次使用 `app/desktop/bin/x64/<Config>/net8.0-windows10.0.19041.0/DshRemote.exe`（开发构建）
- 都没有时，用本机 .NET SDK 自动构建一次（需要 .NET 8 SDK）
- 已经有窗口在运行时不会重复打开
- 首次启动会在桌面和开始菜单创建「DSH 远程」快捷方式

配置项（`cordis.patch.yml` 或 profile 配置）：

```yaml
desktop:
  autoLaunch: true   # 关掉可禁止随插件启动
  shortcuts: true    # 是否创建快捷方式
  exe: null          # 指定自定义路径时优先使用
```

## 使用

1. 插件启动后监听 `http://0.0.0.0:7317`。
2. **桌面端不需要配对**：它和插件在同一台电脑上，直接连本机端口。
3. 手机端在配对页输入配对码完成绑定；配对码在桌面端窗口左下角「配对码」里查看，可在同一弹窗里吊销设备。
4. 绑定后手机可查看会话、收发消息、处理审批、收发文件。

配对码 5 分钟有效，令牌保存在 `.dsh/profiles/<profile>/remote-service/state.json`（仅存哈希）。
