package app.zcomic

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.view.WindowInsets as AndroidWindowInsets
import android.view.WindowInsetsController
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zcomic.data.ComicBook
import app.zcomic.data.BookReader
import app.zcomic.data.VolumeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class PageTurn(
    val from: Int,
    val to: Int,
    val forward: Boolean,
    val fromBitmap: Bitmap,
    val toBitmap: Bitmap
)

private data class DecodedPage(val page: Int, val bitmap: Bitmap)

@Composable
fun ReaderScreen(record: VolumeRecord, onPageChanged: (String, Int, Int) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val preferences = remember { context.getSharedPreferences("reader", android.content.Context.MODE_PRIVATE) }
    var book by remember(record.id) { mutableStateOf<ComicBook?>(null) }
    var error by remember(record.id) { mutableStateOf("") }
    var page by rememberSaveable(record.id) { mutableIntStateOf(record.page.coerceAtLeast(0)) }
    var toolbar by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(preferences.getString("mode", "从右往左") ?: "从右往左") }
    var fit by remember { mutableStateOf(preferences.getString("fit", "适应整页") ?: "适应整页") }
    var dark by remember { mutableStateOf(preferences.getBoolean("dark", true)) }
    val continuousState = rememberLazyListState(initialFirstVisibleItemIndex = page)
    val scope = rememberCoroutineScope()
    val turnProgress = remember { Animatable(0f) }
    var turn by remember { mutableStateOf<PageTurn?>(null) }
    var readyPage by remember(book) { mutableStateOf<DecodedPage?>(null) }
    var turning by remember { mutableStateOf(false) }
    var sliderPage by remember { mutableStateOf<Float?>(null) }
    var turnJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val latestOnPageChanged by rememberUpdatedState(onPageChanged)
    LaunchedEffect(mode, fit, dark) {
        preferences.edit().putString("mode", mode).putString("fit", fit).putBoolean("dark", dark)
            .apply()
    }
    val background = if (dark) Color.Black else Color(0xFFF4F3F1)
    LaunchedEffect(record.id, record.uri) {
        try {
            val opened = BookReader.open(context, Uri.parse(record.uri))
            try {
                page = page.coerceIn(0, opened.pages.lastIndex)
                book = opened
                latestOnPageChanged(record.id, page, opened.pages.size)
                awaitCancellation()
            } finally {
                book = null
                withContext(NonCancellable + Dispatchers.IO) { opened.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { error = exception.message ?: "无法读取文件" }
    }
    DisposableEffect(activity) {
        activity?.window?.insetsController?.hide(AndroidWindowInsets.Type.statusBars() or AndroidWindowInsets.Type.navigationBars())
        activity?.window?.insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.window?.insetsController?.show(AndroidWindowInsets.Type.statusBars() or AndroidWindowInsets.Type.navigationBars())
        }
    }
    val count = book?.pages?.size ?: 0
    val latestPosition by rememberUpdatedState(Triple(record.id, page, count))
    DisposableEffect(record.id) {
        onDispose {
            val (id, position, total) = latestPosition
            if (total > 0) latestOnPageChanged(id, position, total)
        }
    }
    LaunchedEffect(page, count, record.id) {
        if (count > 0) {
            delay(250)
            latestOnPageChanged(record.id, page, count)
        }
    }
    LaunchedEffect(mode) { turnJob?.cancel() }
    LaunchedEffect(mode, count, record.id) {
        if (mode == "上下连续" && count > 0) {
            val target = page.coerceIn(0, count - 1)
            if (continuousState.firstVisibleItemIndex != target) continuousState.scrollToItem(target)
            snapshotFlow { continuousState.firstVisibleItemIndex }.collect { visible ->
                if (page != visible) {
                    page = visible
                }
            }
        }
    }
    fun move(delta: Int) {
        if (count <= 0 || turning) return
        val target = (page + delta).coerceIn(0, count - 1)
        if (target == page) return
        val activeBook = book ?: return
        val startMode = mode
        turning = true
        turnJob = scope.launch {
            try {
                val fromPage = page
                val fromBitmap = readyPage?.takeIf { it.page == fromPage }?.bitmap
                    ?: withContext(Dispatchers.IO) { activeBook.bitmap(fromPage) }
                val toBitmap = withContext(Dispatchers.IO) { activeBook.bitmap(target) }
                if (fromBitmap == null || toBitmap == null) error("图片页无法解码")
                turnProgress.snapTo(0f)
                turn = PageTurn(fromPage, target, delta > 0, fromBitmap, toBitmap)
                turnProgress.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
                if (mode == startMode) {
                    // Keep the decoded target alive across removal of the animated overlay.
                    readyPage = DecodedPage(target, toBitmap)
                    page = target
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (exception: Exception) { error = exception.message ?: "图片页无法解码" }
            finally {
                turn = null
                turning = false
            }
        }
    }
    fun handleTap(x: Float, y: Float, width: Float, height: Float) {
        val action = readerTapAction(x, y, width, height, mode)
        when (action) {
            ReaderAction.TOOLBAR -> toolbar = !toolbar
            ReaderAction.NEXT, ReaderAction.PREVIOUS -> {
                val delta = if (action == ReaderAction.NEXT) 1 else -1
                if (mode == "上下连续") {
                    scope.launch { continuousState.animateScrollBy(delta * continuousState.layoutInfo.viewportSize.height * 0.85f) }
                } else move(delta)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(background)) {
        when {
            error.isNotBlank() -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = if (dark) Color.White else Color.Black)
                TextButton(onClick = onBack) { Text("返回书架") }
            }
            book == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            mode == "上下连续" -> {
                LazyColumn(state = continuousState, modifier = Modifier.fillMaxSize().pointerInput(mode, count) {
                    detectTapGestures { offset -> handleTap(offset.x, offset.y, size.width.toFloat(), size.height.toFloat()) }
                }) {
                    items(count, key = { it }) { index ->
                        ReaderImage(book!!, index, fit, Modifier.fillMaxWidth(), continuous = true)
                    }
                }
            }
            else -> {
                var drag by remember { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxSize().clipToBounds()
                    .pointerInput(mode, count) {
                        detectHorizontalDragGestures(onDragEnd = {
                            when (readerSwipeAction(drag, mode)) {
                                ReaderAction.NEXT -> move(1)
                                ReaderAction.PREVIOUS -> move(-1)
                                else -> Unit
                            }
                            drag = 0f
                        }, onDragCancel = { drag = 0f }) { _, amount -> drag += amount }
                    }
                    .pointerInput(mode, count) {
                        detectTapGestures { offset ->
                            handleTap(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        }
                    }) {
                    val activeTurn = turn
                    val progress = if (activeTurn == null) 1f else turnProgress.value
                    val renderedPage = activeTurn?.to ?: page
                    val offsets = readerTurnOffsets(progress, activeTurn?.forward ?: true, mode)
                    // The destination stays in the same composition slot during and after a turn.
                    ReaderImage(book!!, renderedPage, fit, Modifier.fillMaxSize().graphicsLayer {
                        translationX = size.width * offsets.incoming
                    }.background(background), preloaded = activeTurn?.toBitmap
                        ?: readyPage?.takeIf { it.page == renderedPage }?.bitmap,
                        onDecoded = { readyPage = DecodedPage(renderedPage, it) })
                    if (activeTurn != null) {
                        ReaderImage(book!!, activeTurn.from, fit, Modifier.fillMaxSize()
                            .graphicsLayer {
                                translationX = size.width * offsets.outgoing
                            }.background(background), preloaded = activeTurn.fromBitmap)
                    }
                }
            }
        }
        if (toolbar) {
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).background(MaterialTheme.colorScheme.surface.copy(alpha = .92f)).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("${record.comicTitle} · ${record.title}", modifier = Modifier.weight(1f), maxLines = 1, fontSize = 13.sp)
                IconButton(onClick = { options = true }) { Icon(Icons.Outlined.Settings, "阅读设置") }
            }
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface.copy(alpha = .92f)).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(16.dp)) {
                Text(if (count > 0) "第 ${(sliderPage ?: page.toFloat()).roundToInt() + 1} / $count 页" else "正在读取", fontSize = 12.sp)
                Slider(value = sliderPage ?: page.toFloat(), enabled = count > 1 && !turning, onValueChange = {
                    sliderPage = it
                }, onValueChangeFinished = {
                    val target = sliderPage?.roundToInt() ?: page
                    sliderPage = null
                    if (count > 0) {
                        page = target.coerceIn(0, count - 1)
                        if (mode == "上下连续") scope.launch { continuousState.scrollToItem(page) }
                        latestOnPageChanged(record.id, page, count)
                    }
                }, valueRange = 0f..(count - 1).coerceAtLeast(0).toFloat())
            }
        }
    }
    if (options) AlertDialog(onDismissRequest = { options = false }, title = { Text("阅读设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("翻页方式")
            listOf("从右往左", "从左往右", "上下连续").forEach { value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = mode == value, onClick = { mode = value })
                    Text(value, Modifier.padding(start = 5.dp))
                }
            }
            HorizontalDivider()
            listOf("适应整页", "适应宽度").forEach { value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = fit == value, onClick = { fit = value })
                    Text(value, Modifier.padding(start = 5.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(dark, { dark = it }); Text("深色背景", Modifier.padding(start = 9.dp)) }
        }
    }, confirmButton = { TextButton(onClick = { options = false }) { Text("完成") } })
}

@Composable
private fun ReaderImage(book: ComicBook, page: Int, fit: String, modifier: Modifier,
    continuous: Boolean = false, preloaded: Bitmap? = null, onDecoded: (Bitmap) -> Unit = {}) {
    var imageError by remember(book, page) { mutableStateOf(false) }
    val latestOnDecoded by rememberUpdatedState(onDecoded)
    // Preloaded pages render synchronously; never clear them in an asynchronous producer.
    val bitmap = preloaded ?: key(book, page) {
        val decoded by produceState<Bitmap?>(null, book, page) {
            imageError = false
            try {
                value = withContext(Dispatchers.IO) { book.bitmap(page) }
                imageError = value == null
                value?.let { latestOnDecoded(it) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { imageError = true }
        }
        decoded
    }
    var scale by remember(page) { mutableFloatStateOf(1f) }
    val ratio = book.pageRatios.getOrNull(page) ?: 0.72f
    Box(if (continuous) modifier.aspectRatio(ratio) else modifier, contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            if (imageError) Text("第 ${page + 1} 页无法读取", color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator(Modifier.size(28.dp))
        }
        bitmap?.let { image ->
            Image(image.asImageBitmap(), contentDescription = "第 ${page + 1} 页", modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .pointerInput(page) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zooming = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                zooming = true
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            }
                            if (zooming) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }, contentScale = if (fit == "适应宽度") ContentScale.FillWidth else ContentScale.Fit)
        }
    }
}
