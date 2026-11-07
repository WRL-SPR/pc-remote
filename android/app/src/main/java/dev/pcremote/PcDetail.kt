package dev.pcremote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a PC's status looks like on screen, shared by the card, the list and the detail pane. */
data class StatusUi(val color: Color, val text: String, val address: String)

@Composable
fun statusUi(pc: Pc, state: PcState?, busy: String?): StatusUi {
    val color = when {
        state?.stream == true -> StatusColors.ready
        busy != null || state?.agent == true -> StatusColors.waiting
        else -> StatusColors.off
    }
    val outside = state?.route == Route.REMOTE && state.on
    val text = busy ?: when {
        state == null -> "확인 중…"
        state.stream -> "원격 접속 가능"
        state.agent -> "윈도우 켜짐 · Apollo 응답 없음"
        else -> "꺼짐"
    }.let { if (outside) "$it · 외부 연결" else it }
    val address = pc.remoteHost?.takeIf { outside }?.let { "$it (DDNS)" } ?: pc.host
    return StatusUi(color, text, address)
}

private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * Power-on and Artemis buttons. Only what can be used now is shown: power-on while the PC is off;
 * Artemis is enabled once Apollo answers, i.e. the PC is fully up and streamable.
 */
@Composable
fun PcActionButtons(
    state: PcState?,
    busy: String?,
    inner: Dp,
    large: Boolean,
    onWake: () -> Unit,
    onStream: () -> Unit,
) {
    val streamReady = state?.stream == true
    val off = state?.on != true && busy == null
    val height = if (large) 56.dp else 48.dp
    val shape = RoundedCornerShape(if (large) 18.dp else 16.dp)
    val wakeButton: @Composable (Modifier) -> Unit = { m ->
        Button(onClick = onWake, modifier = m.height(height), shape = shape, contentPadding = CompactButtonPadding) {
            Icon(AppIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("전원 켜기", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val streamButton: @Composable (Modifier) -> Unit = { m ->
        Button(
            onClick = onStream,
            enabled = streamReady,
            modifier = m.height(height),
            shape = shape,
            contentPadding = CompactButtonPadding,
            colors = ButtonDefaults.buttonColors(disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (streamReady) "Artemis 접속" else "Artemis",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (streamReady) FontWeight.SemiBold else null,
            )
        }
    }
    when {
        !off -> streamButton(Modifier.fillMaxWidth())
        inner >= 220.dp -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            wakeButton(Modifier.weight(1f))
            streamButton(Modifier.weight(1f))
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            wakeButton(Modifier.fillMaxWidth())
            streamButton(Modifier.fillMaxWidth())
        }
    }
}

/** Sleep / hibernate / restart / shutdown, shown only while Windows is running. */
@Composable
fun PowerTiles(state: PcState?, busy: String?, inner: Dp, onPower: (PowerAction) -> Unit) {
    if (state?.agent != true) return
    // A row of 4 (icon over label) on wide cards, 2 x 2 (icon beside label) otherwise.
    val wide = inner >= 340.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PowerAction.entries.chunked(if (wide) 4 else 2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    PowerTile(
                        action = action,
                        stacked = wide,
                        enabled = busy == null,
                        modifier = Modifier.weight(1f),
                        onClick = { onPower(action) },
                    )
                }
            }
        }
    }
}

@Composable
fun DefaultBadge() {
    Text(
        "기본",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun BusyBar(busy: String?, color: Color) {
    if (busy == null) return
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        color = color,
        trackColor = color.copy(alpha = 0.15f),
    )
}

/** Left pane on wide screens: one compact row per PC. */
@Composable
fun PcListPane(
    pcs: List<Pc>,
    states: Map<String, PcState>,
    busy: Map<String, String>,
    selectedId: String,
    defaultId: String?,
    onSelect: (Pc) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(start = 12.dp, end = 4.dp, bottom = 16.dp)) {
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(pcs, key = { it.id }) { pc ->
                val ui = statusUi(pc, states[pc.id], busy[pc.id])
                val selected = pc.id == selectedId
                Surface(
                    onClick = { onSelect(pc) },
                    shape = RoundedCornerShape(18.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(ui.color.copy(alpha = 0.14f), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Desktop, contentDescription = null, modifier = Modifier.size(22.dp), tint = ui.color)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pc.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (pcs.size > 1 && pc.id == defaultId) {
                                    Spacer(Modifier.width(6.dp))
                                    DefaultBadge()
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).background(ui.color, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    ui.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("PC 추가")
        }
    }
}

/**
 * Everything about one PC: status and actions, connection settings and recent activity.
 * Right pane on wide screens; full screen on narrow ones (with the edit button in the top bar).
 */
@Composable
fun PcDetailPane(
    pc: Pc,
    state: PcState?,
    busy: String?,
    isDefault: Boolean,
    showDefaultBadge: Boolean,
    latestAgentVersion: String?,
    activities: List<Activity>,
    showEdit: Boolean,
    onWake: () -> Unit,
    onStream: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 24.dp,
) {
    val ui = statusUi(pc, state, busy)
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val side = if (maxWidth < 360.dp) 12.dp else 16.dp
        val contentWidth = minOf(maxWidth, 960.dp) - side * 2
        // Connection info and activity side by side from ~500dp (Fold 7/8 inner screen beside the list).
        val twoColumns = contentWidth >= 480.dp
        val heroPad = if (contentWidth < 320.dp) 16.dp else 22.dp
        val inner = contentWidth - heroPad * 2
        Column(
            Modifier
                .widthIn(max = 960.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = side, end = side, top = 8.dp, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.padding(heroPad), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(64.dp).background(ui.color.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Desktop, contentDescription = null, modifier = Modifier.size(34.dp), tint = ui.color)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pc.name,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (showDefaultBadge && isDefault) {
                                    Spacer(Modifier.width(8.dp))
                                    DefaultBadge()
                                }
                            }
                            Text(
                                ui.address,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (showEdit) {
                            IconButton(onClick = onEdit) {
                                Icon(Icons.Outlined.Edit, contentDescription = "편집", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    StatusPill(ui.text, ui.color)
                    BusyBar(busy, ui.color)
                    PcActionButtons(state, busy, inner, large = true, onWake = onWake, onStream = onStream)
                    PowerTiles(state, busy, inner, onPower)
                }
            }

            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InfoCard(pc, state, isDefault, latestAgentVersion, Modifier.weight(1f))
                    ActivityCard(activities, Modifier.weight(1f))
                }
            } else {
                InfoCard(pc, state, isDefault, latestAgentVersion, Modifier.fillMaxWidth())
                ActivityCard(activities, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DetailCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoCard(pc: Pc, state: PcState?, isDefault: Boolean, latestAgentVersion: String?, modifier: Modifier) {
    val outsideSet = pc.wolTarget.isNotBlank()
    DetailCard("연결 정보", modifier) {
        // The PC agent reports its version while it's running; older agents (< 1.2.0) don't.
        val installed = state?.agentVersion?.takeIf { state.agent }
        val agentUpdate = installed != null && isNewerVersion(latestAgentVersion, installed.ifEmpty { "0" })
        InfoRow(
            "PC 에이전트",
            when {
                installed == null -> "—"
                installed.isEmpty() -> "1.1 이하"
                else -> installed
            } + if (agentUpdate) " → $latestAgentVersion" else "",
            mono = false,
            highlight = agentUpdate,
        )
        if (agentUpdate) {
            Text(
                "PC의 트레이 아이콘을 우클릭해서 업데이트를 설치하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.waiting,
            )
        }
        InfoRow("내부 IP", pc.host)
        InfoRow("MAC 주소", pc.mac.uppercase())
        InfoRow("에이전트 포트", pc.agentPort.toString())
        InfoRow("DDNS", pc.wolTarget.ifBlank { null })
        InfoRow("WOL 외부 포트", if (outsideSet) "UDP ${pc.wolPort}" else null)
        InfoRow("에이전트 외부 포트", pc.remoteAgentPort.takeIf { it > 0 && outsideSet }?.let { "TCP $it" })
        if (pc.routerWol) InfoRow("공유기로 켜기", "원격 관리 ${pc.routerPort}", mono = false)
        InfoRow(
            "현재 연결",
            when {
                state?.on != true -> "—"
                state.route == Route.REMOTE -> "외부 (DDNS)"
                else -> "내부 (같은 네트워크)"
            },
            mono = false,
        )
        InfoRow("빅스비 · 루틴", if (isDefault) "제어 대상" else "—", mono = false)
    }
}

@Composable
private fun InfoRow(label: String, value: String?, mono: Boolean = true, highlight: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value ?: "설정 안 함",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono && value != null) FontFamily.Monospace else null,
            color = when {
                value == null -> MaterialTheme.colorScheme.outline
                highlight -> StatusColors.waiting
                else -> MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (highlight) FontWeight.SemiBold else null,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActivityCard(activities: List<Activity>, modifier: Modifier) {
    DetailCard("최근 활동", modifier) {
        if (activities.isEmpty()) {
            Text(
                "아직 기록이 없습니다. 켜기·절전·종료를 하면 여기에 남습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        activities.take(8).forEach { a ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .size(8.dp)
                        .background(if (a.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(a.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatActivityTime(a.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
