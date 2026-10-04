# zComic

个人使用的 Android 漫画应用，面向 Android 14 及以上设备。应用访问 [Kmoe](https://kxo.moe/)，检索漫画，下载单卷 EPUB 到 `Documents/zComic`，并在应用内离线阅读。两台个人设备各自保存账号会话、下载记录和阅读位置，不提供同步或分发。

应用包含书架、关键词检索与四种网站排序、卷册下载队列、本地 EPUB、PDF、MOBI 导入及阅读器。界面统一使用深色表面和橙色操作色。小米、红米与 POCO 设备使用带持续通知的 dataSync 前台下载服务，其他设备使用 Android 14+ 用户发起的数据传输任务（UIDT）；通知显示进度并可暂停全部，系统设置入口位于下载页和账号设置的“后台下载设置”。部分文件持久保存在应用私有目录，优先续传原线路，主线路故障时尝试备用线路且各自保留进度；阅读支持左右分页动效和上下自由连续滑动。本地格式范围与外部打开说明见 [本地文件指南](docs/local-formats.md)，HyperOS 设置与限制见 [后台下载指南](docs/background-downloads.md)，功能边界见 [设计方案](docs/design.md)，界面约定见 [页面设计](docs/pages.md)，模块职责、数据流和维护限制见 [架构与维护](docs/analysis/architecture.md)。

使用 Android SDK 35 和 JDK 17。完整验证命令为 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --no-daemon`；日常调试可运行 `:app:assembleDebug`。当前 Windows 测试进程无法从含中文的工程路径加载测试类，需要先建立英文路径目录联接，再从联接路径运行上述命令。当前工作区入口为 `F:\Workspace\zcomic-test-link`。

正式构建位于 `app/build/outputs/apk/release/app-release.apk`，开启 R8 压缩与资源裁剪；版本名为 `1.0.0`，内部版本号为 10。个人使用的 release 构建沿用此前 debug 包的本机签名，便于覆盖安装并保留数据；它不是面向应用商店分发的独立发布签名。签名密钥不随源码上传，在其他机器构建时会使用该机器的 debug 签名，无法直接覆盖本机签名的安装包。数据库 v1 升级到 v2 时增加文件内容指纹及在线卷册关联，不清空书架、任务或阅读记录；旧文件指纹在书架检查时补齐。

JVM 回归测试覆盖网站解析、Cookie/HTTP 取消、真实中断后续传、文件版本与范围校验、线路切换、旧缓存迁移、磁盘错误、队列暂停/恢复及 EPUB 和阅读导航；Robolectric SDK 34 测试覆盖 UIDT 和前台服务调度、大队列、系统取消、任务网络路由、中断原因保存、通知与唤醒锁释放、超时收尾及系统设置跳转回退。UIDT 请求使用任务分配的网络，前台服务使用应用正常网络；前台服务与遗留 UIDT 共用队列执行锁。下载页显示最近系统中断原因。Redmi K90 / HyperOS 3 的兼容服务效果和设置跳转仍需真机复测，其他设备的切换应用、锁屏、省电、系统回收及 MediaStore/目录授权也需设备验证；单元测试和构建成功不替代这些验证。

账号在应用内首次输入；后续尝试自动登录。账号密码不得写入源码、构建配置或 APK。登录态和必要凭据只保存在设备上，自动登录失败时提示重新输入。
