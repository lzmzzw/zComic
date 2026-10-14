package app.zcomic

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.zcomic.data.ComicBook
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun ContinuousReader(book: ComicBook, listState: LazyListState, savedWidth: Float?,
    onTap: (Float, Float, Float, Float) -> Unit, onWidthSaved: (Float) -> Unit,
    onPositionRestored: (Int) -> Unit) {
    val adjustable = savedWidth != null
    var width by remember(book, adjustable) { mutableFloatStateOf(savedWidth ?: 1f) }
    var panX by remember(book, adjustable) { mutableFloatStateOf(0f) }
    var pinching by remember(book, adjustable) { mutableStateOf(false) }
    var beforeZoom by remember(book, adjustable) { mutableStateOf<ReaderScrollPosition?>(null) }
    val latestSavedWidth by rememberUpdatedState(savedWidth)
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnWidthSaved by rememberUpdatedState(onWidthSaved)
    val latestOnPositionRestored by rememberUpdatedState(onPositionRestored)
    val scope = rememberCoroutineScope()
    val ratios = remember(book) { List(book.pages.size) { book.pageRatios.getOrNull(it) ?: 0.72f } }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()
        .pointerInput(book, adjustable) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val startedZoomed = width > 1f
                var claimed = startedZoomed
                var pinched = false
                var moved = false
                var anchorIndex = listState.firstVisibleItemIndex
                var anchorOffset = listState.firstVisibleItemScrollOffset.toFloat()
                var completed = false
                if (startedZoomed) down.consume()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            if (!pinched) {
                                // Cancel the real scroll mutation, including a running fling.
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { listState.stopScroll(MutatePriority.PreventUserInput) }
                                anchorIndex = listState.firstVisibleItemIndex
                                anchorOffset = listState.firstVisibleItemScrollOffset.toFloat()
                                if (beforeZoom == null) beforeZoom = ReaderScrollPosition(anchorIndex, anchorOffset.toInt())
                            }
                            pinching = true
                            pinched = true
                            claimed = true
                        }
                        if (event.changes.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }) moved = true
                        if (claimed) {
                            val previous = event.calculateCentroid(useCurrent = false)
                            val current = event.calculateCentroid()
                            if (previous.x.isFinite() && previous.y.isFinite() && current.x.isFinite() && current.y.isFinite()) {
                                val oldWidth = width
                                width = if (adjustable) readerLandscapeGestureWidth(oldWidth, event.calculateZoom())
                                    else (oldWidth * event.calculateZoom()).coerceIn(1f, 5f)
                                val oldPixels = (size.width * oldWidth).roundToInt().coerceAtLeast(1)
                                val newPixels = (size.width * width).roundToInt().coerceAtLeast(1)
                                val factor = newPixels.toFloat() / oldPixels
                                panX = readerContinuousPanX(panX, previous.x, current.x, factor, size.width.toFloat(), width)
                                anchorOffset = readerZoomAnchorOffset(anchorOffset, previous.y, current.y, factor)
                                val target = readerContinuousPosition(ratios, anchorIndex, anchorOffset,
                                    newPixels.toFloat(), size.height.toFloat())
                                anchorOffset = target.anchorOffset
                                listState.requestScrollToItem(target.position.index, target.position.offset)
                            }
                            // Initial pass handles two fingers on different children before scrolling does.
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (startedZoomed && !pinched && !moved) {
                        width = latestSavedWidth ?: 1f
                        panX = 0f
                        beforeZoom?.let { position ->
                            listState.requestScrollToItem(position.index, position.offset)
                            latestOnPositionRestored(position.index)
                        }
                        beforeZoom = null
                    } else if (pinched && width <= 1f) {
                        if (adjustable) latestOnWidthSaved(readerLandscapeWidth(width))
                        panX = 0f
                        beforeZoom = null
                    }
                    completed = true
                } finally {
                    pinching = false
                    if (!completed) {
                        width = latestSavedWidth ?: 1f
                        panX = 0f
                        beforeZoom?.let { listState.requestScrollToItem(it.index, it.offset) }
                        beforeZoom = null
                    }
                }
            }
        }
        .pointerInput(book, adjustable) {
            detectTapGestures { offset -> latestOnTap(offset.x, offset.y, size.width.toFloat(), size.height.toFloat()) }
        }, contentAlignment = Alignment.TopCenter) {
        // Real column width changes page heights together; adjacent page edges remain joined.
        LazyColumn(state = listState, userScrollEnabled = !pinching && width <= 1f,
            modifier = Modifier.requiredWidth(maxWidth * width).fillMaxHeight().graphicsLayer { translationX = panX }) {
            items(book.pages.size, key = { it }) { index ->
                ReaderImage(book, index, "适应宽度", Modifier.fillMaxWidth(), continuous = true)
            }
        }
    }
}
