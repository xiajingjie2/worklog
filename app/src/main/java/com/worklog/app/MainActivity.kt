package com.worklog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.worklog.app.data.Entry
import com.worklog.app.data.EntryStore
import com.worklog.app.ui.CaptureScreen
import com.worklog.app.ui.LogScreen
import com.worklog.app.ui.WorkLogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorkLogTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val store = remember { EntryStore(ctx) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var tab by remember { mutableIntStateOf(1) }

    LaunchedEffect(Unit) {
        entries = withContext(Dispatchers.IO) { store.load() }
            .sortedByDescending { it.time }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = "拍摄") },
                    label = { Text("拍摄") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = "日志") },
                    label = { Text("日志") }
                )
            }
        }
    ) { padding ->
        if (tab == 0) {
            CaptureScreen(
                modifier = Modifier.padding(padding),
                store = store,
                onSaved = { saved ->
                    entries = (entries.filterNot { it.id == saved.id } + saved)
                        .sortedByDescending { it.time }
                }
            )
        } else {
            LogScreen(
                modifier = Modifier.padding(padding),
                entries = entries,
                store = store,
                onAdd = { tab = 0 },
                onChanged = { list ->
                    entries = list.sortedByDescending { it.time }
                }
            )
        }
    }
}
