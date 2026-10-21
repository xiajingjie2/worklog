package com.worklog.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.worklog.app.data.AppSettings
import com.worklog.app.data.Entry
import com.worklog.app.data.EntryStore
import com.worklog.app.export.Exporter
import com.worklog.app.util.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(
    modifier: Modifier = Modifier,
    entries: List<Entry>,
    store: EntryStore,
    onChanged: (List<Entry>) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var day by remember { mutableStateOf(LocalDate.now()) }
    var exporting by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Entry?>(null) }

    val settings = remember { AppSettings(ctx) }
    var mode by remember { mutableStateOf(settings.exportMode) }
    var showModeDialog by remember { mutableStateOf(false) }
    var exportedUri by remember { mutableStateOf<Uri?>(null) }

    val dayEntries = remember(entries, day) {
        entries
            .filter {
                Instant.ofEpochMilli(it.time)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate() == day
            }
            .sortedBy { it.time }
    }

    Column(modifier = modifier.fillMaxSize()) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { day = day.minusDays(1) }) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "前一天")
            }
            Text(
                text = day.format(DateTimeFormatter.ofPattern("M 月 d 日 EEEE", Locale.CHINA)),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium
            )
            IconButton(
                onClick = { if (day.isBefore(LocalDate.now())) day = day.plusDays(1) }
            ) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "后一天")
            }
        }

        if (dayEntries.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "这一天还没有记录",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(dayEntries, key = { it.id }) { entry ->
                    EntryRow(entry = entry, onDelete = { pendingDelete = entry })
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showModeDialog = true },
                modifier = Modifier.weight(1f)
            ) {
                Text("模式：" + AppSettings.label(mode))
            }
            Button(
                onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            val uri = withContext(Dispatchers.IO) {
                                Exporter.exportDocx(ctx, day, dayEntries, mode)
                            }
                            exportedUri = uri
                        } catch (t: Throwable) {
                            Toast.makeText(
                                ctx,
                                "导出失败：" + (t.message ?: t.javaClass.simpleName),
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            exporting = false
                        }
                    }
                },
                enabled = dayEntries.isNotEmpty() && !exporting,
                modifier = Modifier.weight(1.4f)
            ) {
                Text(if (exporting) "正在生成…" else "导出 Word")
            }
        }
    }

    val shared = exportedUri
    if (shared != null) {
        AlertDialog(
            onDismissRequest = { exportedUri = null },
            title = { Text("已保存到「下载/工作日志」") },
            text = { Text("文件放在手机的下载目录里，卸载 App 也不会丢。") },
            confirmButton = {
                TextButton(onClick = {
                    exportedUri = null
                    openDoc(ctx, shared)
                }) { Text("预览") }
            },
            dismissButton = {
                TextButton(onClick = {
                    exportedUri = null
                    shareDocx(ctx, shared)
                }) { Text("分享") }
            }
        )
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("选择导出模式") },
            text = {
                Column {
                    listOf(
                        AppSettings.MODE_LOG to "工作日志摘要",
                        AppSettings.MODE_BRIEF to "简报"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    mode = value
                                    settings.exportMode = value
                                    showModeDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = mode == value, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("关闭") }
            }
        )
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = { Text("照片/视频文件也会一起删掉，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { File(target.path).delete() }
                            target.thumb?.let { runCatching { File(it).delete() } }
                            store.remove(target.id)
                        }
                        onChanged(entries.filterNot { it.id == target.id })
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EntryRow(entry: Entry, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Thumb(path = entry.previewPath, size = 72.dp)
                if (entry.isVideo) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(entry.time)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    locationText(entry).ifBlank { "未记录位置" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(entry.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun Thumb(path: String, size: Dp) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) {
            Media.decodeSampled(path, 256)
        }?.asImageBitmap()
    }

    val img = bitmap
    if (img != null) {
        Image(
            bitmap = img,
            contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

private fun shareDocx(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "分享工作日志"))
}

/** 用系统里能打开 docx 的应用预览 */
private fun openDoc(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, Exporter.MIME_DOCX)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(intent) }
        .onFailure {
            Toast.makeText(ctx, "没有能打开 Word 的应用", Toast.LENGTH_SHORT).show()
        }
}

/** 优先显示地名，没有地名就退化成坐标，两者都没有返回空串 */
private fun locationText(entry: Entry): String {
    entry.place?.takeIf { it.isNotBlank() }?.let { return it }
    val lat = entry.lat
    val lng = entry.lng
    return if (lat != null && lng != null) {
        String.format(Locale.US, "%.5f, %.5f", lat, lng)
    } else {
        ""
    }
}
