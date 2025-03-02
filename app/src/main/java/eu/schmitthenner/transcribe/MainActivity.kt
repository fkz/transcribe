package eu.schmitthenner.transcribe

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import eu.schmitthenner.transcribe.ui.theme.TransccribeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedReader
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.ui.unit.dp
import eu.schmitthenner.transcribe.ui.Downloader
import eu.schmitthenner.transcribe.ui.Main
import eu.schmitthenner.transcribe.ui.RecordFilePicker
import eu.schmitthenner.transcribe.ui.Settings
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteOrder


class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("MainActivity", "MainActivity started")
        val model = ViewModelProvider(this).get(Model::class)
        model.initialize(this)

        val transcriber = Transcriber(this, WhisperCpp(this))
        val recorder = Recorder()

        val recordFilePicker = RecordFilePicker(this@MainActivity, model, transcriber)

        val filePickerLogs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            logFile: Uri? ->
            if (logFile != null) {
                val process = Runtime.getRuntime().exec("logcat -d")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val log = StringBuilder()
                var line: String?

                while ((reader.readLine().also { line = it }) != null) {
                    log.append(line).append("\n")
                }

                val writer = contentResolver.openOutputStream(logFile)
                writer?.write(log.toString().toByteArray())
                writer?.close()
            }
        }

        val downloader = Downloader(this, model)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            model.setHasRecordPermission()
        }


        lifecycleScope.launch {
            transcriber.modelState.receiveAsFlow().collect {
                model.updateModelState(it.first, it.second)
            }
        }

        lifecycleScope.launch {
            transcriber.run(this, model.uiState)
        }

        val self = this
        lifecycleScope.launch {
            val isRecording = model.uiState.map { it.isRecording }.dropWhile { !it }.stateIn(this)
            val prompt = model.uiState.map { it.prompt }.stateIn(this)
            recorder.run(self, self.lifecycleScope, transcriber, isRecording, prompt)
        }

        @OptIn(ExperimentalMaterial3Api::class)
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val uiState = model.uiState.collectAsState()
            val transcription = transcriber.transcribedText.collectAsState();
            val transcriptionCount = transcriber.counter.collectAsState()
            val progress = transcriber.progress.collectAsState()
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            var selectedItem by remember { mutableStateOf("Main") }
            val items = listOf("Main", "Settings")

        TransccribeTheme {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        items.forEach { item ->
                            NavigationDrawerItem(
                                label = { Text(item) },
                                selected = item == selectedItem,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    selectedItem = item
                                },
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            ) {
                Scaffold(topBar = {
                    TopAppBar(navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                drawerState.open()
                            }
                        }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Localized description")
                        }

                    }, title = {
                        Text("Transccribe")
                    })
                }) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (selectedItem == "Main") {
                            Main(uiState, model, recordFilePicker, transcriber, { filePickerLogs.launch("logs.txt")}, this@MainActivity)
                        } else if(selectedItem == "Settings") {
                            Settings(uiState, model, downloader)
                            }
                        }
                    }
                }

            }
        }
    }
}
