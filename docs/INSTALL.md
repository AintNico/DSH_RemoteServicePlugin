# 安装指南

三个部分分开安装。顺序建议：**插件 → 桌面端 → 手机端**，因为配对码在桌面端显示。

---

## 一、电脑：装插件

### 1. 拿到代码

```powershell
git clone https://github.com/AintNico/DSH_RemoteServicePlugin.git
```

如果这台电脑没有 git，也可以从 Releases 下载 `DSH-Remote-<版本>-plugin.zip` 解压到任意目录。

### 2. 装进 DSH

在 DSH 里打开插件管理（`plugin_manager`），执行 `install_bundle`，`target` 填**上一步目录的绝对路径**，例如：

```
D:\DSH_RemoteServicePlugin
```

装好后启用 `dsh-remote-service` 插件。

### 3. 确认在跑

```powershell
curl http://127.0.0.1:7317/api/health
```

正常返回：

```json
{"ok":true,"service":"dsh-remote","version":"1.0.0","pairing":true,"devices":0}
```

看到 `"service":"dsh-remote"` 就说明起来了。

> 端口被占用时改 `cordis.patch.yml` 里的 `port`，手机端地址也要跟着改。

---

## 二、电脑：装桌面端

### 方式 A：让插件自动构建（需要 .NET 8 SDK）

插件启用时会自己找桌面端：优先 `app/desktop/dist/DshRemote.exe`，其次 `bin/x64/...`，都没有就用本机 SDK 构建一次。装好 SDK 的话这一步什么都不用做。

### 方式 B：下自包含包（不需要运行时）

从 Releases 下载 `DSH-Remote-<版本>-desktop-win-x64.zip`，解压到任意目录，双击 `DshRemote.exe`。

**注意**：解压后整个文件夹要保留，不能只拷 exe——里面是 .NET 和 Windows App SDK 的运行时文件。

桌面端和插件同机，**不需要配对**，打开就是已连接状态。关窗会隐藏到系统托盘，托盘菜单里才有「退出」。

---

## 三、手机：装 App 并配对

### 1. 装 APK

从 Releases 下载 `DSH-Remote-<版本>-android.apk`，在手机上打开安装。

首次安装需要在系统里允许「安装未知来源应用」。

### 2. 拿到电脑地址

桌面端窗口左下角显示：

```
手机访问：http://192.168.1.23:7317   [复制]
```

点「复制」可以直接拷到剪贴板。

### 3. 配对

手机打开 App：

1. 服务地址：粘贴上一步的地址（或点「**自动发现电脑**」，让 App 自己扫局域网）
2. 配对码：点桌面端左下角「**配对码**」按钮，把 6 位数字填进来
3. 设备名称：默认是手机型号，可改
4. 点「配对」

配对码 5 分钟有效，过期就在桌面端点「重新生成」。

---

## 常见问题

### 手机连不上

按顺序排查：

1. **是不是同一个 Wi-Fi？** 手机连的是流量或别的网络就连不到
2. **地址对不对？** 电脑的 IP 会变。点手机上的「自动发现电脑」重新找，或看桌面端左下角的新地址
3. **Windows 防火墙** 可能拦了 7317。管理员 PowerShell 放行一次：

   ```powershell
   New-NetFirewallRule -DisplayName "DSH Remote 7317" -Direction Inbound -Protocol TCP -LocalPort 7317 -Action Allow -Profile Private
   ```

4. 看插件是否在跑：`curl http://127.0.0.1:7317/api/health`

### 提示「登录已失效」

令牌没了（可能被吊销或插件状态重置）。在桌面端「配对码」弹窗里确认设备还在，不在就重新配对一次。

### 桌面端显示「未连接到 DSH 插件」

插件没启用，或端口不是 7317。先去 DSH 里确认插件状态，再点桌面端的「重试连接」。

### 每次重启电脑 IP 都变

两种解法：

- **路由器 DHCP 静态绑定**（推荐）：把电脑的 MAC 绑定到固定 IP
- **靠自动发现**：手机端连不上时会自动扫网段找回，不需要手动改地址

### 想在外网用

桌面端点「**远程访问**」→「启动隧道」，会拿到一个 `https://xxx.lhr.life` 地址，把它填到手机的服务地址里即可。

- 地址每次启动都变，停止后失效
- 需要电脑能出网（SSH 22 端口）
- 隧道下配对码只能在电脑上看，手机仍需正常配对
