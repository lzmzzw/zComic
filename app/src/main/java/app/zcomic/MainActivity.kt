package app.zcomic

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import app.zcomic.ui.Cover
import app.zcomic.ui.EmptyState
import app.zcomic.ui.Header
import app.zcomic.ui.theme.ComicTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.zcomic.data.*

private val onlineComicSaver = listSaver<OnlineComic?, String>(
    save = { comic -> comic?.let { listOf(it.id, it.title, it.coverUrl, it.detailUrl) } ?: emptyList() },
    restore = { fields -> if (fields.size == 4) OnlineComic(fields[0], fields[1], fields[2], fields[3]) else null }
)

class MainActivity : ComponentActivity() {
    private var openUri by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openUri = if (savedInstanceState != null) savedInstanceState.getString("pendingOpenUri")?.let(Uri::parse)
            else intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent { ComicApp(incoming = openUri, consumed = { openUri = null }) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingOpenUri", openUri?.toString())
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUri = intent.takeIf { it.action == Intent.ACTION_VIEW }?.data
    }
}

@Composable
private fun ComicApp(incoming: Uri?, consumed: () -> Unit, vm: ComicViewModel = viewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(vm, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.restoreDownloads()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.restoreDownloads()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val books by vm.volumes.collectAsState()
    val downloadLastStop by vm.downloadLastStop.collectAsState()
    val volumesLoaded by vm.volumesLoaded.collectAsState()
    val tasks by vm.downloads.collectAsState()
    val online by vm.online.collectAsState()
    val selectedSort by vm.selectedSort.collectAsState()
    val detail by vm.detail.collectAsState()
    val loginBusy by vm.loginBusy.collectAsState()
    val browseBusy by vm.browseBusy.collectAsState()
    val detailBusy by vm.detailBusy.collectAsState()
    val message by vm.message.collectAsState()
    val login by vm.loginStatus.collectAsState()
    val savedCredentials by vm.savedCredentials.collectAsState()
    val imported by vm.importedVolume.collectAsState()
    var tab by rememberSaveable { mutableStateOf("书架") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedComic by rememberSaveable(stateSaver = onlineComicSaver) { mutableStateOf<OnlineComic?>(null) }
    val selectedComicId = selectedComic?.id
    var readerId by rememberSaveable { mutableStateOf<String?>(null) }
    val reader = remember(books, readerId, imported) { books.firstOrNull { it.id == readerId } ?: imported?.takeIf { it.id == readerId } }
    val matchingDetail = detail?.takeIf { it.comic.id == selectedComicId }
    val groupBooks = remember(books, selectedGroup) { books.filter { it.comicId == selectedGroup } }
    var settings by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::import) }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::scan) }
    LaunchedEffect(incoming) { incoming?.let { vm.import(it); consumed() } }
    LaunchedEffect(imported?.id) {
        imported?.let { readerId = it.id; vm.consumeImportedVolume() }
    }
    LaunchedEffect(tab) { if (tab == "书架") vm.checkFiles() else if (tab == "发现" && online.isEmpty()) vm.browse(ComicSort.COMPREHENSIVE) }
    LaunchedEffect(selectedComicId) {
        selectedComic?.let { selected ->
            if (detail?.comic?.id != selected.id) vm.openDetail(online.firstOrNull { it.id == selected.id } ?: selected)
        }
    }
    BackHandler(readerId != null || selectedGroup != null || selectedComicId != null) {
        when {
            readerId != null -> readerId = null
            selectedGroup != null -> selectedGroup = null
            selectedComicId != null -> selectedComic = null
        }
    }
    ComicTheme {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            if (readerId == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf("书架", "发现", "下载").forEach { item ->
                    NavigationBarItem(selected = tab == item, onClick = {
                        tab = item; selectedGroup = null; selectedComic = null
                    }, icon = { Icon(when(item) { "书架" -> Icons.Outlined.LocalLibrary; "发现" -> Icons.Outlined.Explore; else -> Icons.Outlined.Download }, item) }, label = { Text(item) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ))
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().then(if (readerId == null) Modifier.padding(padding) else Modifier)) {
                when {
                    readerId != null -> if (reader != null) key(reader.id) {
                        ReaderScreen(reader, onPageChanged = vm::updatePage, onBack = { readerId = null })
                    }
                        else if (!volumesLoaded) CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else Column(Modifier.align(Alignment.Center)) {
                            Text("该卷册已不在书架中")
                            TextButton(onClick = { readerId = null }) { Text("返回书架") }
                        }
                    selectedGroup != null -> GroupScreen(groupBooks,
                        onBack = { selectedGroup = null }, onRead = { readerId = it.id }, onDelete = vm::deleteVolume)
                    selectedComicId != null -> DetailScreen(matchingDetail, books, tasks, detailBusy,
                        onBack = { selectedComic = null }, onQueue = { selected ->
                            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            vm.queue(selected)
                        })
                    tab == "书架" -> ShelfScreen(books, onGroup = { selectedGroup = it },
                        onImport = { import.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                        onScan = { scan.launch(null) }, onSettings = { settings = true }, onDeleteBook = vm::deleteBook)
                    tab == "发现" -> DiscoverScreen(online, query, selectedSort, browseBusy, onQuery = { query = it },
                        onSearch = { vm.search(query) }, onSort = vm::browse,
                        onComic = { selectedComic = it })
                    else -> DownloadsScreen(tasks, downloadLastStop, onPause = vm::pause, onResume = vm::resume,
                        onCancel = vm::cancel, onClear = vm::clearCompleted)
                }
                if (message.isNotBlank()) {
                    LaunchedEffect(message) { kotlinx.coroutines.delay(3600); vm.dismissMessage(message) }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small, shadowElevation = 8.dp) {
                        Text(message, Modifier.padding(14.dp), fontSize = 13.sp)
                    }
                }
            }
        }
        if (settings) SettingsDialog(login, savedCredentials, message, loginBusy, onDismiss = { settings = false }, onLogin = vm::login, onLogout = vm::logout)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ShelfScreen(books: List<VolumeRecord>, onGroup: (String) -> Unit,
    onImport: () -> Unit, onScan: () -> Unit, onSettings: () -> Unit, onDeleteBook: (List<VolumeRecord>) -> Unit) {
    val groups = remember(books) { books.groupBy { it.comicId }.values.sortedWith(
        compareByDescending<List<VolumeRecord>> { group -> group.maxOf { it.lastReadAt } }
            .thenByDescending { group -> group.maxOf { it.addedAt } }
    ) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<List<VolumeRecord>?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("书架", actionModifier = Modifier.offset(x = 12.dp)) {
            Box {
                IconButton(onClick = { addMenuExpanded = true }) { Icon(Icons.Outlined.Add, "添加") }
                DropdownMenu(expanded = addMenuExpanded, onDismissRequest = { addMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("导入 EPUB") },
                        leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                        onClick = { addMenuExpanded = false; onImport() }
                    )
                    DropdownMenuItem(
                        text = { Text("扫描文件夹") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
                        onClick = { addMenuExpanded = false; onScan() }
                    )
                }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Tune, "设置和登录") }
        }
        Spacer(Modifier.height(12.dp))
        if (groups.isEmpty()) {
            EmptyState("书架还没有 EPUB", "从本地导入，或登录后在发现页下载")
            return@Column
        }
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(groups, key = { it.first().comicId }) { group ->
                val first = group.minBy { it.number }
                Column(Modifier.combinedClickable(onClick = { onGroup(first.comicId) }, onLongClick = { deletingGroup = group })) {
                    Cover(first.coverUri, Modifier.fillMaxWidth().aspectRatio(.72f))
                    Spacer(Modifier.height(7.dp))
                    Text(first.comicTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("已下载 ${group.size} 卷", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    val active = group.maxByOrNull { it.lastReadAt }
                    if (active != null && active.lastReadAt > 0) {
                        Text("最近阅读 ${active.title}", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                        LinearProgressIndicator(progress = { ((active.page + 1f) / active.pageCount.coerceAtLeast(1)).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 5.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    deletingGroup?.let { group ->
        AlertDialog(onDismissRequest = { deletingGroup = null },
            title = { Text("删除 ${group.first().comicTitle}？") },
            text = { Text("将移除这本书的 ${group.size} 卷及其阅读位置。应用保存的副本同时删除，扫描来源文件保留。") },
            confirmButton = { TextButton(onClick = { onDeleteBook(group); deletingGroup = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingGroup = null }) { Text("取消") } })
    }
}

@Composable
private fun GroupScreen(books: List<VolumeRecord>, onBack: () -> Unit,
    onRead: (VolumeRecord) -> Unit, onDelete: (VolumeRecord) -> Unit) {
    var deleting by remember { mutableStateOf<VolumeRecord?>(null) }
    val grouped = remember(books) { books.groupBy { it.comicTitle }.mapValues { (_, volumes) ->
        volumes.sortedWith(compareBy<VolumeRecord> { it.number }.thenBy { it.title })
    } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("卷册") { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架") } }
        LazyColumn {
            grouped.forEach { (title, volumes) ->
                item(key = "heading-$title") {
                    Text(title, Modifier.padding(top = 12.dp, bottom = 10.dp), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                }
                items(volumes, key = { it.id }) { volume ->
                    Row(Modifier.fillMaxWidth().clickable { onRead(volume) }.heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d".format(volume.number), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.width(35.dp))
                        Column(Modifier.weight(1f)) {
                            Text(volume.title, fontSize = 14.sp)
                            Text(if (volume.lastReadAt == 0L) "未读" else "第 ${volume.page + 1} / ${volume.pageCount} 页",
                                color = if (volume.lastReadAt == 0L) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                        }
                        IconButton(onClick = { deleting = volume }) { Icon(Icons.Outlined.DeleteOutline, "删除 ${volume.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    deleting?.let { volume ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${volume.title}？") },
            text = { Text("将移除该卷和阅读位置。应用保存的副本同时删除，扫描来源文件保留。") },
            confirmButton = { TextButton(onClick = { onDelete(volume); deleting = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun DiscoverScreen(comics: List<OnlineComic>, query: String, selectedSort: ComicSort?, busy: Boolean, onQuery: (String) -> Unit,
    onSearch: () -> Unit, onSort: (ComicSort) -> Unit, onComic: (OnlineComic) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("发现")
        Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(query, onQuery, singleLine = true,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics { contentDescription = "搜索漫画" },
                    textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }))
                IconButton(onClick = onSearch) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "搜索") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ComicSort.entries.forEach { sort ->
                Surface(onClick = { onSort(sort) }, modifier = Modifier.weight(1f).heightIn(min = 36.dp)
                    .semantics { selected = selectedSort == sort },
                    shape = MaterialTheme.shapes.small,
                    color = if (selectedSort == sort) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = if (selectedSort == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) {
                    Box(Modifier.padding(horizontal = 2.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(sort.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (comics.isEmpty() && !busy) EmptyState("暂无作品", "请稍后重试")
        LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(comics, key = { it.id }) { comic ->
                Column(Modifier.clickable { onComic(comic) }) {
                    Cover(comic.coverUrl, Modifier.fillMaxWidth().aspectRatio(.72f))
                    Text(comic.title, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(detail: ComicDetail?, books: List<VolumeRecord>, tasks: List<DownloadRecord>, busy: Boolean,
    onBack: () -> Unit, onQueue: (List<OnlineVolume>) -> Unit) {
    var selected by remember(detail?.comic?.id) { mutableStateOf(setOf<String>()) }
    val downloadedIds = remember(books) { books.flatMap { listOf(it.id, it.sourceId) }.filter { it.isNotBlank() }.toSet() }
    val queuedIds = remember(tasks) { tasks.filter { it.status !in listOf("failed", "completed") }.mapTo(mutableSetOf()) { it.id } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("漫画详情") { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (detail == null) {
            if (busy) EmptyState("正在读取卷册", "请稍候")
            else EmptyState("无法读取卷册", "请返回后重试")
            return@Column
        }
        Row(verticalAlignment = Alignment.Top) {
            Cover(detail.comic.coverUrl, Modifier.width(94.dp).height(138.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(detail.comic.title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("EPUB · ${detail.volumes.size} 卷", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text(detail.description, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Text("选择卷册", Modifier.padding(top = 20.dp, bottom = 8.dp), fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f)) {
            items(detail.volumes, key = { it.id }) { volume ->
                val downloaded = volume.id in downloadedIds
                val queued = volume.id in queuedIds
                Row(Modifier.fillMaxWidth().clickable(enabled = !downloaded && !queued) {
                    selected = if (volume.id in selected) selected - volume.id else selected + volume.id
                }.heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = volume.id in selected || downloaded, enabled = !downloaded && !queued,
                        onCheckedChange = { selected = if (it) selected + volume.id else selected - volume.id })
                    Column(Modifier.weight(1f)) {
                        Text(volume.title, fontSize = 14.sp)
                        Text(volume.size, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Text(when { downloaded -> "已下载"; queued -> "已排队"; else -> "" }, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已选 ${selected.size} 卷")
            Button(onClick = { onQueue(detail.volumes.filter { it.id in selected }); selected = emptySet() }) { Text("加入下载") }
        }
    }
}

@Composable
private fun DownloadsScreen(tasks: List<DownloadRecord>, lastStop: String, onPause: (String) -> Unit, onResume: (String) -> Unit,
    onCancel: (String) -> Unit, onClear: () -> Unit) {
    var backgroundSettings by rememberSaveable { mutableStateOf(false) }
    if (backgroundSettings) BackgroundDownloadDialog { backgroundSettings = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("下载", actionModifier = Modifier.offset(x = 12.dp)) {
            Row {
                IconButton(onClick = { backgroundSettings = true }) { Icon(Icons.Outlined.Settings, "后台下载设置") }
                IconButton(onClick = onClear) { Icon(Icons.Outlined.PlaylistRemove, "清除已完成记录") }
            }
        }
        if (lastStop.isNotBlank()) Text("最近中断：$lastStop", fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tasks.isEmpty()) EmptyState("没有下载任务", "在漫画详情页选择卷册加入队列")
        LazyColumn(Modifier.padding(top = 14.dp)) {
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${task.comicTitle} · ${task.volumeTitle}", modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(when(task.status) { "running" -> "下载中"; "queued" -> "等待中"; "paused" -> "已暂停";
                            "completed" -> "已完成"; else -> "失败" }, color = when(task.status) {
                            "failed" -> MaterialTheme.colorScheme.error; "completed" -> MaterialTheme.colorScheme.secondary;
                            "paused", "queued" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }, fontSize = 11.sp)
                    }
                    Text(if (task.error.isNotBlank()) task.error else if (task.total > 0) "${task.received / 1048576} / ${task.total / 1048576} MB" else task.status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    if (task.total > 0 && task.status != "completed") LinearProgressIndicator(
                        progress = { downloadProgress(task.received, task.total) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
                    Row {
                        if (task.status == "running" || task.status == "queued") TextButton(onClick = { onPause(task.id) }) { Text("暂停") }
                        if (task.status == "paused" || task.status == "failed") TextButton(onClick = { onResume(task.id) }) { Text("继续 / 重试") }
                        if (task.status != "completed") TextButton(onClick = { onCancel(task.id) }) { Text("取消") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SettingsDialog(login: String, savedCredentials: Pair<String, String>?, message: String, busy: Boolean, onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit, onLogout: () -> Unit) {
    var backgroundSettings by rememberSaveable { mutableStateOf(false) }
    if (backgroundSettings) BackgroundDownloadDialog { backgroundSettings = false }
    var email by remember(savedCredentials) { mutableStateOf(savedCredentials?.first.orEmpty()) }
    var password by remember(savedCredentials) { mutableStateOf(savedCredentials?.second.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), title = { Text("账号与设置") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("状态：$login", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            if (login == "登录失败" && message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            OutlinedTextField(email, { email = it }, label = { Text("账号邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false))
            OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, autoCorrectEnabled = false))
            TextButton(onClick = { onLogout(); onDismiss() }) { Text("退出登录") }
            TextButton(onClick = { backgroundSettings = true }) { Text("后台下载设置") }
        }
    }, confirmButton = { TextButton(onClick = { onLogin(email.trim(), password) }, enabled = !busy && email.isNotBlank() && password.isNotBlank()) { Text("登录") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
