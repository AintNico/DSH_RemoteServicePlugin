# 发布流程

打一个版本并把三端产物挂到 GitHub Release，其他设备就能直接下载安装。

## 1. 统一版本号

三处必须一致（当前 `1.0.0`）：

| 文件 | 字段 |
|---|---|
| `package.json` | `version` |
| `lib/service.js` | `const VERSION` |
| `app/android/app/build.gradle` | `versionName`（`versionCode` 每次发版 +1） |
| `app/desktop/DshRemote.csproj` | `<Version>` |

## 2. 构建产物

```powershell
powershell -ExecutionPolicy Bypass -File scripts/package-release.ps1
```

脚本会产出到 `release/`：

| 文件 | 内容 | 目标设备 |
|---|---|---|
| `DSH-Remote-<版本>-android.apk` | 安卓安装包 | 手机 |
| `DSH-Remote-<版本>-desktop-win-x64.zip` | 自包含桌面端（免 .NET 运行时） | Windows |
| `DSH-Remote-<版本>-plugin.zip` | 插件源码（不含构建产物） | 电脑 |

`release/` 已在 `.gitignore` 里，不会进仓库。

## 3. 打标签并推送

```powershell
git add -A
git commit -m "release: v1.0.0"
git tag -a v1.0.0 -m "DSH Remote 1.0.0"
git push origin main
git push origin v1.0.0
```

## 4. 创建 GitHub Release

1. 打开 `https://github.com/AintNico/DSH_RemoteServicePlugin/releases/new`
2. **Choose a tag** 选 `v1.0.0`
3. 标题填 `DSH Remote 1.0.0`
4. 说明直接复制 `docs/RELEASE_NOTES.md` 的内容
5. 把 `release/` 里的三个文件拖进 **Attach binaries**
6. 发布

## 5. 验证

在另一台设备上：

- 浏览器打开 Release 页 → 能下载到三个文件
- 手机装 APK → 能启动、能配对
- 电脑解压桌面端 → 双击能连上插件

## 备注

- **许可证**：仓库目前没有 LICENSE，别人使用默认无授权。要开源的话建议加一个（如 MIT），在 GitHub 仓库页右侧「Add license」即可生成
- **Release 资产大小**：桌面端自包含包约 160 MB，GitHub 单文件上限 2 GB，没问题
- 发布前建议跑一遍：`node --input-type=module -e "await import('./index.js')"` 和 `dotnet build app/desktop/DshRemote.csproj -c Debug -p:Platform=x64`
