package eu.schmitthenner.transcribe

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant

enum class SelectedModel {
    LargeV3 {
        override fun fileName(): String {
            return "ggml-large-v3.bin"
        }
    },
    LargeV3Turbo {
        override fun fileName(): String {
            return "ggml-large-v3-turbo.bin"
        }
    },
    LargeV3TurboQuant {
        override fun fileName(): String {
            return "ggml-large-v3-turbo-q5_0.bin"
        }
    },
    Medium {
        override fun fileName(): String {
            return "ggml-medium.bin"
        }
    },
    Small {
        override fun fileName(): String {
            return "ggml-small.bin"
        }
    },
    Base {
        override fun fileName(): String {
            return "ggml-base.bin"
        }
    },
    Tiny {
        override fun fileName(): String {
            return "ggml-tiny.bin"
        }
    };

    abstract fun fileName(): String

    fun downloadLocation(): Uri {
        return Uri.parse("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${fileName()}")
    }
}

enum class ModelState {
    DoesNotExist,
    DownloadTriggered,
    Downloaded,
    DownloadFailed,
    Instantiating,
    InstantiationFailed,
    Instantiated
}

data class Segment(val from: Long, val to: Long, val text: String)
data class Transcription(val segments: Array<Segment>, val before: Instant, val after: Instant)

data class UiState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val selectedModel: SelectedModel? = null,
    val modelState: Map<SelectedModel, ModelState> = mapOf(),
    val hasRecordPermission: Boolean = false,
    val prompt: String? = null,
    val transcriptions: List<Transcription> = listOf(),
    val threads: Int = 4,
    val loadAtStartup: Boolean = true,
    val useGpu: Boolean = false,
) {
    fun allowRecording(): Boolean = selectedModel != null && modelState[selectedModel] == ModelState.Instantiated && !isPlaying
}


class Model(): ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()
    lateinit var sharedPreferences: SharedPreferences

    fun updateModelState(selectedModel: SelectedModel?, modelState: ModelState) {
        _uiState.update {
            if (it.selectedModel == selectedModel && selectedModel != null) {
                it.copy(modelState = it.modelState + (selectedModel to modelState))
            } else {
                it
            }
        }
    }

    fun toggleRecording(record: Boolean) {
        _uiState.update {
            it.copy(isRecording = !it.isRecording)
        }
    }

    fun updateSelectedModel(selectedModel: SelectedModel?) {
        _uiState.update {
            it.copy(
                selectedModel = selectedModel,
                modelState =
                    if (it.selectedModel != null)
                        it.modelState + (it.selectedModel to ModelState.Downloaded)
                    else
                        it.modelState
            )
        }
        sharedPreferences.edit(commit = true) {
            putString("selectedMode", selectedModel?.name)
        }
    }

    fun downloadStarted(model: SelectedModel) {
        _uiState.update {
            it.copy(modelState = it.modelState + (model to ModelState.DownloadTriggered))
        }
    }

    fun downloadFinished(model: SelectedModel) {
        _uiState.update {
            it.copy(modelState = it.modelState + (model to ModelState.Downloaded))
        }
    }

    fun setHasRecordPermission() {
        _uiState.update {
            it.copy(hasRecordPermission = true)
        }
    }

    fun setPlayFromFile(value: Boolean) {
        _uiState.update {
            it.copy(isPlaying = value)
        }
    }

    fun initialize(context: Context) {
        sharedPreferences = context.getSharedPreferences("model", Context.MODE_PRIVATE)
        _uiState.update {
            val modelState = SelectedModel.entries.associateWith { key ->
                val f = File(context.filesDir, key.fileName())
                if (f.exists()) ModelState.Downloaded else ModelState.DoesNotExist
            }
            val loadAtStartup = sharedPreferences.getBoolean("loadAtStartup", true)
            val maybeSelectedModel = sharedPreferences.getString("selectedMode", null)?.let { SelectedModel.valueOf(it) }
            val selectedModel = if (loadAtStartup && modelState[maybeSelectedModel] == ModelState.Downloaded) {
                maybeSelectedModel
            } else {
                null
            }
            it.copy(
                modelState = modelState,
                selectedModel = selectedModel,
                prompt = sharedPreferences.getString("prompt", ""),
                threads = sharedPreferences.getInt("threads", 4),
                loadAtStartup = loadAtStartup,
                useGpu = sharedPreferences.getBoolean("useGpu", false),
            )
        }
    }

    fun changePrompt(prompt: String) {
        _uiState.update {
            it.copy(prompt = prompt)
        }
        sharedPreferences.edit(commit = true) {
            putString("prompt", prompt)
        }
    }

    fun addTranscription(transcription: Transcription) {
        _uiState.update { it.copy(transcriptions = it.transcriptions + transcription )}
    }

    fun updateThreads(threads: Int) {
        _uiState.update { it.copy(threads = threads) }
        sharedPreferences.edit(commit = true) {
            putInt("threads", threads)
        }
    }

    fun updateUseGpu(useGpu: Boolean) {
        _uiState.update { it.copy(useGpu = useGpu, selectedModel = null,
            modelState =
                if (it.selectedModel != null)
                    it.modelState + (it.selectedModel to ModelState.Downloaded)
                else
                    it.modelState
            )
        }
        sharedPreferences.edit(commit = true) {
            putBoolean("useGpu", useGpu)
        }
    }

    fun updateLoadAtStartup(checked: Boolean) {
        _uiState.update { it.copy(loadAtStartup = checked) }
        sharedPreferences.edit(commit = true) {
            putBoolean("loadAtStartup", checked)
        }
    }
}