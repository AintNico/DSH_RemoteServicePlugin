# DSH 手机远程控制插件 — 设计方案

> 状态：设计定稿（P0 开工前）
> 最后更新：2026-10-23

## 1. 背景与目标

在手机上远程使用 DSH：

1. **消息** —— 查看电脑端会话的消息流，并发送新消息。
2. **审批** —— 电脑端触发的工具审批、用户提问，推送到手机待处理。
3. **文件** —— 手机向电脑传文件；电脑把修改好的文件发回手机。
4. **形态** —— 两端都是原生应用：桌面端 WinUI 3 独立窗口，安卓端 Java 原生界面；不使用浏览器承载界面。

非目标：多人协作、iOS 原生端、公网直连（P5 之前不做）。

## 2. 已确定的参数

| 决策 | 取值 | 说明 |
|---|---|---|
| 手机端 | 仅 Android | iOS 无法实现系统级悬浮窗，暂不支持 |
| 网络范围 | 仅局域网 | 外网访问留到 P5，用 Tailscale 而非端口映射 |
| 审批超时 | 重试一次后回退 | 推送 → 10s 无响应重推 → 再 20s 无响应回退电脑端 |
| 文件推送准入 | 规则 B（宽松版） | 本轮变更集内直接推；其余需确认 |

## 3. 总体架构

```
桌面客户端（WinUI 3）        安卓客户端（Java + 原生 UI）
        │  HTTP + WSS              │
        └────────────┬─────────────┘
                     ▼
插件 Host 侧 @ DSH —— 独立监听 0.0.0.0:7317
   ├─ gateway      监听、连接管理、重连与消息队列
   ├─ auth         配对码、设备令牌、限速
   ├─ approvals    approval/request 与 user-questions/request 的拦截与回传
   ├─ sessions     sessionController.list / prompt / cancel / follow
   ├─ files        上传绑定、下载签名 URL、准入判定
   └─ bridge       session/event → 推送
```

三条约束：

- **客户端不承载 Web UI。** 两端都是原生界面，插件只提供 HTTP / WebSocket 接口。
- **不复用 DSH 的 51992 端口。** 实测该服务只监听 `127.0.0.1`，手机不可达；且复用会与 DSH 自身路由、升级产生耦合。
- **控制流与文件流分离。** 少量控制消息走 WebSocket；文件走 HTTP，才能有进度、断点续传和系统下载器支持。

## 4. 传输协议

### 4.1 HTTP

| 路由 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/` | GET | 无 | 手机页面 |
| `/desktop` | GET | 仅回环 | 电脑端配对页，显示配对码与设备列表 |
| `/api/health` | GET | 无 | 存活探测 |
| `/api/pair` | POST | 配对码 | 用 6 位码换取长期令牌 |
| `/api/sessions` | GET | Bearer | 会话列表 |
| `/api/sessions/:id/messages` | GET | Bearer | 历史消息（分页） |
| `/api/sessions/:id/prompt` | POST | Bearer | 发送消息 |
| `/api/files/:sessionId/:uploadId` | PUT | Bearer | 分块上传，支持 `Content-Range` |
| `/api/files/:sessionId/:uploadId/complete` | POST | Bearer | 完成上传并绑定到一条消息 |
| `/api/files/:fileId` | GET | 一次性签名 | 下载，支持 `Range` |
| `/api/workspace` | GET | Bearer | 浏览会话工作区（只读） |

### 4.2 WebSocket `/ws`

客户端连上后先发 `hello`（携带令牌），服务端回 `ready`。消息帧类型：

- `message/append` —— 新消息
- `message/stream` —— 流式增量
- `approval/request` —— 待审批卡片
- `approval/revoke` —— 请求已失效，撤回卡片
- `question/request` —— 待回答提问
- `file/progress` —— 传输进度
- `file/ready` —— 文件可下载
- `session/status` —— 运行状态变化

断线重连：客户端携带 `lastSeq` 续传，服务端从会话事件日志补齐缺口。

## 5. 鉴权与配对

1. **本机回环免令牌**：桌面端与插件跑在同一台电脑上，来自 `127.0.0.1` 的请求直接放行，桌面端不需要配对。
2. 插件首次启动生成 6 位配对码，5 分钟有效，可在桌面端窗口的「配对码」里查看。
3. 手机输入配对码 + 设备名 + **稳定设备标识**，换取 32 字节随机令牌；只有远端设备需要令牌。
4. 服务端只保存令牌的 SHA-256，令牌由手机端安全存储持有。
5. 令牌只走 `Authorization` 头或 WebSocket 子协议，**不放 URL**（避免 Referer 与日志泄漏）。
6. 配对失败每 IP 5 次锁 1 分钟；令牌可随时在桌面端「配对码」弹窗里吊销。
7. **令牌 24 小时滑动有效**：有效期 = 最近一次成功请求 + 24 小时，正常使用等于长期有效，连续闲置一天才需要重新配对。
8. **设备去重**：同一设备标识再次配对时原地刷新令牌和有效期，不新增条目；管理列表按设备标识去重、按最后活跃时间倒序。
9. 下载链接使用一次性签名：5 分钟有效，失效后需重新申请。

## 6. 审批与提问推送

### 6.1 拦截点

- `approval/request`（waterfall 事件）：返回 `allowed-once` / `rejected` 即接管，`next()` 交回后续回答者。
- `user-questions/request`（waterfall 事件）：返回完整答案即接管。

**已实测（2026-10-23）**：宿主侧把审批转发给浏览器的桥接器（`@deepseek-ai/dsh-api-remotes`）本身也是一个 `approval/request` 监听器，且按连接动态注册。以 `prepend: true` 注册的监听器排在它之前，真实派发时会被先调用、并可截断整条链：

```
hooks: [0] remoteApprovalAnswerer (prepend)  ← 本插件
       [1] (anonymous)                        ← Remote 转发桥接器
```

动态验证：对一个临时会话真实派发一次 `approval/request`，本插件收到并返回 `rejected`，调用方立即拿到该结果，桥接器未被调用。

**前提条件**：只有会话的审批策略为 `ask` 时才会走到 answerer 链。策略为 `never` 时 `approval.request()` 直接返回 `rejected`，不经过任何回答者——此时手机端无从审批。

### 6.2 状态机

```
收到审批
├─ 手机不在线 ──────────────────────► next() 交回电脑端
├─ 手机在线 → 推送卡片 #1（震动 + 响铃）
│     ├─ 10s 内回应 ────────────────► 返回结果
│     ├─ 10s 无回应 → 重推 #2（强提醒）
│     │      ├─ 20s 内回应 ─────────► 返回结果
│     │      └─ 仍无回应 ───────────► next() 交回电脑端
│     └─ signal 中止 ───────────────► 撤回卡片，返回 cancelled
└─ 手机先答则手机的结果生效
```

### 6.3 两个必须处理的边界

- **撤回**：`approval/request` 携带 `signal`，回合取消或结束时手机卡片必须同步消失，避免对已失效请求作答。
- **电脑端盲区**：不 `next()` 时电脑端不会弹窗，前 30 秒只有手机可见。P2 先接受该盲区；若体验不佳，再改为"双通道并发、先答先得"。

## 7. 文件双向传输

### 7.1 手机 → 电脑

```
选择文件 → HTTP PUT 分块上传
  → fileUploads.uploadStream({ sessionId, data, name })
  → { receiptId, file }
  → fileUploads.bindPrompt(agent, [receiptId], requestId)
  → sessionController.prompt(...)
  → binding.commit()
```

上传的文件作为**一条带附件的用户消息**进入会话，AI 可直接读取；同时在 `<workspace>/uploads/` 落一份副本（可配置关闭）。

### 7.2 电脑 → 手机

三种触发方式：

| 方式 | 说明 | 阶段 |
|---|---|---|
| AI 主动推 | 注册 `send_file_to_phone` 工具 | P3 |
| 手机主动拉 | 浏览工作区目录后下载 | P3 |
| 电脑端按钮 | DSH 界面内"发送到手机" | P4 |

读取：会话附件用 `attachments.readFileStream`，工作区文件用 `fs.readByteRange` 按段读，避免大文件整块进内存。

### 7.3 准入规则（规则 B）

模型调用 `send_file_to_phone(path)` 时，服务端自行判定，不信任参数：

```
├─ 解析真实路径，校验工作区白名单
│    └─ 越界（含软链接逃逸）──────► 拒绝
├─ 本回合变更集（workspaceChanges.summary）命中？
│    ├─ 命中 ────────────────────► 直接推送
│    └─ 未命中 ──────────────────► approval.request 确认
└─ 大小 / 配额检查 ──────────────► 超限拒绝
```

- "本轮变更集"以 `workspaceChanges.summary(sessionId, seq)` 为准，**以模型自述为准是错误的**。
- 确认卡片复用 P2 的审批链路，不另建 UI。
- 卡片必须显示**完整路径与大小**，路径过长时中间省略、保留首尾，以便分辨文件来源。

### 7.4 边界情况

| 情况 | 处理 |
|---|---|
| 手机不在线 | 直接返回未送达，不排队（延迟送达的旧文件比不送达更糟） |
| 文件已删除 | 返回明确错误，便于模型纠正 |
| 传输中断 | 断点续传；24 小时未完成则废弃并清理临时文件 |
| 重名 | 追加时间戳后缀 |
| 文件名 | 去路径分隔符与控制字符，做 NFC 归一 |

### 7.5 限额

默认单文件 200 MB、单次会话累计 2 GB，可配置；超限在建立连接时即拒绝。

## 8. 数据与持久化

令牌、设备、审计日志存于 `${DSH_PROFILE_DIR}/remote-service/state.json`：

```json
{
  "version": 1,
  "devices": [{ "id": "...", "name": "Pixel", "tokenHash": "...", "createdAt": 0, "lastSeenAt": 0 }],
  "audit": [{ "at": 0, "kind": "approval|file", "detail": {}, "outcome": "allowed-once" }]
}
```

审计日志仅保留最近 2000 条。

## 9. 客户端形态

两端均为原生应用，不内嵌浏览器。

**桌面端（`app/desktop`，WinUI 3 / .NET 8）**

- 独立窗口，自有图标与任务栏条目；未打包（`WindowsPackageType=None`），免安装运行。
- 左侧会话侧边栏 + 右侧详情区，后续接入消息流与审批卡片；侧边栏可收起。
- 令牌存放在 `%LOCALAPPDATA%\DshRemote\settings.json`，仅本机可读。
- 构建：`dotnet build -c Debug -p:Platform=x64`。

**安卓端（`app/android`，Java + 原生 UI）**

- 原生 Activity / RecyclerView 布局，不使用 WebView。
- 原生能力：本地通知、前台服务、悬浮球（`SYSTEM_ALERT_WINDOW`）、系统文件选择器。
- 局域网 HTTP 不是安全上下文，浏览器方案无法使用 Service Worker / Web Push；原生应用没有这个限制，这是选择原生实现的原因之一。

## 10. 分期

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 插件骨架、独立监听、配对鉴权、会话列表 | 手机连上、配对成功、看到会话 |
| **P0.5** | **桌面端 WinUI 3 独立窗口（配对 + 会话列表）** | 窗口能启动、配对成功、列出真实会话 |
| P1 | 消息收发（历史 / 实时推送 / 发送 / 停止） | 发消息，电脑端回复，客户端实时可见 |
| P2 | 审批与提问推送（重试 + 回退） | 电脑端审批，客户端点击放行；不点则超时回退 |
| P3 | 文件双向（上传绑消息、浏览下载） | 两个方向各跑通一次（已完成上传与下载，AI 主动推送见 P3.5） |
| P3.5 | `send_file_to_phone` 工具 + 变更集准入（规则 B） | 待做 || P4 | 安卓端原生应用（通知、前台服务、悬浮球） | 锁屏收到审批通知 || P5 | 外网访问（Tailscale）、多设备 | 4G 下可用 |

## 11. 风险与未验证假设

| 风险 | 影响 | 应对 |
|---|---|---|
| waterfall 监听顺序 | ~~可能无法先于 DSH 自带 UI 接管审批~~ **已验证**：`prepend` 可排到转发桥接器之前并截断 | 保持 `prepend`，勿改为普通注册 |
| `workspaceChanges.summary` 的变更集粒度未知 | 可能按回合粗粒度，误判"本轮修改" | P3 前实测；必要时退化为"确认优先" |
| 安卓后台杀进程 | 长连接断开、消息丢失 | P4 前台服务 + 重连队列 |
| 原生构建链依赖本机工具链 | WinUI 需要 Windows App SDK，安卓需要 SDK/JDK | 版本固定在仓库文档中；CI 后续补 |
| 局域网暴露面 | 同网段设备可扫到端口 | 全部接口令牌鉴权 + 配对限速 |
| DSH 升级改动事件契约 | 审批/会话接口失效 | 契约集中在 `lib/dsh.js` 适配层，升级只需改一处 |

## 12. 附录：依赖的 DSH 接口

| 能力 | 接口 |
|---|---|
| HTTP 路由（可选） | `ctx.webServer.register` |
| 会话列表 | `ctx.sessionController.list` |
| 发送消息 | `ctx.sessionController.prompt` |
| 取消回合 | `ctx.sessionController.cancel` |
| 消息流 | `session/event` 事件 / `ctx.sessionController.follow` |
| 审批 | `approval/request` 事件、`ctx.approval.request` |
| 提问 | `user-questions/request` 事件 |
| 文件接收 | `ctx.fileUploads.uploadStream` / `bindPrompt` |
| 文件读取 | `ctx.attachments.readFileStream` / `ctx.fs.readByteRange` |
| 变更集 | `ctx.workspaceChanges.summary` |
| 注册工具 | `ctx.tools.register` |
