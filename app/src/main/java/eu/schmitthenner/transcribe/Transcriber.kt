package eu.schmitthenner.transcribe

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Date

sealed class TranscribeAction() {
    companion object {
        data class SelectedModelChanged(val selectedModel: SelectedModel?): TranscribeAction()
        class DataReceived(val data: List<ShortArray>, val prompt: String?): TranscribeAction()
    }
}

data class TranscribeResult(val from: Long, val to: Long, val text: String)

interface Whisper {
    fun transcribe(prompt: String?, floatArray: FloatArray, progress: (Int) -> Unit, res: (TranscribeResult) -> Unit)
    fun modelChanged(selectedModel: SelectedModel?): Boolean
    fun ready(): Boolean
    fun finalTranscription(): List<TranscribeResult>
}

class WhisperCpp(val context: Context, val numThreads: () -> Int, val useGpu: () -> Boolean): Whisper {
    init  {
        WhisperLib.initLogging()
    }

    var ptr = 0L
    var previous: SelectedModel? = null

    override fun ready(): Boolean = ptr != 0L

    override fun transcribe(prompt: String?, floatArray: FloatArray, progress: (Int) -> Unit, res: (TranscribeResult) -> Unit) {
        var already = 0
        WhisperLib.fullTranscribe(ptr, numThreads(), floatArray, progress, prompt = prompt, segmentCallback = {
            val after = already + it
            for (i in already until after) {
                val t = WhisperLib.getTextSegment(ptr, i)
                val t0 = WhisperLib.getTextSegmentT0(ptr, i)
                val t1 = WhisperLib.getTextSegmentT1(ptr, i)
                res(TranscribeResult(t0, t1, t))
            }
            already = after
        })
    }

    override fun finalTranscription(): List<TranscribeResult> {
        return (0 until WhisperLib.getTextSegmentCount(ptr)).map {
            TranscribeResult(
                WhisperLib.getTextSegmentT0(ptr, it),
                WhisperLib.getTextSegmentT1(ptr, it),
                WhisperLib.getTextSegment(ptr, it)
            )
        }
    }

    override fun modelChanged(selectedModel: SelectedModel?): Boolean {
        if (ptr != 0L && previous != selectedModel) {
            WhisperLib.freeContext(ptr)
        }

        var result = false

        if (selectedModel != null && previous != selectedModel) {
            val path = File(context.filesDir, selectedModel.fileName())
            ptr = WhisperLib.initContext(path.path, useGpu())
            result = ptr != 0L
        }
        previous = selectedModel
        return result
    }
}


class Transcriber(val context: Context, val whisper: Whisper) {
    val modelState = Channel<Pair<SelectedModel?, ModelState>>(0)
    private val transcribeAction = Channel<TranscribeAction>(0)
    val transcribedText: MutableStateFlow<String> = MutableStateFlow("")
    val counter: MutableStateFlow<Int> = MutableStateFlow(0)
    val progress: MutableStateFlow<String> = MutableStateFlow("")

    suspend fun transcribe(model: Model) {
        withContext(Dispatchers.IO) {
            while (true) {
                when (val action = transcribeAction.receive()) {
                    is TranscribeAction.Companion.SelectedModelChanged -> {
                        modelState.send(action.selectedModel to ModelState.Instantiating)
                        val state  = if (whisper.modelChanged(action.selectedModel)) {
                            ModelState.Instantiated
                        } else {
                            ModelState.InstantiationFailed
                        }
                        modelState.send(action.selectedModel to state)
                    }
                    is TranscribeAction.Companion.DataReceived -> {
                        val fullSize = action.data.sumOf { it.size }
                        val floatArray = FloatArray(fullSize)
                        var index = 0
                        progress.update { "Prepare to transcribe" }
                        for (array in action.data) {
                            for (i in array.indices) {
                                floatArray[index + i] = array[i] / 32768f
                            }
                            index += array.size
                        }
                        val before = Instant.now()
                        if (whisper.ready()) {
                            whisper.transcribe(action.prompt, floatArray, { prog -> progress.update { "Transcribing: $prog% finished" }}) {
                                t -> transcribedText.update {
                                    it + "\n[${t.from/50}-${t.to/50}] ${t.text}"
                            }
                            }
                        }

                        val segments = whisper.finalTranscription().let { s -> Array(s.size) {
                                Segment(s[it].from, s[it].to, s[it].text)
                            }
                        }

                        transcribedText.update {
                            whisper.finalTranscription().joinToString("\n") {
                                "[${it.from/50}-${it.to/50}] ${it.text}"
                            }
                        }

                        val after = Instant.now()
                        model.addTranscription(Transcription(segments, before, after))
                        transcribedText.update { "" }

                        progress.update { "Finished transcribing" }
                    }
                }
            }
        }
    }

    suspend fun run(coroutineScope: CoroutineScope, state: StateFlow<UiState>, model: Model) {
        coroutineScope.launch {
            transcribe(model)
        }

        var s: SelectedModel? = null
        state.collect {
            val n = if (it.modelState[it.selectedModel] != ModelState.Downloaded) null else it.selectedModel
            if (s != n) {
                s = n
                if (n != null) {
                    val file = File(context.filesDir, n.fileName())
                    if (!file.exists()) {
                        modelState.send(n to ModelState.DoesNotExist)
                        return@collect
                    }
                }
                transcribeAction.send(TranscribeAction.Companion.SelectedModelChanged(n))
            }
        }
    }

    suspend fun sendData(data: MutableList<ShortArray>, prompt: String?) {
        transcribeAction.send(TranscribeAction.Companion.DataReceived(data, prompt = prompt,))
    }
}