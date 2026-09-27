package com.myreading.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Path
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
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class ReaderSection {
    SHELF,
    HISTORY,
    READER
}

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
    val bookmarks: Set<ReadingPosition> = emptySet(),
    val storedBooks: List<StoredBook> = emptyList(),
    val currentBookId: String? = null,
    val section: ReaderSection = ReaderSection.SHELF,
    val sessionStartedAt: Long = 0L
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
    private val parser = EpubParser()
    private val layoutEngine = VerticalLayoutEngine(LayoutCache(16))
    private val libraryStore = LibraryStore(app)

    var state by mutableStateOf(ReaderUiState(storedBooks = libraryStore.books()))
        private set

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
                val (bookId, file) = copyToLibraryFile(getApplication(), uri)
                val book = parser.parse(file)
                require(book.chapters.isNotEmpty()) { "EPUB 中没有可阅读章节" }
                val initialChapterIndex = book.initialReadableChapterIndex()
                val record = libraryStore.recordOpened(
                    id = bookId,
                    filePath = file.absolutePath,
                    title = book.metadata.title ?: file.nameWithoutExtension,
                    creator = book.metadata.creator.orEmpty(),
                    defaultChapterIndex = initialChapterIndex
                )
                withContext(Dispatchers.Main) {
                    showBook(book, record)
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    state = state.copy(loading = false, errorMessage = readableImportError(error))
                }
            }
        }
    }

    fun openStoredBook(record: StoredBook) {
        state = state.copy(loading = true, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(record.filePath)
                require(file.isFile) { "书籍文件已不存在" }
                val book = parser.parse(file)
                val openedRecord = libraryStore.recordOpened(
                    id = record.id,
                    filePath = record.filePath,
                    title = record.title,
                    creator = record.creator,
                    defaultChapterIndex = book.initialReadableChapterIndex()
                )
                withContext(Dispatchers.Main) { showBook(book, openedRecord) }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    state = state.copy(loading = false, errorMessage = readableImportError(error))
                }
            }
        }
    }

    private fun showBook(book: Book, record: StoredBook) {
        val chapterIndex = record.lastChapterIndex.coerceIn(0, book.chapters.lastIndex)
        state = state.copy(
            loading = false,
            errorMessage = null,
            book = book,
            chapterIndex = chapterIndex,
            pageIndex = record.lastPageIndex.coerceAtLeast(0),
            bookmarks = loadBookmarks(book),
            storedBooks = libraryStore.books(),
            currentBookId = record.id,
            section = ReaderSection.READER,
            sessionStartedAt = System.currentTimeMillis()
        )
        val lastPageIndex = currentPages().lastIndex.coerceAtLeast(0)
        if (state.pageIndex > lastPageIndex) state = state.copy(pageIndex = lastPageIndex)
        saveProgress()
    }

    fun nextPage() {
        val book = state.book ?: return
        val position = ReadingPosition(state.chapterIndex, state.pageIndex).next(book.chapters.size) {
            pagesForChapter(it).size
        }
        state = state.copy(chapterIndex = position.chapterIndex, pageIndex = position.pageIndex)
        saveProgress()
    }

    fun previousPage() {
        if (state.book == null) return
        val position = ReadingPosition(state.chapterIndex, state.pageIndex).previous {
            pagesForChapter(it).size
        }
        state = state.copy(chapterIndex = position.chapterIndex, pageIndex = position.pageIndex)
        saveProgress()
    }

    fun selectChapter(index: Int) {
        val book = state.book ?: return
        if (index in book.chapters.indices) {
            state = state.copy(chapterIndex = index, pageIndex = 0)
            saveProgress()
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
        saveProgress()
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

    fun increaseFont() = state.let {
        state = it.copy(fontSize = (it.fontSize + 1f).coerceAtMost(40f), pageIndex = 0)
        saveProgress()
    }

    fun decreaseFont() = state.let {
        state = it.copy(fontSize = (it.fontSize - 1f).coerceAtLeast(12f), pageIndex = 0)
        saveProgress()
    }

    fun updateViewport(width: Float, height: Float) {
        if (state.pageWidth != width || state.pageHeight != height) {
            state = state.copy(pageWidth = width, pageHeight = height)
            val lastPageIndex = currentPages().lastIndex.coerceAtLeast(0)
            if (state.pageIndex > lastPageIndex) {
                state = state.copy(pageIndex = lastPageIndex)
                saveProgress()
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
            bookmarks = loadBookmarks(demo),
            currentBookId = null,
            section = ReaderSection.READER,
            sessionStartedAt = System.currentTimeMillis()
        )
    }

    fun showShelf() {
        state = state.copy(section = ReaderSection.SHELF, storedBooks = libraryStore.books())
    }

    fun showHistory() {
        state = state.copy(section = ReaderSection.HISTORY, storedBooks = libraryStore.books())
    }

    fun toggleCurrentShelf() {
        val id = state.currentBookId ?: return
        val record = libraryStore.book(id) ?: return
        libraryStore.setShelf(id, !record.inShelf)
        state = state.copy(storedBooks = libraryStore.books())
    }

    fun removeFromShelf(id: String) {
        libraryStore.setShelf(id, false)
        state = state.copy(storedBooks = libraryStore.books())
    }

    fun removeFromHistory(id: String) {
        libraryStore.removeHistory(id)
        state = state.copy(storedBooks = libraryStore.books())
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

    private fun copyToLibraryFile(context: Context, uri: Uri): Pair<String, File> {
        val booksDirectory = File(context.filesDir, "books").apply { mkdirs() }
        val temporary = File.createTempFile("import-", ".epub", booksDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Unable to open EPUB URI" }
        input.use {
            temporary.outputStream().use { outputStream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    outputStream.write(buffer, 0, count)
                }
            }
        }
        val id = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        val storedFile = File(booksDirectory, "$id.epub")
        if (storedFile.exists()) {
            temporary.delete()
        } else if (!temporary.renameTo(storedFile)) {
            temporary.copyTo(storedFile, overwrite = true)
            temporary.delete()
        }
        return id to storedFile
    }

    private fun saveProgress() {
        val id = state.currentBookId ?: return
        libraryStore.updateProgress(id, state.chapterIndex, state.pageIndex)
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
            when (state.section) {
                ReaderSection.SHELF -> CollectionScreen(
                    title = "书架",
                    books = state.storedBooks.filter(StoredBook::inShelf),
                    loading = state.loading,
                    errorMessage = state.errorMessage,
                    onOpen = { openDocument.launch(EPUB_MIME_TYPES) },
                    onOpenDemo = viewModel::openDemo,
                    onOpenBook = viewModel::openStoredBook,
                    onRemove = { viewModel.removeFromShelf(it.id) },
                    onShowShelf = viewModel::showShelf,
                    onShowHistory = viewModel::showHistory
                )

                ReaderSection.HISTORY -> CollectionScreen(
                    title = "阅读历史",
                    books = state.storedBooks.filter(StoredBook::inHistory),
                    loading = state.loading,
                    errorMessage = state.errorMessage,
                    onOpen = { openDocument.launch(EPUB_MIME_TYPES) },
                    onOpenDemo = viewModel::openDemo,
                    onOpenBook = viewModel::openStoredBook,
                    onRemove = { viewModel.removeFromHistory(it.id) },
                    onShowShelf = viewModel::showShelf,
                    onShowHistory = viewModel::showHistory
                )

                ReaderSection.READER -> {
                    val book = state.book
                    if (book == null) {
                        LaunchedEffect(Unit) { viewModel.showShelf() }
                    } else {
                        val pages = viewModel.currentPages()
                        val inShelf = state.storedBooks.firstOrNull { it.id == state.currentBookId }?.inShelf == true
                        ReaderScreen(
                            state = state,
                            chapterTitle = viewModel.chapterTitle(),
                            chapterTitles = book.chapters.map { it.title },
                            pageCount = pages.size,
                            page = pages.getOrNull(state.pageIndex),
                            inShelf = inShelf,
                            onToggleShelf = viewModel::toggleCurrentShelf,
                            onShowShelf = viewModel::showShelf,
                            onShowHistory = viewModel::showHistory,
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
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun CollectionScreen(
    title: String,
    books: List<StoredBook>,
    loading: Boolean,
    errorMessage: String?,
    onOpen: () -> Unit,
    onOpenDemo: () -> Unit,
    onOpenBook: (StoredBook) -> Unit,
    onRemove: (StoredBook) -> Unit,
    onShowShelf: () -> Unit,
    onShowHistory: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF4F0E8))) {
        TopAppBar(
            title = { Text(title) },
            actions = {
                TextButton(onClick = onShowShelf) { Text("书架") }
                TextButton(onClick = onShowHistory) { Text("阅读历史") }
                TextButton(onClick = onOpen) { Text("导入 EPUB") }
            }
        )
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp))
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (books.isEmpty() && !loading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (title == "书架") "书架暂无图书" else "暂无阅读历史", style = MaterialTheme.typography.headlineSmall)
                    Text("导入 EPUB 后会保存阅读位置，长按图书可从当前列表移除。")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onOpen) { Text("打开 EPUB") }
                        OutlinedButton(onClick = onOpenDemo) { Text("加载示例") }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 20.dp)
            ) {
                itemsIndexed(books, key = { _, book -> book.id }) { _, book ->
                    Card(
                        modifier = Modifier.fillMaxWidth().combinedClickable(
                            onClick = { onOpenBook(book) },
                            onLongClick = { onRemove(book) }
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(book.title.ifBlank { "未命名图书" }, style = MaterialTheme.typography.titleLarge)
                                if (book.creator.isNotBlank()) Text(book.creator, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "上次阅读：第 ${book.lastChapterIndex + 1} 章 · 第 ${book.lastPageIndex + 1} 页",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(if (book.inShelf) "★" else "历史", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
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
    inShelf: Boolean,
    onToggleShelf: () -> Unit,
    onShowShelf: () -> Unit,
    onShowHistory: () -> Unit,
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
    var clockMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(state.sessionStartedAt) {
        while (true) {
            clockMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val systemTime = remember(clockMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(clockMillis))
    }
    val readingDuration = formatReadingDuration((clockMillis - state.sessionStartedAt).coerceAtLeast(0L))

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
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(onClick = onShowShelf, modifier = Modifier.fillMaxWidth()) { Text("书架") }
                        OutlinedButton(onClick = onShowHistory, modifier = Modifier.fillMaxWidth()) { Text("阅读历史") }
                        OutlinedButton(
                            onClick = { showContents = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("目录") }
                        OutlinedButton(
                            onClick = { showBookmarks = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("书签 ${state.bookmarks.size}") }
                    }
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onToggleShelf, modifier = Modifier.fillMaxWidth()) {
                            Text(if (inShelf) "★ 移出书架" else "☆ 加入书架")
                        }
                        Text(
                            "章节 ${state.chapterIndex + 1} / ${maxOf(chapterTitles.size, 1)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            if (pageCount == 0) "页面 0 / 0" else "页面 ${state.pageIndex + 1} / $pageCount",
                            style = MaterialTheme.typography.labelSmall
                        )
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
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(end = 44.dp)) {
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
                if (currentBookmarked) BookmarkRibbon(modifier = Modifier.align(Alignment.TopEnd).padding(end = 9.dp))
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
                        Column {
                            Text("系统时间 $systemTime", style = MaterialTheme.typography.labelLarge)
                            Text("本次阅读 $readingDuration", style = MaterialTheme.typography.labelMedium)
                        }
                        Text("下拉添加书签 · 上拉取消", style = MaterialTheme.typography.bodySmall)
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

private fun formatReadingDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun BookmarkRibbon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(24.dp).height(64.dp)) {
        val ribbon = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(size.width / 2f, size.height * 0.78f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(ribbon, Color(0xFFB3261E))
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
