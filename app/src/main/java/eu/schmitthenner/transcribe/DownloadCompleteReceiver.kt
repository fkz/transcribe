package eu.schmitthenner.transcribe

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import java.io.File


class DownloadCompleteReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("DownloadCompleteReceiver", "received download compeleted event")
        /*val v = Downloader.downloader.value
        if (v != null) {
            val uri = context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(v.first)
            if (uri != null) {
                val uri2 = File(context.dataDir, v.second.fileName())
                Log.i("DownloadCompleteReceiver", "about to copy $uri to $uri2")

                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    val stream2 = context.contentResolver.openOutputStream(uri2.toUri())
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
                v.third.downloadFinished(v.second)
            }
        }*/
    }
}
