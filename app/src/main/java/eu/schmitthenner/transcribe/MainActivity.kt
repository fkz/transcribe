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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
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
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteOrder


object Downloader {
    val downloader: MutableStateFlow<Triple<Long, SelectedModel, Model>?> = MutableStateFlow(null)
}

class MainActivity : ComponentActivity() {
    fun resamplePcm(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        if (inputRate == outputRate) return input // No resampling needed

        val ratio = outputRate.toFloat() / inputRate
        val newSize = (input.size * ratio).toInt()
        val resampled = ShortArray(newSize)

        for (i in resampled.indices) {
            val srcIndex = i / ratio
            val srcIndexInt = srcIndex.toInt()
            val srcIndexFrac = srcIndex - srcIndexInt

            val sample1 = input.getOrElse(srcIndexInt) { 0 }
            val sample2 = input.getOrElse(srcIndexInt + 1) { sample1 }

            resampled[i] = (sample1 + (sample2 - sample1) * srcIndexFrac).toInt().toShort()
        }

        return resampled
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i("MainActivity", "MainActivity started")
        val model = ViewModelProvider(this).get(Model::class)

        val transcriber = Transcriber(this, WhisperCpp(this))
        val recorder = Recorder()

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
                if (writer != null) {
                    model.downloadFinished()
                }
            }
        }

        val filePicker2 = registerForActivityResult((ActivityResultContracts.OpenDocument())) { uri: Uri? ->
            Log.i("DownloadCompleteReceiver", "Picked manually")
            val uri2 = File(dataDir, model.uiState.value.selectedModel!!.fileName())
            if (uri != null) {
                val stream = contentResolver.openInputStream(uri)
                if (stream != null) {
                    val stream2 = contentResolver.openOutputStream(uri2.toUri())
                    if (stream2 != null) {
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (stream.read(buffer).also { bytesRead = it } != -1) {
                            stream2.write(buffer, 0, bytesRead)
                        }
                        Log.i("DownloadCompleteReceiver", "Successfully copied files")
                        stream2.close()
                    }
                    stream.close()
                }
            }
        }

        val recordFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                model.setPlayFromFile(true)
                lifecycleScope.launch(Dispatchers.IO) {
                    val extractor = MediaExtractor()
                    run {
                        extractor.setDataSource(this@MainActivity, uri, null)
                        extractor.selectTrack(0)

                        val mediaFormat = extractor.getTrackFormat(0)
                        val targetSampleRate = 16000  // Our desired output sample rate

                        //val pcmEnc = mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        val inputSampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)


                        val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mediaFormat)
                            ?: throw RuntimeException("No suitable decoder found")

                        val codec = MediaCodec.createByCodecName(codecName)
                        //mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 16000)

                        codec.configure(mediaFormat, null, null, 0)


                        codec.start()

                        //Log.i("MainAct", "Output format: $pcmEnc $inputSampleRate $channels ${codec.outputFormat}")


                        val bufferInfo = MediaCodec.BufferInfo()
                        var isEOS = false
                        var outputEOS = false

                        while (!outputEOS) {
                            // Feed input to the decoder
                            if (!isEOS) {
                                val inputBufferId = codec.dequeueInputBuffer(1000)
                                if (inputBufferId >= 0) {
                                    val inputBuffer = codec.getInputBuffer(inputBufferId) ?: continue
                                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(
                                            inputBufferId, 0, 0, 0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        isEOS = true
                                    } else {
                                        codec.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                                        extractor.advance()
                                    }
                                }
                            }

                            // Retrieve output from the decoder
                            var outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 1000)
                            while (outputBufferId >= 0) {
                                val outputBuffer = codec.getOutputBuffer(outputBufferId)?.order(ByteOrder.nativeOrder())
                                val shortBuffer = outputBuffer?.asShortBuffer()

                                if (shortBuffer != null) {
                                    val size = shortBuffer.remaining()


                                    val rawPcmData = ShortArray(size / channels) { shortBuffer.get(channels*it) }

                                    // Resample if needed
                                    val resampledData = if (inputSampleRate != targetSampleRate) {
                                        resamplePcm(rawPcmData, inputSampleRate, targetSampleRate)
                                    } else {
                                        rawPcmData
                                    }

                                    transcriber.pushData(resampledData, resampledData.size)
                                }

                                codec.releaseOutputBuffer(outputBufferId, false)

                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    outputEOS = true
                                    break
                                }

                                outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 1000)
                            }
                        }
                        transcriber.recordingStopped()
                        codec.stop()
                        codec.release()
                        extractor.release()
                    }
                }
                model.setPlayFromFile(false)
            }
        }


        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted: Boolean ->
            if (isGranted) {
                model.setHasRecordPermission()
                model.toggleRecording(false)
            }
        }

        val recordPermission: () -> Unit = {
            requestPermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO)
        }

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
            recorder.run(self, self.lifecycleScope, transcriber, isRecording)
        }

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val uiState = model.uiState.collectAsState()
            val transcription = transcriber.transcribedText.collectAsState();
            val transcriptionCount = transcriber.counter.collectAsState()
            val progress = transcriber.progress.collectAsState()
            TransccribeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Greeting(
                            name = uiState.value.modelState.name,
                            modifier = Modifier.padding(innerPadding)
                        )
                        Row {
                            Button(onClick = {
                                if (uiState.value.hasRecordPermission) model.toggleRecording(
                                    uiState.value.isRecording
                                ) else recordPermission()
                            }, enabled = uiState.value.allowRecording()) {
                                Text(if (uiState.value.isRecording) "Stop Recording" else "Record")
                            }
                            Button(
                                enabled = uiState.value.allowRecording() && !uiState.value.isRecording,
                                onClick = {
                                    recordFilePicker.launch(arrayOf("audio/*"))
                                }
                            ) {
                                Text("Transcribe from file")
                            }
                        }

                        Text(transcription.value)
                        Text(transcriptionCount.value.toString())
                        Text(progress.value.toString())

                        val expanded = remember { mutableStateOf(false) }
                        Text("Selected model: " + uiState.value.selectedModel?.name.orEmpty())
                        Button(onClick = { expanded.value = true }) {
                            Text("Switch model")
                        }
                        val selectedModel = uiState.value.selectedModel
                        if (uiState.value.modelState == ModelState.DoesNotExist && selectedModel != null) {
                            Button(onClick = {
                                val downloadManager = getSystemService(DownloadManager::class.java)
                                val request = DownloadManager.Request(selectedModel.downloadLocation())
                                val enqueued = downloadManager.enqueue(request)
                                Downloader.downloader.update { Triple(enqueued, selectedModel, model) }
                                model.downloadStarted()
                            }) { Text("Download") }
                            Button(onClick = {
                                filePicker2.launch(arrayOf("*/*"))
                            }) { Text("Upload model manually")}
                        }

                        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                            for (i in SelectedModel.entries) {
                                DropdownMenuItem(text = { Text(i.name)}, onClick = {
                                    model.updateSelectedModel(i)
                                    expanded.value = false
                                })
                            }
                        }
                        val transcriptionValue = transcription.value
                        Button(onClick = {
                            val clipboardManager = getSystemService(ClipboardManager::class.java)
                            clipboardManager.setPrimaryClip(
                                ClipData.newPlainText("Transcript", transcriptionValue)
                            )
                        }) {
                            Text("Copy transcript")
                        }
                        Button(onClick = {
                            filePickerLogs.launch("logs.txt")
                        }) {
                            Text("See logs")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TransccribeTheme {
        Greeting("Android")
    }
}