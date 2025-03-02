package eu.schmitthenner.transcribe

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

sealed class TranscribeAction() {
    companion object {
        data class SelectedModelChanged(val selectedModel: SelectedModel?): TranscribeAction()
        class DataReceived(val data: List<ShortArray>): TranscribeAction()
    }
}

data class TranscribeResult(val from: Long, val to: Long, val text: String)

interface Whisper {
    fun transcribe(floatArray: FloatArray, progress: (Int) -> Unit, res: (TranscribeResult) -> Unit)
    fun modelChanged(selectedModel: SelectedModel?): Boolean
    fun ready(): Boolean
    fun finalTranscription(): List<TranscribeResult>
}

class WhisperCpp(val context: Context): Whisper {
    init  {
        WhisperLib.initLogging()
    }

    var ptr = 0L
    var previous: SelectedModel? = null

    override fun ready(): Boolean = ptr != 0L

    override fun transcribe(floatArray: FloatArray, progress: (Int) -> Unit, res: (TranscribeResult) -> Unit) {
        var already = 0
        WhisperLib.fullTranscribe(ptr, 8, floatArray, progress) {
            for (i in already until it) {
                val t = WhisperLib.getTextSegment(ptr, i)
                val t0 = WhisperLib.getTextSegmentT0(ptr, i)
                val t1 = WhisperLib.getTextSegmentT1(ptr, i)
                res(TranscribeResult(t0, t1, t))
            }
            already = it
        }
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
            ptr = WhisperLib.initContext(path.path)
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

    suspend fun transcribe(coroutineScope: CoroutineScope) {
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
                            for (i in 0 until array.size) {
                                floatArray[index + i] = array[i] / 32768f
                            }
                            index += array.size
                        }
                        if (whisper.ready()) {
                            whisper.transcribe(floatArray, { prog -> progress.update { "Transcribing: $prog% finished" }}) {
                                t -> transcribedText.update {
                                    it + "\n[${t.from/50}-${t.to/50}] ${t.text}"
                            }
                            }
                        }
                        transcribedText.update {
                            it + "----\n" + whisper.finalTranscription().joinToString("\n") {
                                "[${it.from/50}-${it.to/50}] ${it.text}"
                            } + "----\n"
                        }
                        progress.update { "Finished transcribing" }
                    }
                }
            }
        }
    }

    suspend fun run(coroutineScope: CoroutineScope, state: StateFlow<UiState>) {
        coroutineScope.launch {
            transcribe(coroutineScope)
        }

        state.mapNotNull { if (it.modelState[it.selectedModel] != ModelState.Downloaded) null else it.selectedModel }.collect { selectedModel ->
            val file = File(context.filesDir, selectedModel.fileName())
            if (file.exists()) {
                transcribeAction.send(TranscribeAction.Companion.SelectedModelChanged(selectedModel))
            } else {
                modelState.send(selectedModel to ModelState.DoesNotExist)
            }
        }
    }

    suspend fun sendData(data: MutableList<ShortArray>) {
        transcribeAction.send(TranscribeAction.Companion.DataReceived(data))
    }
}