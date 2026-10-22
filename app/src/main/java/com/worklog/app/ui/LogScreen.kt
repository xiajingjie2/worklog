package com.worklog.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.OutlinedTextField
import androidx.core.content.FileProvider
import java.io.FileOutputStream
import java.util.Calendar

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
import com.worklog.app.util.ImageOps
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
    onAdd: () -> Unit,
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

    var editing by remember { mutableStateOf<Entry?>(null) }
    var editNote by remember { mutableStateOf("") }
    var editTime by remember { mutableStateOf(0L) }
    var editPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCalendar by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportedMime by remember { mutableStateOf(Exporter.MIME_DOCX) }

    val markedDays = remember(entries) {
        entries.map {
            Instant.ofEpochMilli(it.time).atZone(ZoneId.systemDefault()).toLocalDate()
        }.toSet()
    }

    val editPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    val out = mutableListOf<String>()
                    val dir = File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }
                    uris.take(2).forEachIndexed { i, uri ->
                        val dest = File(dir, "IMG_${System.currentTimeMillis()}_$i.jpg")
                        val ok = runCatching {
                            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                                FileOutputStream(dest).use { o -> ins.copyTo(o) }
                            }
                        }.getOrNull()
                        if (ok != null && dest.length() > 0L) out.add(dest.absolutePath)
                    }
                    out
                }
                editPaths = (editPaths + added).distinct().take(2)
            }
        }
    }

    var showImageSource by remember { mutableStateOf(false) }
    var editShotFile by remember { mutableStateOf<File?>(null) }

    val editCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val file = editShotFile
        editShotFile = null
        if (result.resultCode == android.app.Activity.RESULT_OK && file != null &&
            file.exists() && file.length() > 0L
        ) {
            editPaths = (editPaths + file.absolutePath).take(2)
        }
    }

    fun shootForEdit() {
        val dir = File(ctx.filesDir, "media").apply { if (!exists()) mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        editShotFile = file
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            editCameraLauncher.launch(intent)
        }.onFailure {
            Toast.makeText(ctx, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    val dayEntries = remember(entries, day) {
        entries
            .filter {
                Instant.ofEpochMilli(it.time)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate() == day
            }
            .sortedBy { it.time }
    }

    fun doExport(format: Exporter.Format) {
        exporting = true
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    Exporter.export(ctx, day, dayEntries, mode, format)
                }
                exportedMime = if (format == Exporter.Format.EXCEL) {
                    Exporter.MIME_XLSX
                } else {
                    Exporter.MIME_DOCX
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
                modifier = Modifier
                    .weight(1f)
                    .clickable { showCalendar = true }
                    .padding(vertical = 8.dp),
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
                    EntryRow(
                        entry = entry,
                        onOpen = {
                            editing = entry
                            editNote = entry.note
                            editTime = entry.time
                            editPaths = entry.paths
                        },
                        onDelete = { pendingDelete = entry }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onAdd,
                modifier = Modifier.weight(0.9f)
            ) {
                Text("新增")
            }
            OutlinedButton(
                onClick = { showModeDialog = true },
                modifier = Modifier.weight(1.3f)
            ) {
                Text("模式：" + AppSettings.label(mode))
            }
            Button(
                onClick = { showExportDialog = true },
                enabled = dayEntries.isNotEmpty() && !exporting,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (exporting) "生成中…" else "导出")
            }
        }
    }

    if (showCalendar) {
        MonthCalendarDialog(
            initial = day,
            marked = markedDays,
            onPick = { picked ->
                day = picked
                showCalendar = false
            },
            onDismiss = { showCalendar = false }
        )
    }

    val editingEntry = editing
    if (editingEntry != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("修改记录") },
            text = {
                Column {
                    TextButton(onClick = { pickDateTime(ctx, editTime) { editTime = it } }) {
                        Text(
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                                .format(Date(editTime))
                        )
                    }
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        placeholder = { Text("备注") }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        editPaths.forEach { path ->
                            Box {
                                Thumb(path = path, size = 64.dp)
                                IconButton(
                                    onClick = { editPaths = editPaths - path },
                                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除图片",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        if (editPaths.size < 2) {
                            OutlinedButton(onClick = { showImageSource = true }) {
                                Text("+ 图片")
                            }
                        }
                    }
                    Text(
                        "每条记录最多两张图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = editingEntry.copy(
                        time = editTime,
                        note = editNote.trim(),
                        paths = editPaths
                    )
                    editing = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.update(updated) }
                        onChanged(entries.map { if (it.id == updated.id) updated else it })
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出为") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            doExport(Exporter.Format.WORD)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Word 文档（.docx）")
                    }
                    TextButton(
                        onClick = {
                            showExportDialog = false
                            doExport(Exporter.Format.EXCEL)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Excel 表格（.xlsx）")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    if (showImageSource) {
        AlertDialog(
            onDismissRequest = { showImageSource = false },
            title = { Text("添加图片") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showImageSource = false
                            shootForEdit()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("拍摄") }
                    TextButton(
                        onClick = {
                            showImageSource = false
                            runCatching {
                                editPicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从相册选择") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageSource = false }) { Text("取消") }
            }
        )
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
                    openDoc(ctx, shared, exportedMime)
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
                        AppSettings.MODE_LOG to "日志",
                        AppSettings.MODE_BRIEF to "简报",
                        AppSettings.MODE_TABLE to "表格"
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
                            target.paths.forEach { runCatching { File(it).delete() } }
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
private fun EntryRow(entry: Entry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
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
            ImageOps.loadUpright(File(path), 256)
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

/** 月历：有记录的日期打点标记，点选即可跳转 */
@Composable
private fun MonthCalendarDialog(
    initial: LocalDate,
    marked: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var month by remember { mutableStateOf(initial.withDayOfMonth(1)) }
    val titleFormat = remember { DateTimeFormatter.ofPattern("yyyy 年 M 月", Locale.CHINA) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
                }
                Text(
                    month.format(titleFormat),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "下个月")
                }
            }
        },
        text = {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Text(
                            label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))

                val offset = month.withDayOfMonth(1).dayOfWeek.value - 1
                val days = month.lengthOfMonth()
                val rows = (offset + days + 6) / 7
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val dayNum = row * 7 + col - offset + 1
                            if (dayNum in 1..days) {
                                val date = month.withDayOfMonth(dayNum)
                                val isMarked = marked.contains(date)
                                val isSelected = date == initial
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(2.dp)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.Transparent
                                        )
                                        .clickable { onPick(date) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            dayNum.toString(),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                        if (isMarked) {
                                            Spacer(Modifier.height(2.dp))
                                            Box(
                                                Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (isSelected) {
                                                            MaterialTheme.colorScheme.onPrimary
                                                        } else {
                                                            MaterialTheme.colorScheme.primary
                                                        }
                                                    )
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 依次弹出日期、时间选择框 */
private fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        ctx,
        { _, year, month, dayOfMonth ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
            TimePickerDialog(
                ctx,
                { _, hour, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hour)
                    cal.set(Calendar.MINUTE, minute)
                    cal.set(Calendar.SECOND, 0)
                    onPicked(cal.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
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
private fun openDoc(ctx: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
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
