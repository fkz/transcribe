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

sealed abstract class TranscribeAction() {
    companion object {
        data class SelectedModelChanged(val selectedModel: SelectedModel?): TranscribeAction()
        data object DataReceived: TranscribeAction()
    }
}

const val size = 16000 * 30 * 2

class RingBuffer {
    private val values: ShortArray = ShortArray(size)
    private var startOffset = 0
    private var endOffset = -1

    private val seekChannel = Channel<Unit>(1)
    private val storeChannel = Channel<Unit>(1)

    private var isStopped = false

    fun isStopped(): Boolean {
        return synchronized(this) {
            isStopped
        }
    }

    fun setIsStopped() {
        synchronized(this) {
            isStopped = true
        }
    }

    fun size(): Int {
        synchronized(this) {
            if (endOffset == -1) {
                return 0
            }
            val result = endOffset - startOffset
            if (result <= 0) {
                return result + size
            } else {
                return result
            }
        }
    }

    fun seek(value: Int) {
        synchronized(this) {
            if (value > size()) {
                throw IllegalStateException()
            }
            startOffset = (startOffset + value) % size
            isStopped = false
        }
        seekChannel.trySend(Unit)
    }

    fun read(maxSize: Int): ShortArray {
        synchronized(this) {
            val s = min(maxSize, size())
            return ShortArray(s) { i ->
                values[(startOffset + i) % size]
            }
        }
    }

    suspend fun waitForMoreThan(minSize: Int) {
        while (true) {
            val done = synchronized(this) {
                size() > minSize || isStopped
            }
            if (done) return
            storeChannel.receive()
        }
    }

    suspend fun store(data: ShortArray, dataSize: Int) {
        var offset = 0
        while (true) {
            synchronized(this) {
                isStopped = false
                val capacity = min(size - size(), dataSize - offset)
                if (capacity > 0) {
                    if (endOffset == -1) {
                        endOffset = startOffset
                    }
                    for (i in 0 until capacity) {
                        values[(endOffset + i) % size] = data[offset + i]
                    }
                    endOffset = (endOffset + capacity) % size
                    offset += capacity
                }
            }
            storeChannel.trySend(Unit)
            if (offset == dataSize) {
                break
            }
            seekChannel.receive()
        }
    }
}

data class TranscribeResult(val text: String, val highOffset: Int)

interface Whisper {
    fun transcribe(floatArray: FloatArray, offset: Int, length: Int): TranscribeResult
    fun modelChanged(selectedModel: SelectedModel?): Boolean
    fun ready(): Boolean
}

class WhisperCpp(val context: Context): Whisper {
    init  {
        WhisperLib.initLogging()
    }

    var ptr = 0L
    var previous: SelectedModel? = null

    override fun ready(): Boolean = ptr != 0L

    override fun transcribe(floatArray: FloatArray, offset: Int, length: Int): TranscribeResult {
        WhisperLib.fullTranscribe(ptr, 4, offset / 16, floatArray, floatArray.size, {

        }, length / 16)

        val segments = WhisperLib.getTextSegmentCount(ptr)
        //var res = "$segments,$minSize, ${result.size} $offsetMs}\n"
        var res = ""
        for (i in 0 until WhisperLib.getTextSegmentCount(ptr)) {
            res += "(${WhisperLib.getTextSegmentT0(ptr, i)}-${WhisperLib.getTextSegmentT1(ptr, i)}) ${WhisperLib.getTextSegment(ptr, i)}\n"
        }
        var seek = 0
        for (i in (0 until segments).reversed()) {
            val s = 160 * WhisperLib.getTextSegmentT1(ptr, segments - 1).toInt()
            res += "($i,$s)"
            if (s <= length) {
                seek = s
                break
            }
        }
        return TranscribeResult(res, seek)
    }

    override fun modelChanged(selectedModel: SelectedModel?): Boolean {
        if (ptr != 0L && previous != selectedModel) {
            WhisperLib.freeContext(ptr)
        }

        var result = false

        if (selectedModel != null && previous != selectedModel) {
            val path = File(context.dataDir, selectedModel.fileName())
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

    val buffer = RingBuffer()

    suspend fun transcribe(coroutineScope: CoroutineScope) {
        var startPadding = 0

        withContext(Dispatchers.IO) {
            var offsetMs = 0
            var minSize = 0
            while (true) {
                val action = select {
                    transcribeAction.onReceive { it }
                    coroutineScope.async { buffer.waitForMoreThan(minSize) }.onAwait { TranscribeAction.Companion.DataReceived }
                }
                when (action) {
                    is TranscribeAction.Companion.SelectedModelChanged -> {
                        val state  = if (whisper.modelChanged(action.selectedModel)) {
                            ModelState.Instantiated
                        } else {
                            ModelState.InstantiationFailed
                        }
                        modelState.send(action.selectedModel to state)
                    }
                    is TranscribeAction.Companion.DataReceived -> {
                        val isStopped = buffer.isStopped()
                        if (whisper.ready() && (buffer.size() >= 16000 * 30 || isStopped)) {
                            val result = buffer.read(31 * 16000 + 320)
                            val floatArray = FloatArray(result.size) { (result[it] / 32767.0f).coerceIn(-1f..1f) }
                            counter.update { it + 1 }
                            progress.update { "current: ${result.size}" }
                            val duration = (floatArray.size - startPadding) - (if (isStopped) 0 else 1000 * 16); // keep at least 2 seconds in the end as overlap window
                            val transcribeResult = whisper.transcribe(floatArray, startPadding, duration)
                            val seek = transcribeResult.highOffset
                            val increasePadding = min(seek, 320 - startPadding)
                            buffer.seek(seek - increasePadding)
                            startPadding += increasePadding
                            minSize = result.size - seek
                            offsetMs += seek / 16
                            transcribedText.update { it + transcribeResult.text }
                            progress.update { "minSize: $minSize" }
                        }
                    }
                }
            }
        }
    }

    suspend fun pushData(shortArray: ShortArray, size: Int) {
        buffer.store(shortArray, size)
    }

    fun recordingStopped() {
        buffer.setIsStopped()
    }

    suspend fun run(coroutineScope: CoroutineScope, state: StateFlow<UiState>) {
        coroutineScope.launch {
            transcribe(coroutineScope)
        }

        state.mapNotNull { if (it.modelState != ModelState.Unknown) null else it.selectedModel }.collect { selectedModel ->
            val file = File(context.dataDir, selectedModel.fileName())
            if (file.exists()) {
                modelState.send(selectedModel to ModelState.Downloaded)
                transcribeAction.send(TranscribeAction.Companion.SelectedModelChanged(selectedModel))
            } else {
                modelState.send(selectedModel to ModelState.DoesNotExist)
            }
        }
    }
}