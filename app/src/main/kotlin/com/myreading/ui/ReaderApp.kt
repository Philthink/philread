package com.myreading.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myreading.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

data class ReaderUiState(
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val book: Book? = null,
    val chapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val fontSize: Float = 20f,
    val lineHeight: Float = 1.25f,
    val columnGap: Float = 18f,
    val pageWidth: Float = 360f,
    val pageHeight: Float = 640f,
    val bookmarks: Set<ReadingPosition> = emptySet()
) {
    val layoutSettings: LayoutSettings
        get() = LayoutSettings(
            fontSize = fontSize,
            lineHeight = lineHeight,
            columnGap = columnGap,
            pageWidth = pageWidth,
            pageHeight = pageHeight
        )
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    var state by mutableStateOf(ReaderUiState())
        private set

    private val parser = EpubParser()
    private val layoutEngine = VerticalLayoutEngine(LayoutCache(16))

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(application) as T
            }
        }
    }

    fun openEpub(uri: Uri) {
        state = state.copy(loading = true, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = copyToTempFile(getApplication(), uri)
                val book = parser.parse(file)
                require(book.chapters.isNotEmpty()) { "EPUB 中没有可阅读章节" }
                val initialChapterIndex = book.initialReadableChapterIndex()
                withContext(Dispatchers.Main) {
                    state = state.copy(
                        loading = false,
                        errorMessage = null,
                        book = book,
                        chapterIndex = initialChapterIndex,
                        pageIndex = 0,
                        bookmarks = loadBookmarks(book)
                    )
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    state = state.copy(loading = false, errorMessage = readableImportError(error))
                }
            }
        }
    }

    fun nextPage() {
        val book = state.book ?: return
        val position = ReadingPosition(state.chapterIndex, state.pageIndex).next(book.chapters.size) {
            pagesForChapter(it).size
        }
        state = state.copy(chapterIndex = position.chapterIndex, pageIndex = position.pageIndex)
    }

    fun previousPage() {
        if (state.book == null) return
        val position = ReadingPosition(state.chapterIndex, state.pageIndex).previous {
            pagesForChapter(it).size
        }
        state = state.copy(chapterIndex = position.chapterIndex, pageIndex = position.pageIndex)
    }

    fun selectChapter(index: Int) {
        val book = state.book ?: return
        if (index in book.chapters.indices) {
            state = state.copy(chapterIndex = index, pageIndex = 0)
        }
    }

    fun selectBookmark(position: ReadingPosition) {
        val book = state.book ?: return
        if (position.chapterIndex !in book.chapters.indices) return
        val lastPageIndex = pagesForChapter(position.chapterIndex).lastIndex
        if (lastPageIndex < 0) return
        state = state.copy(
            chapterIndex = position.chapterIndex,
            pageIndex = position.pageIndex.coerceIn(0, lastPageIndex)
        )
    }

    fun addCurrentBookmark() {
        if (currentPages().isEmpty()) return
        val bookmark = ReadingPosition(state.chapterIndex, state.pageIndex)
        if (bookmark in state.bookmarks) return
        state = state.copy(bookmarks = state.bookmarks + bookmark)
        saveBookmarks()
    }

    fun removeCurrentBookmark() {
        val bookmark = ReadingPosition(state.chapterIndex, state.pageIndex)
        if (bookmark !in state.bookmarks) return
        state = state.copy(bookmarks = state.bookmarks - bookmark)
        saveBookmarks()
    }

    fun increaseFont() = state.let { state = it.copy(fontSize = (it.fontSize + 1f).coerceAtMost(40f), pageIndex = 0) }
    fun decreaseFont() = state.let { state = it.copy(fontSize = (it.fontSize - 1f).coerceAtLeast(12f), pageIndex = 0) }

    fun updateViewport(width: Float, height: Float) {
        if (state.pageWidth != width || state.pageHeight != height) {
            state = state.copy(pageWidth = width, pageHeight = height)
            val lastPageIndex = currentPages().lastIndex.coerceAtLeast(0)
            if (state.pageIndex > lastPageIndex) {
                state = state.copy(pageIndex = lastPageIndex)
            }
        }
    }

    fun currentPages(): List<PageLayout> {
        return pagesForChapter(state.chapterIndex)
    }

    private fun pagesForChapter(chapterIndex: Int): List<PageLayout> {
        val book = state.book ?: return emptyList()
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return emptyList()
        val stylesheet = extractStylesheet(book, chapter)
        return layoutEngine.paginate(chapter, stylesheet, state.layoutSettings).pages
    }

    fun currentPage(): PageLayout? = currentPages().getOrNull(state.pageIndex)

    fun chapterTitle(): String = state.book?.chapters?.getOrNull(state.chapterIndex)?.title ?: "未打开书籍"

    fun chapterCount(): Int = state.book?.chapters?.size ?: 0

    fun openDemo() {
        val demo = DemoBookFactory.sampleBook()
        state = state.copy(
            loading = false,
            errorMessage = null,
            book = demo,
            chapterIndex = 0,
            pageIndex = 0,
            bookmarks = loadBookmarks(demo)
        )
    }

    private fun bookmarkStorageKey(book: Book): String {
        val identity = listOfNotNull(
            book.metadata.identifier,
            book.metadata.title,
            book.metadata.creator
        ).joinToString("|").ifBlank { book.sourcePath }
        return "bookmarks-${identity.hashCode().toUInt().toString(16)}"
    }

    private fun loadBookmarks(book: Book): Set<ReadingPosition> {
        val serialized = getApplication<Application>()
            .getSharedPreferences("reader-bookmarks", Context.MODE_PRIVATE)
            .getString(bookmarkStorageKey(book), "")
            .orEmpty()
        return serialized.split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            val chapterIndex = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val pageIndex = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            ReadingPosition(chapterIndex, pageIndex)
        }.filter { it.chapterIndex in book.chapters.indices && it.pageIndex >= 0 }.toSet()
    }

    private fun saveBookmarks() {
        val book = state.book ?: return
        val serialized = state.bookmarks
            .sortedWith(compareBy(ReadingPosition::chapterIndex, ReadingPosition::pageIndex))
            .joinToString(";") { "${it.chapterIndex}:${it.pageIndex}" }
        getApplication<Application>()
            .getSharedPreferences("reader-bookmarks", Context.MODE_PRIVATE)
            .edit()
            .putString(bookmarkStorageKey(book), serialized)
            .apply()
    }

    private fun extractStylesheet(book: Book, chapter: Chapter): Stylesheet {
        val refs = chapter.referencedResourceHrefs
        val css = refs.mapNotNull { book.resourcesByHref[it] }
            .firstOrNull { it.type == ResourceType.CSS }
            ?.readText()
            .orEmpty()
        return CssParser.parse(css, chapter.href)
    }

    private fun copyToTempFile(context: Context, uri: Uri): File {
        val output = File.createTempFile("myreading-", ".epub", context.cacheDir)
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Unable to open EPUB URI" }
        input.use {
            output.outputStream().use { outputStream ->
                it.copyTo(outputStream)
            }
        }
        return output
    }

    private fun readableImportError(error: Throwable): String {
        val detail = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .lastOrNull()
        return if (detail == null) "打开 EPUB 失败" else "打开 EPUB 失败：$detail"
    }
}

private val EPUB_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/zip",
    "application/octet-stream"
)

@Composable
fun ReaderApp(
    externalEpubUri: Uri? = null,
    onExternalEpubConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.factory(context.applicationContext as Application))
    val state = viewModel.state
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.openEpub(uri)
        }
    }

    LaunchedEffect(externalEpubUri) {
        externalEpubUri?.let { uri ->
            viewModel.openEpub(uri)
            onExternalEpubConsumed()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (state.book == null) {
                EmptyLibraryScreen(
                    loading = state.loading,
                    errorMessage = state.errorMessage,
                    onOpen = { openDocument.launch(EPUB_MIME_TYPES) },
                    onOpenDemo = viewModel::openDemo
                )
            } else {
                val pages = viewModel.currentPages()
                ReaderScreen(
                    state = state,
                    chapterTitle = viewModel.chapterTitle(),
                    chapterTitles = state.book.chapters.map { it.title },
                    pageCount = pages.size,
                    page = pages.getOrNull(state.pageIndex),
                    onOpen = { openDocument.launch(EPUB_MIME_TYPES) },
                    onPreviousPage = viewModel::previousPage,
                    onNextPage = viewModel::nextPage,
                    onPreviousChapter = { viewModel.selectChapter((state.chapterIndex - 1).coerceAtLeast(0)) },
                    onNextChapter = {
                        val lastIndex = (viewModel.chapterCount() - 1).coerceAtLeast(0)
                        viewModel.selectChapter((state.chapterIndex + 1).coerceAtMost(lastIndex))
                    },
                    onSelectChapter = viewModel::selectChapter,
                    onSelectBookmark = viewModel::selectBookmark,
                    onAddBookmark = viewModel::addCurrentBookmark,
                    onRemoveBookmark = viewModel::removeCurrentBookmark,
                    onFontPlus = viewModel::increaseFont,
                    onFontMinus = viewModel::decreaseFont,
                    onViewportChanged = viewModel::updateViewport
                )
            }
        }
    }
}

@Composable
private fun EmptyLibraryScreen(loading: Boolean, errorMessage: String?, onOpen: () -> Unit, onOpenDemo: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF4F0E8)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("古籍阅读器", style = MaterialTheme.typography.headlineMedium)
            Text("加载 EPUB3，竖排原生分页，不使用 WebView 旋转。")
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (loading) {
                CircularProgressIndicator()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpen) { Text("打开 EPUB") }
                OutlinedButton(onClick = onOpenDemo) { Text("加载示例") }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ReaderScreen(
    state: ReaderUiState,
    chapterTitle: String,
    chapterTitles: List<String>,
    pageCount: Int,
    page: PageLayout?,
    onOpen: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onSelectBookmark: (ReadingPosition) -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: () -> Unit,
    onFontPlus: () -> Unit,
    onFontMinus: () -> Unit,
    onViewportChanged: (Float, Float) -> Unit
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var controlsActivity by remember { mutableIntStateOf(0) }
    var showContents by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    val swipeThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val currentPosition = ReadingPosition(state.chapterIndex, state.pageIndex)
    val currentBookmarked = currentPosition in state.bookmarks

    fun showReaderControls() {
        controlsVisible = true
        controlsActivity += 1
    }

    LaunchedEffect(controlsVisible, controlsActivity, state.chapterIndex, state.pageIndex) {
        if (controlsVisible) {
            delay(10_000)
            controlsVisible = false
        }
    }

    if (showContents) {
        ContentsDialog(
            chapterTitles = chapterTitles,
            currentChapterIndex = state.chapterIndex,
            onSelect = { index ->
                onSelectChapter(index)
                showContents = false
            },
            onDismiss = { showContents = false }
        )
    }

    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = state.bookmarks,
            chapterTitles = chapterTitles,
            onSelect = { bookmark ->
                onSelectBookmark(bookmark)
                showBookmarks = false
            },
            onDismiss = { showBookmarks = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F3EA))) {
        TopAppBar(
            title = { Text(chapterTitle) },
            actions = {
                TextButton(onClick = {
                    if (controlsVisible) controlsVisible = false else showReaderControls()
                }) {
                    Text(if (controlsVisible) "隐藏控件" else "显示控件")
                }
                TextButton(onClick = onOpen) { Text("打开") }
                TextButton(onClick = onFontMinus) { Text("A-") }
                TextButton(onClick = onFontPlus) { Text("A+") }
            }
        )
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (controlsVisible) {
                Column(
                    modifier = Modifier.width(120.dp).fillMaxHeight().background(Color(0xFFF0E4D2)),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("章节", style = MaterialTheme.typography.titleMedium)
                        Text("${state.chapterIndex + 1} / ${maxOf(chapterTitles.size, 1)}")
                        Spacer(Modifier.height(8.dp))
                        Text("页面", style = MaterialTheme.typography.titleMedium)
                        Text(if (pageCount == 0) "0 / 0" else "${state.pageIndex + 1} / $pageCount")
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showContents = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("目录") }
                        OutlinedButton(
                            onClick = { showBookmarks = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("书签 ${state.bookmarks.size}") }
                        Text(
                            if (currentBookmarked) "本页已书签" else "本页未书签",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPreviousChapter, modifier = Modifier.fillMaxWidth()) { Text("上一章") }
                        OutlinedButton(onClick = onNextChapter, modifier = Modifier.fillMaxWidth()) { Text("下一章") }
                    }
                }
            }
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)
                    .background(Color.White)
                    .pointerInput(state.chapterIndex, state.pageIndex) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var deltaX = 0f
                            var deltaY = 0f
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                deltaX += change.position.x - change.previousPosition.x
                                deltaY += change.position.y - change.previousPosition.y
                                if (abs(deltaX) > viewConfiguration.touchSlop || abs(deltaY) > viewConfiguration.touchSlop) {
                                    change.consume()
                                }
                                pressed = change.pressed
                            }

                            when {
                                abs(deltaY) >= swipeThreshold && abs(deltaY) > abs(deltaX) -> {
                                    if (deltaY > 0f) onAddBookmark() else onRemoveBookmark()
                                    showReaderControls()
                                }
                                abs(deltaX) <= viewConfiguration.touchSlop && abs(deltaY) <= viewConfiguration.touchSlop -> {
                                    val offset = down.position
                                    when {
                                        offset.x < size.width / 3f -> onPreviousPage()
                                        offset.x > size.width * 2f / 3f -> onNextPage()
                                        else -> showReaderControls()
                                    }
                                }
                            }
                        }
                    }
            ) {
                val density = LocalDensity.current
                LaunchedEffect(maxWidth, maxHeight) {
                    with(density) {
                        onViewportChanged(maxWidth.toPx(), maxHeight.toPx())
                    }
                }
                if (page == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有可显示页面") }
                } else {
                    VerticalPageCanvas(page = page, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(80.dp).background(Color(0xFFF8F3EA))
        ) {
            if (controlsVisible) {
                BottomAppBar(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (currentBookmarked) "本页已书签 · 上拉取消" else "下拉添加书签 · 点击中间显示控件")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onPreviousPage) { Text("上一页") }
                            OutlinedButton(onClick = onNextPage) { Text("下一页") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentsDialog(
    chapterTitles: List<String>,
    currentChapterIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("目录") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(chapterTitles) { index, title ->
                    TextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${index + 1}. ${title.ifBlank { "未命名章节" }}",
                            color = if (index == currentChapterIndex) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun BookmarksDialog(
    bookmarks: Set<ReadingPosition>,
    chapterTitles: List<String>,
    onSelect: (ReadingPosition) -> Unit,
    onDismiss: () -> Unit
) {
    val orderedBookmarks = remember(bookmarks) {
        bookmarks.sortedWith(compareBy(ReadingPosition::chapterIndex, ReadingPosition::pageIndex))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书签") },
        text = {
            if (orderedBookmarks.isEmpty()) {
                Text("暂无书签。在正文中下拉可添加当前页书签。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    itemsIndexed(orderedBookmarks) { _, bookmark ->
                        val chapterTitle = chapterTitles.getOrNull(bookmark.chapterIndex).orEmpty()
                        TextButton(onClick = { onSelect(bookmark) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "${bookmark.chapterIndex + 1}. ${chapterTitle.ifBlank { "未命名章节" }} · 第 ${bookmark.pageIndex + 1} 页",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun VerticalPageCanvas(
    page: PageLayout,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        drawRect(Color(0xFFFFFCF7))
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.LEFT
        }
        fun drawNativeText(text: String, x: Float, y: Float, fontSize: Float) {
            if (text.isBlank()) return
            paint.textSize = fontSize
            val baseline = y - paint.ascent()
            drawContext.canvas.nativeCanvas.drawText(text, x, baseline, paint)
        }
        page.columns.forEach { column ->
            column.fragments.forEach { fragment ->
                when (fragment) {
                    is LayoutFragment.Text -> drawNativeText(fragment.displayText, fragment.x, fragment.y, fragment.fontSize)
                    is LayoutFragment.Ruby -> {
                        fragment.baseFragments.forEach { baseFragment ->
                            drawNativeText(baseFragment.displayText, baseFragment.x, baseFragment.y, baseFragment.fontSize)
                        }
                        if (fragment.annotationDisplayText.isNotBlank()) {
                            drawNativeText(fragment.annotationDisplayText, fragment.annotationX, fragment.annotationY, fragment.annotationFontSize)
                        }
                    }
                    is LayoutFragment.Image -> {
                        drawRect(
                            color = Color(0xFFE0D8CC),
                            topLeft = Offset(fragment.x, fragment.y),
                            size = androidx.compose.ui.geometry.Size(fragment.width, fragment.height)
                        )
                    }
                }
            }
        }
    }
}

object DemoBookFactory {
    fun sampleBook(): Book {
        val chapter = Chapter(
            id = "chap1",
            href = "chapter1.xhtml",
            title = "第一章",
            order = 0,
            linear = true,
            content = """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head>
                    <title>第一章</title>
                    <style>
                      body { writing-mode: vertical-rl; }
                    </style>
                  </head>
                  <body>
                    <p>天地玄黃宇宙洪荒。<ruby>漢<rt>kan</rt></ruby><span style="text-combine-upright: all;">2026</span>。</p>
                    <p>日月盈昃辰宿列張。「古籍」之形，當隨竪排而定。</p>
                  </body>
                </html>
            """.trimIndent().encodeToByteArray(),
            referencedResourceHrefs = emptySet()
        )
        return Book(
            sourcePath = "demo",
            metadata = Metadata(listOf("古籍示例"), emptyList(), listOf("zh-CN"), listOf("demo"), emptyList(), emptyList(), emptyList(), emptyList(), null, emptyMap()),
            manifest = Manifest(emptyList()),
            spine = Spine(listOf(SpineItem("chap1", "chapter1.xhtml", true))),
            navigation = Navigation(null, listOf(NavigationNode("第一章", "chapter1.xhtml"))),
            chapters = listOf(chapter),
            resourcesByHref = emptyMap(),
            issues = emptyList()
        )
    }
}
