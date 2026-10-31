package dev.komichi.agent

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.komichi.agent.agent.RunPhase
import dev.komichi.agent.agent.RunProgress
import kotlinx.coroutines.delay

@Composable internal fun RunStatusCard(progress: RunProgress, loadingModels: Boolean, idleText: String, onStop: () -> Unit) {
    var now by remember(progress.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.active,progress.startedAt) { while(progress.active) { now = System.currentTimeMillis(); delay(1000) } }
    val active = progress.active || loadingModels
    val seconds = ((if(progress.active) now else progress.finishedAt) - progress.startedAt).coerceAtLeast(0) / 1000
    val elapsed = "%02d:%02d".format(seconds/60,seconds%60)
    val error = progress.phase == RunPhase.FAILED
    Surface(color = if(active) MaterialTheme.colorScheme.primaryContainer else if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp,vertical = 6.dp).testTag("runStatus").semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(horizontal = 14.dp,vertical = if(active) 12.dp else 8.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if(active) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.padding(top = 4.dp).size(18.dp),strokeWidth = 2.dp)
                    Column(Modifier.weight(1f)) {
                        Text("動作中",style = MaterialTheme.typography.titleSmall,fontWeight = FontWeight.Bold)
                        Text(if(progress.active) progress.phase.label else "モデル一覧を取得中",style = MaterialTheme.typography.bodySmall)
                    }
                    if(progress.active) Text(elapsed,style = MaterialTheme.typography.labelMedium,modifier = Modifier.padding(top = 8.dp).testTag("runElapsed"))
                    TextButton(onClick = onStop,contentPadding = PaddingValues(horizontal = 8.dp)) { Text("停止",color = MaterialTheme.colorScheme.error) }
                }
                if(progress.active) {
                    Text(progress.detail,style = MaterialTheme.typography.bodySmall,maxLines = 2)
                    if(progress.step > 0) Text("操作 ${progress.step}/${progress.maximum} · ${progress.tool}",style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(if(progress.phase == RunPhase.IDLE || idleText != progress.detail) idleText else "${progress.phase.label} · $idleText",style = MaterialTheme.typography.bodySmall,maxLines = 3)
            }
        }
    }
}
