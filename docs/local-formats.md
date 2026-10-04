# 本地文件导入与阅读

zComic 支持本地 EPUB、PDF 和无 DRM 的 MOBI7 文件。书架的“导入文件”会打开系统文件选择器，允许浏览全部文件，选定后根据内容识别实际格式；不会仅凭扩展名或提供器 MIME 类型接受文件。目录扫描按扩展名或已知 MIME 类型筛选，再校验内容。

导入先把内容暂存到应用私有缓存，校验、内容指纹与公开副本使用同一份数据，避免原文件变化。成功后按实际格式保存到 `Documents/zComic/<作品名>/<文件名>.epub|pdf|mobi`，原文件保留。同一内容复用书架条目，同名不同内容提示冲突，不覆盖。书架保存封面、页数和阅读位置；删除导入条目会删除应用副本；删除成功后同步清理 `Documents/zComic` 下对应的空作品目录，根目录保留，目录中仍有文件或子目录时不清理。系统拒绝目录清理时提示清理失败，已删除的卷册仍从书架移除。删除扫描条目保留来源文件及目录。

## 格式范围

| 格式 | 阅读方式 | 当前限制 |
| --- | --- | --- |
| EPUB | 按 spine 顺序读取图片页 | 面向图片漫画，不重排纯文字 EPUB；加密或不可解码图页报错 |
| PDF | Android PdfRenderer 按原页渲染 | 保留文字、扫描页和排版；不提供密码输入、OCR、文字选取或重排 |
| MOBI | MOBI7 HTML 中按顺序解析文字与嵌入图片 | 支持未压缩、PalmDOC 和 HUFF/CDIC；双格式 MOBI 使用 MOBI7 部分；不支持 DRM 和纯 KF8/AZW3 |

MOBI 文字以固定版心分页，保持字号及页码稳定，使用现有翻页、连续滚动和缩放；保留正文顺序与段落，复杂 HTML/CSS 样式、交互链接和目录导航不复刻 Kindle 排版。图片只从文件记录读取，不加载外部 URL。记录、解压正文及字典展开均设上限，损坏、循环引用或超限文件拒绝导入。PDF 和 MOBI 每页按需生成位图，最长边不超过 2048px，缓存上限 24MB；关闭阅读后释放归档、PDF 文件描述符与临时文件。

## 从文件管理器打开

在文件管理器中选中文件，点击“打开方式”并选择 zComic，应用会校验、导入并打开阅读器。系统关联接受 `application/epub+zip`、`application/pdf`、`application/x-mobipocket-ebook`、`application/vnd.amazon.mobi`；对于 `application/octet-stream`，仅当 URI 路径带 EPUB/PDF/MOBI 后缀时匹配。

部分文件管理器把 MOBI 标为通用二进制并使用无后缀的不透明 `content://` 地址，此时 Android 无法仅按显示文件名匹配应用，zComic 可能不出现在打开方式列表。可从应用内“导入文件”选择该文件；应用不会注册为所有二进制文件的默认打开工具。外部打开需要来源应用授予读取权限，导入完成后阅读应用副本，不依赖来源授权永久有效。

## 实现与验证参考

PDF 渲染遵循 [Android PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer) 的页面及描述符生命周期。MOBI 按 Palm 数据库、PalmDOC/MOBI 头、图片引用和压缩记录解析；格式资料见 [MOBI 格式字段](https://github.com/kovidgoyal/calibre/blob/master/format_docs/pdb/mobi.txt) 与 [libmobi](https://github.com/bfabiszewski/libmobi)。本应用使用 Kotlin 实现，没有包含这些项目的库或复制其源码。

JVM / Robolectric 测试覆盖 MOBI 图文顺序与三种解压、DRM/KF8/损坏文件拒绝、格式识别、EPUB 回归、原格式复制与文件管理器 MIME 匹配。Robolectric 的旧 Manifest 解析器忽略 pathSuffix，负例改用从真实 XML 构建的 Android IntentFilter 校验，不把错误的模拟关联当作设备行为。

PDF 页比例与颜色渲染、描述符关闭及安装后真实关联另由设备测试 `PdfBookDeviceTest` 覆盖；Windows 本机没有 Android 原生 PDF 引擎，构建设备测试包不代表已执行这些测试。连接设备后运行 `.\gradlew.bat :app:connectedDebugAndroidTest`。实际文件选择器、各厂商管理器授权和真机阅读表现仍需设备验证。
