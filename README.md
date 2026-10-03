# zComic

个人使用的 Android 漫画应用，面向 Android 14 及以上设备。应用访问 [Kmoe](https://kxo.moe/)，检索漫画，下载单卷 EPUB 到 `Documents/zComic`，并在应用内离线阅读。两台个人设备各自保存账号会话、下载记录和阅读位置，不提供同步或分发。

应用包含书架、关键词检索与四种网站排序、卷册下载队列、本地 EPUB 导入及图片漫画阅读器。界面统一使用深色表面和橙色操作色。下载使用 Android 14+ 用户发起的数据传输任务（UIDT），切换应用或锁屏后由系统继续执行，通知显示进度并可暂停全部；部分文件持久保存在应用私有目录，优先续传原线路，主线路故障时尝试备用线路且各自保留进度；阅读支持左右分页动效和上下自由连续滑动。功能边界见 [设计方案](docs/design.md)，界面约定见 [页面设计](docs/pages.md)，模块职责、数据流和维护限制见 [架构与维护](docs/analysis/architecture.md)。

使用 Android SDK 35 和 JDK 17。完整验证命令为 `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --no-daemon`；日常调试可运行 `:app:assembleDebug`。当前 Windows 测试进程无法从含中文的工程路径加载测试类，需要先建立英文路径目录联接，再从联接路径运行上述命令。当前工作区入口为 `F:\Workspace\zcomic-test-link`。

正式构建位于 `app/build/outputs/apk/release/app-release.apk`，开启 R8 压缩与资源裁剪；版本名为 `1.0.0`，内部版本号为 7。个人使用的 release 构建沿用此前 debug 包的本机签名，便于覆盖安装并保留数据；它不是面向应用商店分发的独立发布签名。签名密钥不随源码上传，在其他机器构建时会使用该机器的 debug 签名，无法直接覆盖本机签名的安装包。数据库 v1 升级到 v2 时增加文件内容指纹及在线卷册关联，不清空书架、任务或阅读记录；旧文件指纹在书架检查时补齐。

JVM 回归测试覆盖网站解析、Cookie/HTTP 取消、真实中断后续传、文件版本与范围校验、线路切换、旧缓存迁移、磁盘错误、队列暂停/恢复及 EPUB 和阅读导航；Robolectric SDK 34 测试覆盖 UIDT 持久化调度、大队列与系统取消。切换应用、锁屏、省电模式、系统回收/重启、通知权限及 MediaStore/目录授权等行为仍需 Android 14/15 真机验证；单元测试和构建成功不替代这些设备验证。

账号在应用内首次输入；后续尝试自动登录。账号密码不得写入源码、构建配置或 APK。登录态和必要凭据只保存在设备上，自动登录失败时提示重新输入。
