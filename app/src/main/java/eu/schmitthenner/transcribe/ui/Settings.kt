package eu.schmitthenner.transcribe.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import eu.schmitthenner.transcribe.DownloadCompleteReceiver
import kotlinx.coroutines.*

import eu.schmitthenner.transcribe.Model
import eu.schmitthenner.transcribe.ModelState
import eu.schmitthenner.transcribe.SelectedModel
import eu.schmitthenner.transcribe.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import java.io.File

data class DownloadDetails(
    val id: Long,
    val details: String?,
    val downloadFinished: Boolean = false
)

class Downloader(val context: ComponentActivity, val model: Model) {
    val ongoing: MutableStateFlow<Map<SelectedModel, DownloadDetails>> = MutableStateFlow(mapOf())

    init {
        context.lifecycleScope.launch {
            while (true) {
                val v = ongoing.filter { it.isNotEmpty() }.first()
                checkDownloads(v)
                delay(1000)
            }
        }
    }

    private fun pretty(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / 1024 / 1024} MB"
        }
    }

     private fun checkDownloads(d: Map<SelectedModel, DownloadDetails>) {
        val downloadManager = context.getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query()
        query.setFilterById(*d.map { it.value.id }.toLongArray())
        val cursor = downloadManager.query(query)

        var result: MutableMap<Long, DownloadDetails> = mutableMapOf()

        if (cursor.moveToFirst()) {
            val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val idColumn = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val downloadedSoFar = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val reason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val localUri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            do {
                val id = cursor.getLong(idColumn)
                when (cursor.getInt(statusColumn)) {
                    DownloadManager.STATUS_PENDING ->
                        result[id] = DownloadDetails(id, "pending - Reason ${cursor.getInt(reason)}")
                    DownloadManager.STATUS_PAUSED ->
                        result[id] = DownloadDetails(id, "paused - Reason ${cursor.getInt(reason)}")
                    DownloadManager.STATUS_RUNNING -> {
                        val downloadedBytes = pretty(cursor.getLong(downloadedSoFar))
                        result[id] = DownloadDetails(id, "running - $downloadedBytes")
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        result[id] = DownloadDetails(id, "successfully downloaded, copying to app directory", downloadFinished = true)
                        val s = d.filter { it.value.id == id }.firstNotNullOf { it.key }
                        triggerCopy(id, cursor.getString(localUri).toUri(), s)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        result[id] = DownloadDetails(id, "failed - Reason ${cursor.getInt(reason)}")
                    }
                }
            } while (cursor.moveToNext())
        }
        ongoing.update {
            it.mapValues { (_, v) ->  result[v.id] ?: v }
        }
    }

    private fun triggerCopy(id: Long, uri: Uri, s: SelectedModel) {
        context.lifecycleScope.launch(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                val dest = File(context.filesDir, s.fileName())
                val stream2 = context.contentResolver.openOutputStream(dest.toUri())
                if (stream2 != null) {

                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        stream2.write(buffer, 0, bytesRead)
                    }
                    stream2.close()
                    ongoing.update { it - s }
                    model.downloadFinished(s)
                }
                stream.close()
                val downloadManager = context.getSystemService(DownloadManager::class.java)
                downloadManager.remove(id)
            }
        }
    }

    fun triggerDownload(m: SelectedModel) {
        ongoing.update {
            if (!it.containsKey(m)) {
                val downloadManager = context.getSystemService(DownloadManager::class.java)
                val request = DownloadManager.Request(m.downloadLocation())
                val enqueued = downloadManager.enqueue(request)
                it + (m to DownloadDetails(enqueued, null))
            } else {
                it
            }
        }
        model.downloadStarted(m)
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Settings(uiState: State<UiState>, model: Model, downloader: Downloader) {
    val downloading by downloader.ongoing.collectAsState()
    LazyColumn(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        item { Text(text = "Models", style = MaterialTheme.typography.headlineSmall) }

            items(uiState.value.modelState.toList()) { (m, s) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ListItem(
                        leadingContent = {
                            RadioButton(
                                selected = m == uiState.value.selectedModel,
                                enabled = when (s) {
                                    ModelState.Downloaded -> true
                                    ModelState.Instantiating -> true
                                    ModelState.Instantiated -> true
                                    ModelState.InstantiationFailed -> true
                                    ModelState.DownloadFailed -> false
                                    ModelState.DownloadTriggered -> false
                                    ModelState.DoesNotExist -> false
                                },
                                onClick = {
                                    model.updateSelectedModel(m)
                                }
                            )
                        },
                        headlineContent = { Text(m.name) },
                        supportingContent = {
                            when (s) {
                                ModelState.DownloadTriggered -> {
                                    Text("Downloading - ${downloading[m]?.details}")
                                }

                                ModelState.Downloaded -> Text("Available")
                                ModelState.DoesNotExist -> Text("Not available")
                                ModelState.Instantiated -> Text("Available - Instantiated")
                                ModelState.Instantiating -> Text("Available - Instantiating")
                                ModelState.InstantiationFailed -> Text("Failed")
                                ModelState.DownloadFailed -> Text("Download failed")
                            }
                        },
                        trailingContent = {
                            when (s) {
                                ModelState.DownloadTriggered ->
                                    CircularProgressIndicator(
                                        modifier = Modifier.width(32.dp)
                                    )

                                ModelState.DoesNotExist -> IconButton(onClick = {
                                    downloader.triggerDownload(
                                        m
                                    )
                                }) { Icon(Icons.Default.Add, "Download model") }

                                ModelState.Downloaded -> {}
                                ModelState.Instantiated -> {}
                                ModelState.Instantiating -> CircularProgressIndicator(
                                    modifier = Modifier.width(
                                        32.dp
                                    )
                                )

                                ModelState.DownloadFailed -> {}
                                ModelState.InstantiationFailed -> {}
                            }
                        }
                    )
                }
            }

        item {
            val uiStateValue by uiState

            ListItem(
                headlineContent = { Text("Load model at startup") },
                trailingContent = {
                    Switch(
                        checked = uiStateValue.loadAtStartup,
                        onCheckedChange = { model.updateLoadAtStartup(it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Prompt") },
                supportingContent = {
                    TextField(
                        value = uiStateValue.prompt ?: "",
                        onValueChange = { model.changePrompt(it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Threads") },
                supportingContent = {
                    TextField(
                        value = uiStateValue.threads.toString(),
                        onValueChange = { it.toIntOrNull()?.let { model.updateThreads(it) } }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Use GPU (Vulkan)")},
                trailingContent = {
                    Switch(
                        checked = uiStateValue.useGpu,
                        onCheckedChange = { model.updateUseGpu(it) }
                    )
                }
            )
        }
    }
}