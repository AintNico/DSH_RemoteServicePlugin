# DSH_RemoteServicePlugin

Control your DSH remotely using your smartphone.

## Status

- [x] P0 — 插件骨架、独立监听、配对鉴权、会话列表
- [ ] P1 — 消息收发
- [ ] P2 — 审批与提问推送
- [ ] P3 — 文件双向传输
- [ ] P4 — 安卓壳（通知、前台服务、悬浮球）
- [ ] P5 — 外网访问

设计方案见 [docs/DESIGN.md](docs/DESIGN.md)。

## 开发

```bash
# 语法与模块加载检查
node --input-type=module -e "await import('./index.js')"
```

安装到当前 profile：用 DSH 的 `plugin_manager` → `install_bundle`，目标指向本目录。

## 使用（P0）

1. 插件启动后监听 `http://0.0.0.0:7317`。
2. 电脑上打开 `http://127.0.0.1:7317/desktop` 查看配对码。
3. 手机浏览器打开 `http://<电脑局域网 IP>:7317`，输入配对码完成绑定。
4. 绑定后即可看到会话列表。

配对码 5 分钟有效，令牌保存在 `.dsh/profiles/<profile>/remote-service/state.json`（仅存哈希）。
