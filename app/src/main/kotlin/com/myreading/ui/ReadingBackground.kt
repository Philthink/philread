package com.myreading.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
internal fun Modifier.readingBackground(color: Long, uri: String, dark: Boolean): Modifier {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = null
        if (uri.isNotEmpty()) value = withContext(Dispatchers.IO) {
            runCatching {
                val source = Uri.parse(uri)
                decodeReadingImage { context.contentResolver.openInputStream(source) }
            }.getOrNull()
        }
    }
    return clipToBounds().background(if (dark) Color(0xFF171512) else Color(color)).drawBehind {
        bitmap?.let {
            val scale = max(size.width / it.width, size.height / it.height)
            val width = (it.width * scale).toInt().coerceAtLeast(1)
            val height = (it.height * scale).toInt().coerceAtLeast(1)
            drawImage(it, dstOffset = IntOffset((size.width.toInt() - width) / 2, (size.height.toInt() - height) / 2),
                dstSize = IntSize(width, height))
            // Keep text legible over photographs, including in night mode.
            drawRect(if (dark) Color.Black.copy(alpha = 0.60f) else Color(color).copy(alpha = 0.25f))
        }
    }
}

@Composable
internal fun BackgroundChoice(title: String, selected: Long, uri: String, onChange: (Long, String) -> Unit) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
        if (result != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(result)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
            }.onSuccess { error = null; onChange(selected, result.toString()) }
                .onFailure { error = "无法读取此图片，请选择本地图片" }
        }
    }
    Text(title, style = MaterialTheme.typography.titleSmall)
    val colors = listOf("护眼米黄" to 0xFFF8F3EAL, "暖白" to 0xFFFFFCF7L,
        "浅绿" to 0xFFE8F0E3L, "浅灰" to 0xFFE9EAEBL, "浅蓝" to 0xFFE3EDF5L)
    colors.forEach { (label, color) ->
        TextButton(onClick = { onChange(color, "") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (selected == color && uri.isEmpty()) "$label · 已选择" else label)
        }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(
            onClick = { picker.launch(arrayOf("image/*")) },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(if (uri.isEmpty()) "选择图片" else "更换图片", style = MaterialTheme.typography.labelMedium)
        }
        if (uri.isNotEmpty()) {
            Box(Modifier.size(width = 160.dp, height = 80.dp).readingBackground(selected, uri, false))
            Text("点击完成后应用", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onChange(selected, "") }) { Text("移除图片") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Spacer(Modifier.height(8.dp))
}
