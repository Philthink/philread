package com.myreading.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val pageHeight: Float = 640f
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
                withContext(Dispatchers.Main) {
                    state = state.copy(
                        loading = false,
                        errorMessage = null,
                        book = book,
                        chapterIndex = 0,
                        pageIndex = 0
                    )
                }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    state = state.copy(loading = false, errorMessage = error.message ?: "打开 EPUB 失败")
                }
            }
        }
    }

    fun nextPage() {
        val pages = currentPages()
        if (state.pageIndex + 1 < pages.size) state = state.copy(pageIndex = state.pageIndex + 1)
    }

    fun previousPage() {
        if (state.pageIndex > 0) state = state.copy(pageIndex = state.pageIndex - 1)
    }

    fun selectChapter(index: Int) {
        val book = state.book ?: return
        if (index in book.chapters.indices) {
            state = state.copy(chapterIndex = index, pageIndex = 0)
        }
    }

    fun increaseFont() = state.let { state = it.copy(fontSize = (it.fontSize + 1f).coerceAtMost(40f), pageIndex = 0) }
    fun decreaseFont() = state.let { state = it.copy(fontSize = (it.fontSize - 1f).coerceAtLeast(12f), pageIndex = 0) }

    fun updateViewport(width: Float, height: Float) {
        if (state.pageWidth != width || state.pageHeight != height) {
            state = state.copy(pageWidth = width, pageHeight = height)
        }
    }

    fun currentPages(): List<PageLayout> {
        val book = state.book ?: return emptyList()
        val chapter = book.chapters.getOrNull(state.chapterIndex) ?: return emptyList()
        val stylesheet = extractStylesheet(book, chapter)
        return layoutEngine.paginate(chapter, stylesheet, state.layoutSettings).pages
    }

    fun currentPage(): PageLayout? = currentPages().getOrNull(state.pageIndex)

    fun chapterTitle(): String = state.book?.chapters?.getOrNull(state.chapterIndex)?.title ?: "未打开书籍"

    fun chapterCount(): Int = state.book?.chapters?.size ?: 0

    fun openDemo() {
        val demo = DemoBookFactory.sampleBook()
        state = state.copy(loading = false, errorMessage = null, book = demo, chapterIndex = 0, pageIndex = 0)
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
}

@Composable
fun ReaderApp() {
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

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (state.book == null) {
                EmptyLibraryScreen(
                    loading = state.loading,
                    errorMessage = state.errorMessage,
                    onOpen = { openDocument.launch(arrayOf("application/epub+zip")) },
                    onOpenDemo = viewModel::openDemo
                )
            } else {
                ReaderScreen(
                    state = state,
                    chapterTitle = viewModel.chapterTitle(),
                    chapterCount = viewModel.chapterCount(),
                    page = viewModel.currentPage(),
                    onOpen = { openDocument.launch(arrayOf("application/epub+zip")) },
                    onPreviousPage = viewModel::previousPage,
                    onNextPage = viewModel::nextPage,
                    onPreviousChapter = { viewModel.selectChapter((state.chapterIndex - 1).coerceAtLeast(0)) },
                    onNextChapter = {
                        val lastIndex = (viewModel.chapterCount() - 1).coerceAtLeast(0)
                        viewModel.selectChapter((state.chapterIndex + 1).coerceAtMost(lastIndex))
                    },
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
    chapterCount: Int,
    page: PageLayout?,
    onOpen: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onFontPlus: () -> Unit,
    onFontMinus: () -> Unit,
    onViewportChanged: (Float, Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF8F3EA))) {
        TopAppBar(
            title = { Text(chapterTitle) },
            actions = {
                TextButton(onClick = onOpen) { Text("打开") }
                TextButton(onClick = onFontMinus) { Text("A-") }
                TextButton(onClick = onFontPlus) { Text("A+") }
            }
        )
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.width(120.dp).fillMaxHeight().background(Color(0xFFF0E4D2)),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("章节", style = MaterialTheme.typography.titleMedium)
                    Text("${state.chapterIndex + 1} / ${maxOf(chapterCount, 1)}")
                    Spacer(Modifier.height(8.dp))
                    Text("页面", style = MaterialTheme.typography.titleMedium)
                    Text("${state.pageIndex + 1}")
                }
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPreviousChapter, modifier = Modifier.fillMaxWidth()) { Text("上一章") }
                    OutlinedButton(onClick = onNextChapter, modifier = Modifier.fillMaxWidth()) { Text("下一章") }
                }
            }
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(16.dp)
                    .background(Color.White)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset -> if (offset.x < size.width / 2f) onPreviousPage() else onNextPage() }
                        )
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
        BottomAppBar {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("点击左右半区翻页")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onPreviousPage) { Text("上一页") }
                    OutlinedButton(onClick = onNextPage) { Text("下一页") }
                }
            }
        }
    }
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
            """.trimIndent(),
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
