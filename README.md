# Forge Card Image Download Fix

针对 [Card-Forge/forge](https://github.com/Card-Forge/forge) 的卡图批量下载补丁。

解决的问题：

- `Download Card Images` 对某些变体卡会回退到 `api.scryfall.com`
- DNS / TLS 不稳定时，整批下载会卡在 `UnknownHostException` 或连接超时
- 自动下载（`Automatically Download Missing Card Art`）正常，因为它走 `cards.scryfall.io` 直链

本补丁让批量下载也优先走 CDN 直链。

## 修改内容

基于上游源码：

~~~text
forge-gui/src/main/java/forge/gui/download/GuiDownloadFilteredCardImages.java
~~~

URL 解析顺序改为：

1. 当前印刷版本的 CDN 直链
2. **同名其他印刷版本的 CDN 直链**（新增）
3. Scryfall API
4. 旧 cardforge URL

同时保留上游已有的：

- 文件存在性去重（`.jpg` 完整路径）
- 双面卡 back face 处理
- CDN UUID 缓存预热
- 批量数据同步

补丁文件：

~~~text
patches/0001-prefer-cached-cdn-printings.patch
~~~

## 使用方法

要求：

- Windows
- JDK 17+，且包含 `javac` / `jar` / `javap`
- 已安装 Forge

在仓库根目录执行：

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\apply.ps1 -ForgeDir "D:\forge"
~~~

脚本会：

1. 备份原 jar 到 `backups/`
2. 用 `--release 17` 编译补丁类
3. 更新 jar 中的 `GuiDownloadFilteredCardImages.class`
4. 用 `javap` 验证

然后完全退出并重启 Forge，打开 `Download Card Images` 重新下载。

## 恢复原版

~~~powershell
powershell -ExecutionPolicy Bypass -File .\scripts\restore.ps1 -ForgeDir "D:\forge"
~~~

默认恢复最近一次备份，也可以指定：

~~~powershell
.\scripts\restore.ps1 -ForgeDir "D:\forge" -BackupPath ".\backups\xxx.bak-20261018-201828"
~~~

## 游戏更新后

Forge 更新后，jar 名称可能变化。重新执行：

~~~powershell
.\scripts\apply.ps1 -ForgeDir "D:\forge" -JarName "新的jar文件名.jar"
~~~

脚本会针对新 jar 重新编译。如果 Forge 改了相关 API，编译会失败，需要同步调整源码。

## 上游 PR

如果希望从源头修复，可以把：

~~~text
patches/0001-prefer-cached-cdn-printings.patch
~~~

应用到 [Card-Forge/forge](https://github.com/Card-Forge/forge) 的 fork，然后提交 PR。

补丁只改一个文件，且保留上游全部去重/缓存逻辑。

## 注意

- 这是二进制补丁，不是官方发布。
- Forge 使用 GPL-3.0，本仓库同样遵循 GPL-3.0。
- 修改前会备份 jar；可以用 `restore.ps1` 恢复。
- 仅修改 `GuiDownloadFilteredCardImages.class`，不修改游戏数据。
