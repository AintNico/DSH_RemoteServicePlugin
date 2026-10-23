# DSH_RemoteServicePlugin

Control your DSH remotely using your smartphone.

## Status

- [x] P0 — 插件骨架、独立监听、配对鉴权、会话列表
- [x] P0.5 — 桌面端 WinUI 3 独立窗口（配对 + 会话列表）
- [x] P1 — 消息历史、实时推送、发送与停止（流式增量待补）
- [x] P2 — 审批推送与回传（策略开关、重试、超时回退、撤销）
- [x] P3 — 文件双向（附件上传、工作区浏览、签名下载）
- [ ] P3.5 — AI 主动推送文件（`send_file_to_phone` 工具 + 变更集准入）
- [ ] P4 — 安卓原生应用
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
```

安装到当前 profile：用 DSH 的 `plugin_manager` → `install_bundle`，目标指向本目录。

## 使用（P0）

1. 插件启动后监听 `http://0.0.0.0:7317`。
2. 电脑上打开 `http://127.0.0.1:7317/desktop` 查看配对码。
3. 手机浏览器打开 `http://<电脑局域网 IP>:7317`，输入配对码完成绑定。
4. 绑定后即可看到会话列表。

配对码 5 分钟有效，令牌保存在 `.dsh/profiles/<profile>/remote-service/state.json`（仅存哈希）。
