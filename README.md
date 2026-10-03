# zComic

个人使用的 Android 漫画应用，面向 Android 14 及以上设备。应用访问 [Kmoe](https://kxo.moe/)，检索漫画，下载单卷 EPUB 到 `Documents/zComic`，并在应用内离线阅读。两台个人设备各自保存账号会话、下载记录和阅读位置，不提供同步或分发。

应用包含书架、关键词检索与四种网站排序、卷册下载队列、本地 EPUB 导入及图片漫画阅读器。界面统一使用深色表面和橙色操作色。下载优先通过网站 JSON 接口取得签名 EPUB 地址，失败后重试并切换备用线路；阅读支持左右分页动效和上下自由连续滑动。功能边界见 [设计方案](docs/design.md)，界面约定见 [页面设计](docs/pages.md)，模块职责、数据流和维护限制见 [架构与维护](docs/analysis/architecture.md)。

使用 Android SDK 35 和 JDK 17。完整验证命令为 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --no-daemon`；日常调试可运行 `:app:assembleDebug`。当前 Windows 测试进程无法从含中文的工程路径加载测试类，需要先建立英文路径目录联接，再从联接路径运行上述命令。当前工作区入口为 `F:\Workspace\zcomic-test-link`。

正式构建位于 `app/build/outputs/apk/release/app-release.apk`，开启 R8 压缩与资源裁剪；版本名为 `1.0.0`，内部版本号为 5。个人使用的 release 构建沿用此前 debug 包的本机签名，便于覆盖安装并保留数据；它不是面向应用商店分发的独立发布签名。签名密钥不随源码上传，在其他机器构建时会使用该机器的 debug 签名，无法直接覆盖本机签名的安装包。数据库 v1 升级到 v2 时增加文件内容指纹及在线卷册关联，不清空书架、任务或阅读记录；旧文件指纹在书架检查时补齐。

JVM 回归测试覆盖网站解析、排序入口、Cookie 生命周期、实际 HTTP 取消、续传请求头、EPUB 资源索引和点击/滑动方向。MediaStore/目录授权、数据库升级、图片解码与触控效果仍需 Android 15 真机验证；单元测试和构建成功不替代这些设备验证。

账号在应用内首次输入；后续尝试自动登录。账号密码不得写入源码、构建配置或 APK。登录态和必要凭据只保存在设备上，自动登录失败时提示重新输入。
