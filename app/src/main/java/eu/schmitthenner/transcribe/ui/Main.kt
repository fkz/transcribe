package eu.schmitthenner.transcribe.ui

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import eu.schmitthenner.transcribe.Model
import eu.schmitthenner.transcribe.ModelState
import eu.schmitthenner.transcribe.SelectedModel
import eu.schmitthenner.transcribe.Transcriber
import eu.schmitthenner.transcribe.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteOrder
import java.nio.ShortBuffer


class RecordFilePicker(context: ComponentActivity, model: Model, transcriber: Transcriber) {
    private fun resamplePcm(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
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


    val recordFilePicker = context.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            model.setPlayFromFile(true)
            context.lifecycleScope.launch(Dispatchers.IO) {
                val data = mutableListOf<ShortArray>()
                val extractor = MediaExtractor()
                run {
                    extractor.setDataSource(context, uri, null)
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
                            val outputBuffer = codec.getOutputBuffer(outputBufferId)?.order(
                                ByteOrder.nativeOrder())
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

                                data.add(resampledData)
                            }

                            codec.releaseOutputBuffer(outputBufferId, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputEOS = true
                                break
                            }

                            outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 1000)
                        }
                    }
                    codec.stop()
                    codec.release()
                    extractor.release()
                }
                transcriber.sendData(data)
            }
            model.setPlayFromFile(false)
        }
    }

    fun launch() {
        recordFilePicker.launch(arrayOf("audio/*"))
    }

    val requestPermissionLauncher = context.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            isGranted: Boolean ->
        if (isGranted) {
            model.setHasRecordPermission()
            model.toggleRecording(false)
        }
    }

    fun requestRecordPermission() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Main(uiState: State<UiState>, model: Model, recordFilePicker: RecordFilePicker, transcriber: Transcriber, showLogs: () -> Unit, context: Context) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val expanded = remember { mutableStateOf(false)}
            val currentModel = uiState.value.selectedModel
            ExposedDropdownMenuBox(expanded = expanded.value, onExpandedChange = {expanded.value = it }) {
                TextField(
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    readOnly = true,
                    value = currentModel?.name.orEmpty(),
                    label = { Text("Selected model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded.value) },
                    onValueChange = {}
                )
                ExposedDropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
                    for (i in uiState.value.modelState.filterValues { it == ModelState.Downloaded || it == ModelState.Instantiated }) {
                        DropdownMenuItem(text = { Text(i.key.name) }, onClick = {
                            model.updateSelectedModel(i.key)
                            expanded.value = false
                        })
                    }
                }
            }

            Text(
                text = uiState.value.modelState[uiState.value.selectedModel]?.name.orEmpty(),
                modifier = Modifier.padding(innerPadding)
            )
            Row {
                Button(onClick = {
                    if (uiState.value.hasRecordPermission) model.toggleRecording(
                        uiState.value.isRecording
                    ) else recordFilePicker.requestRecordPermission()
                }, enabled = uiState.value.allowRecording()) {
                    Text(if (uiState.value.isRecording) "Stop Recording" else "Record")
                }
                Button(
                    enabled = uiState.value.allowRecording() && !uiState.value.isRecording,
                    onClick = {
                        recordFilePicker.launch()
                    }
                ) {
                    Text("Transcribe from file")
                }
            }

            val transcribedTextState = transcriber.transcribedText.collectAsState()
            val transcribedTextProgress = transcriber.progress.collectAsState()

            Text(transcribedTextState.value)
            //Text(transcriptionCount.value.toString())
            Text(transcribedTextProgress.value)


            //if (uiState.value.modelState == ModelState.DoesNotExist && selectedModel != null) {
                /*Button(onClick = {
                    val downloadManager = getSystemService(DownloadManager::class.java)
                    val request = DownloadManager.Request(selectedModel.downloadLocation())
                    val enqueued = downloadManager.enqueue(request)
                    Downloader.downloader.update { Triple(enqueued, selectedModel, model) }
                    model.downloadStarted()
                }) { Text("Download") }
                Button(onClick = {
                    filePicker2.launch(arrayOf("* / *"))
                }) { Text("Upload model manually") } */
            //}
            //val transcriptionValue = transcription.value
            Button(onClick = {
                val clipboardManager = context.getSystemService(ClipboardManager::class.java)
                clipboardManager.setPrimaryClip(
                    ClipData.newPlainText("Transcript", transcribedTextState.value)
                )
            }) {
                Text("Copy transcript")
            }
            Button(onClick = {
                showLogs()
            }) {
                Text("See logs")
            }
        }
    }
}