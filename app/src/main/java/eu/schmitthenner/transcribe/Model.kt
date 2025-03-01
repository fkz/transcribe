package eu.schmitthenner.transcribe

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select

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
    Unknown,
    DoesNotExist,
    DownloadTriggered,
    Downloaded,
    InstantiationFailed,
    Instantiated
}

data class UiState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val selectedModel: SelectedModel? = null,
    val modelState: ModelState = ModelState.DoesNotExist,
    val hasRecordPermission: Boolean = false,
) {
    fun allowRecording(): Boolean = selectedModel != null && modelState == ModelState.Instantiated && !isPlaying
}


class Model: ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    fun updateModelState(selectedModel: SelectedModel?, modelState: ModelState) {
        _uiState.update {
            if (it.selectedModel == selectedModel) {
                it.copy(modelState = modelState)
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
            it.copy(selectedModel = selectedModel, modelState = ModelState.Unknown)
        }
    }

    fun downloadStarted() {
        _uiState.update {
            it.copy(modelState = ModelState.DownloadTriggered)
        }
    }

    fun downloadFinished() {
        _uiState.update {
            it.copy(modelState = ModelState.Unknown)
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
}