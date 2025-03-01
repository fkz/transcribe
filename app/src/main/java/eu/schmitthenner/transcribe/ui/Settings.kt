package eu.schmitthenner.transcribe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ModelInfo(val name: String, var downloaded: Boolean, var downloading: Boolean = false)


fun dummyModels() : List<ModelInfo>{
    return listOf(
        ModelInfo("tiny", true),
        ModelInfo("base", true),
        ModelInfo("small", false),
        ModelInfo("medium", false),
        ModelInfo("large", false)
    )
}
@Composable
fun Settings() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = "Models", style = MaterialTheme.typography.headlineSmall)
        val models = remember { mutableStateListOf(*dummyModels().toTypedArray()) }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(models) { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model.name,
                        modifier = Modifier.weight(1f)
                    )

                    if (model.downloading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.width(32.dp)
                        )
                    } else {
                        Button(
                            onClick = {
                                if (!model.downloaded) {
                                    model.downloading = true
                                    // Simulate download process
                                    android.os.Handler().postDelayed({
                                        model.downloaded = true
                                        model.downloading = false
                                    }, 2000)
                                }
                            },
                            enabled = !model.downloaded
                        ) {
                            Text(if (model.downloaded) "Downloaded" else "Download")
                        }
                    }
                }
            }
        }
    }
}