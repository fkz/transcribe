package eu.schmitthenner.transcribe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Recorder {
    suspend fun run(context: Activity, scope: CoroutineScope, transcriber: Transcriber, isRecording: StateFlow<Boolean>, prompt: StateFlow<String?>) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("Recorder", "permission check failed")
        }

        val bufferSize = 16000 * 10;
        val audioRecord: AudioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )

        scope.launch {
            isRecording.collect {
                if (it) audioRecord.startRecording() else audioRecord.stop()
            }
        }

        withContext(Dispatchers.IO) {
            while (true) {
                isRecording.dropWhile { !it }.first()
                val buffers = mutableListOf<ShortArray>()
                var buffer = ShortArray(bufferSize)
                var offset = 0
                while (true) {
                    val r = audioRecord.read(buffer, offset, bufferSize - offset, AudioRecord.READ_NON_BLOCKING)
                    if (r < 0) {
                        Log.e("MainActivity", "error while recording; stop")
                        break
                    } else if (r > 0) {
                        offset += r
                        if (offset == bufferSize) {
                            buffers.add(buffer)
                            buffer = ShortArray(bufferSize)
                            offset = 0
                        }
                    } else if (r == 0 && !isRecording.value) {
                        val lastBuffer = buffer.slice(0 until offset).toShortArray()
                        buffers.add(lastBuffer)
                        transcriber.sendData(buffers, prompt.value)
                        break
                    }
                }
            }
        }
    }
}