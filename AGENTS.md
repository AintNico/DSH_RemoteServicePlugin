# 协作约定

给在这个仓库里干活的 AI 代理看的几条硬性规则，用户已经明确要求过。

## 测试

1. **手机端的验证由用户手动完成。** 不要为了验证去抢手机焦点、改 App 数据、发 intent 或点屏幕。
   需要确认手机上的行为时，把「操作步骤 + 预期结果」写清楚，交给用户点。
2. **任何一项检查卡住超过 3 分钟，立即停止自动化尝试，转成手动测试项。**
   包括但不限于：adb 无响应、模拟器 ANR、`am start` 不返回、截图/拉取卡在人机交互上。
   转手时要说清楚：卡在哪一步、已确认了什么、剩下哪一步需要人来做。
3. 服务端与桌面端的行为（插件接口、二维码内容、桌面弹窗）仍可自动化验证；
   能用 `curl` / 单元测试 / 截图讲清楚的事情，不要推给用户。

## 构建

```powershell
# 插件：语法与单元测试
node --input-type=module -e "await import('./index.js')"
npm test

# 安卓
$env:JAVA_HOME='D:\android\jbr'
& 'D:\android\gradle\gradle-8.13\bin\gradle.bat' -p 'D:\DSH_RemoteServicePlugin\app\android' assembleDebug --no-daemon --console=plain

# 桌面
dotnet build app\desktop\DshRemote.csproj -c Debug -p:Platform=x64
```

## 注意

- **不要用 PowerShell 的 `Get-Content`/`Set-Content` 或字符串替换去改 Java/C#（含中文的源文件）**，
  会按 GBK 解码导致乱码。用文件编辑工具，或 `[System.IO.File]::ReadAllText/WriteAllText` 配 UTF8 无 BOM。
- 这台机器访问 GitHub 经常被重置；push 失败时先重试，必要时用本地 CONNECT 隧道换边缘 IP。
