package com.worklog.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.OutlinedButton
import androidx.core.content.FileProvider
import java.io.FileOutputStream
import java.util.Calendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.worklog.app.data.Entry
import com.worklog.app.data.EntryStore
import com.worklog.app.util.Locator
import com.worklog.app.util.Media
import com.worklog.app.util.Watermark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    store: EntryStore,
    onSaved: (Entry) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    var locationGranted by remember { mutableStateOf(Locator.granted(ctx)) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> locationGranted = result.values.any { it } }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sheetEntry by remember { mutableStateOf<Entry?>(null) }
    var note by remember { mutableStateOf("") }
    var sheetTime by remember { mutableStateOf(0L) }

    val videoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            busy = true
            scope.launch {
                val entry = withContext(Dispatchers.IO) {
                    val file = Media.saveVideoCopy(ctx, uri, store) ?: return@withContext null
                    val thumb = Media.videoThumb(file, store)
                    buildEntry(ctx, store, file, "video", thumb)
                }
                busy = false
                if (entry != null) {
                    onSaved(entry)
                    sheetEntry = entry
                } else {
                    Toast.makeText(ctx, "视频保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val entry = withContext(Dispatchers.IO) {
                    importPicked(ctx, store, uris.take(2))
                }
                busy = false
                if (entry != null) {
                    onSaved(entry)
                    sheetEntry = entry
                } else {
                    Toast.makeText(ctx, "导入图片失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var pendingSecond by remember { mutableStateOf<File?>(null) }

    val secondShotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val file = pendingSecond
        pendingSecond = null
        val currentEntry = sheetEntry
        if (result.resultCode == Activity.RESULT_OK && file != null &&
            file.exists() && file.length() > 0L && currentEntry != null
        ) {
            scope.launch {
                val updated = withContext(Dispatchers.IO) {
                    val entry = currentEntry.copy(
                        paths = (currentEntry.paths + file.absolutePath).take(2)
                    )
                    store.update(entry)
                    entry
                }
                sheetEntry = updated
                onSaved(updated)
            }
        }
    }

    fun shootSecondPhoto() {
        val currentEntry = sheetEntry ?: return
        if (currentEntry.paths.size >= 2) return
        val file = File(store.mediaDir(), "IMG_${System.currentTimeMillis()}.jpg")
        pendingSecond = file
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            secondShotLauncher.launch(intent)
        }.onFailure {
            Toast.makeText(ctx, "没有可用的相机应用", Toast.LENGTH_SHORT).show()
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                note = if (note.isBlank()) text else note.trimEnd() + "，" + text
            }
        }
    }

    // 打开浮层时，把这条记录已有的备注带进来
    LaunchedEffect(sheetEntry?.id) {
        sheetEntry?.let {
            note = it.note
            sheetTime = it.time
        }
    }

    // 相机权限拿到后，自动申请一次定位权限
    LaunchedEffect(granted) {
        if (granted && !locationGranted && !locationAsked) {
            locationAsked = true
            locationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        if (!granted) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要相机权限才能拍照记录", color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }) {
                    Text("去授权")
                }
            }
        } else {
            CameraPreview(Modifier.fillMaxSize()) { imageCapture = it }

            AssistChip(
                onClick = {
                    if (!locationGranted) {
                        locationPermission.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                label = { Text(if (locationGranted) "定位已开启" else "定位未开启") },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = !busy) {
                            runCatching {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }.onFailure {
                                Toast.makeText(ctx, "无法打开相册", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = "从相册选择",
                        tint = Color.White
                    )
                }

                ShutterButton(enabled = imageCapture != null && !busy) {
                    val capture = imageCapture ?: return@ShutterButton
                    if (busy) return@ShutterButton
                    busy = true
                    val file = File(store.mediaDir(), "IMG_${System.currentTimeMillis()}.jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(
                                outputFileResults: ImageCapture.OutputFileResults
                            ) {
                                scope.launch {
                                    val entry = withContext(Dispatchers.IO) {
                                        buildEntry(ctx, store, file, "photo", null)
                                    }
                                    busy = false
                                    onSaved(entry)
                                    sheetEntry = entry
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                Toast.makeText(
                                    ctx,
                                    "拍照失败：" + exception.message,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable(enabled = !busy) {
                            runCatching {
                                videoLauncher.launch(
                                    Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE).apply {
                                        putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 1)
                                    }
                                )
                            }.onFailure {
                                Toast.makeText(ctx, "没有可用的录像应用", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Videocam, contentDescription = "录像", tint = Color.White)
                }
            }

            if (busy) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }

    val current = sheetEntry
    if (current != null) {
        ModalBottomSheet(
            onDismissRequest = { sheetEntry = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text("给这条记录补一句", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                                .format(Date(sheetTime))
                        )
                        val lat = current.lat
                        val lng = current.lng
                        val where = current.place?.takeIf { it.isNotBlank() }
                            ?: if (lat != null && lng != null) {
                                String.format(Locale.US, "%.5f, %.5f", lat, lng)
                            } else {
                                null
                            }
                        where?.let { append("　").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { pickDateTime(ctx, sheetTime) { sheetTime = it } }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("修改日期时间")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已拍 ${current.paths.size} 张（最多 2 张）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    if (current.paths.size < 2) {
                        OutlinedButton(onClick = { shootSecondPhoto() }) {
                            Text("再拍一张")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text("这儿做了什么…") }
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "说一句这处工作的内容")
                            }
                            runCatching { speechLauncher.launch(intent) }.onFailure {
                                Toast.makeText(
                                    ctx,
                                    "没有可用的语音识别服务",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "语音输入")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "说一句，自动转文字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { sheetEntry = null }) { Text("跳过") }
                    Button(
                        onClick = {
                            val updated = current.copy(time = sheetTime, note = note.trim())
                            scope.launch {
                                withContext(Dispatchers.IO) { store.update(updated) }
                                onSaved(updated)
                                sheetEntry = null
                            }
                        }
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color.White.copy(alpha = 0.35f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(if (enabled) Color(0xFF2563EB) else Color(0xFF94A3B8))
        )
    }
}

@Composable
private fun CameraPreview(modifier: Modifier, onCapture: (ImageCapture?) -> Unit) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener(
            Runnable {
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                    onCapture(capture)
                }
            },
            ContextCompat.getMainExecutor(ctx)
        )
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
            onCapture(null)
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private suspend fun buildEntry(
    ctx: Context,
    store: EntryStore,
    file: File,
    kind: String,
    thumb: File?
): Entry {
    val time = System.currentTimeMillis()
    val location = Locator.current(ctx)
    val place = location?.let { Locator.placeName(ctx, it.latitude, it.longitude) }
    val lat = location?.latitude
    val lng = location?.longitude

    // 照片底部压一条「时间 + 位置」水印；视频只记录不烧录
    if (kind == "photo") {
        Watermark.apply(file, stampText(time, place, lat, lng))
    }

    val entry = Entry(
        id = UUID.randomUUID().toString(),
        time = time,
        kind = kind,
        paths = listOf(file.absolutePath),
        thumb = thumb?.absolutePath,
        note = "",
        lat = lat,
        lng = lng,
        place = place
    )
    store.add(entry)
    return entry
}

/** 把相册选的图片（最多两张）复制进来，生成一条记录 */
private suspend fun importPicked(ctx: Context, store: EntryStore, uris: List<Uri>): Entry? {
    val dests = mutableListOf<File>()
    uris.forEachIndexed { index, uri ->
        val dest = File(store.mediaDir(), "PIC_${System.currentTimeMillis()}_$index.jpg")
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
        }.getOrNull()
        if (ok != null && dest.exists() && dest.length() > 0L) dests.add(dest)
    }
    if (dests.isEmpty()) return null

    val location = Locator.current(ctx)
    val place = location?.let { Locator.placeName(ctx, it.latitude, it.longitude) }
    val entry = Entry(
        id = UUID.randomUUID().toString(),
        time = System.currentTimeMillis(),
        kind = "photo",
        paths = dests.map { it.absolutePath },
        thumb = null,
        note = "",
        lat = location?.latitude,
        lng = location?.longitude,
        place = place
    )
    store.add(entry)
    return entry
}

/** 水印文字：时间 + 地名，没有地名就用坐标兜底 */
private fun stampText(time: Long, place: String?, lat: Double?, lng: Double?): String {
    val clock = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(time))
    val where = place?.takeIf { it.isNotBlank() }
        ?: if (lat != null && lng != null) {
            String.format(Locale.US, "%.5f, %.5f", lat, lng)
        } else {
            null
        }
    return if (where != null) "$clock  $where" else clock
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

/** 本次进程内是否已经自动申请过定位权限，避免反复弹框 */
private var locationAsked = false
