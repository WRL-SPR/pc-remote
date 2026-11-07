package dev.pcremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

/** Shows what's new, downloads the APK (checked against the release's SHA-256) and opens the installer. */
@Composable
fun UpdateDialog(info: ReleaseInfo, currentVersion: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloaded by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun installOrAskPermission(file: File) {
        if (Updates.canInstall(context)) {
            Updates.install(context, file)
        } else {
            message = "\"이 출처 허용\"을 켠 뒤 돌아와서 [설치]를 다시 눌러 주세요"
            Updates.openInstallPermissionSettings(context)
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null || downloaded != null) onDismiss() },
        icon = { Icon(AppIcons.Desktop, contentDescription = null) },
        title = { Text("새 버전 ${info.appVersion}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "지금 설치된 버전: $currentVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "업데이트 내역",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(info.notes.ifBlank { "내역이 없습니다." }, style = MaterialTheme.typography.bodyMedium)
                progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (downloaded != null) "내려받기 완료" else "내려받는 중… ${(p * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress == null || downloaded != null,
                onClick = {
                    val file = downloaded
                    if (file != null) {
                        installOrAskPermission(file)
                    } else {
                        scope.launch {
                            message = null
                            progress = 0f
                            try {
                                val f = Updates.download(context, info) { progress = it }
                                downloaded = f
                                progress = 1f
                                installOrAskPermission(f)
                            } catch (e: Exception) {
                                message = e.message ?: "내려받지 못했습니다"
                                progress = null
                            }
                        }
                    }
                },
            ) { Text(if (downloaded != null) "설치" else "업데이트", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = progress == null || downloaded != null) { Text("나중에") }
        },
    )
}
