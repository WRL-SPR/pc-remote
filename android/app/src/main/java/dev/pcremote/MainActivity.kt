package dev.pcremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = PcStore(this)
        setContent {
            PcRemoteTheme(dark = isSystemInDarkTheme()) { PcRemoteApp(store) }
        }
    }
}

val PowerAction.icon: ImageVector
    get() = when (this) {
        PowerAction.SLEEP -> AppIcons.Moon
        PowerAction.HIBERNATE -> AppIcons.Bed
        PowerAction.RESTART -> Icons.Default.Refresh
        PowerAction.SHUTDOWN -> AppIcons.Power
    }

@Composable
fun PcRemoteApp(store: PcStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pcs by remember { mutableStateOf(store.load()) }
    val states = remember { mutableStateMapOf<String, PcState>() }
    val busy = remember { mutableStateMapOf<String, String>() }
    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<Pc?>(null) }
    var pendingAction by remember { mutableStateOf<Pair<Pc, PowerAction>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun openArtemis() {
        if (!Artemis.launch(context)) notify("Artemis가 설치되어 있지 않아 다운로드 페이지를 엽니다")
    }

    // Refresh online status every 5 seconds while the app is on screen.
    LaunchedEffect(pcs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                coroutineScope {
                    pcs.forEach { pc -> launch { states[pc.id] = PcStatus.check(pc) } }
                }
                delay(5_000)
            }
        }
    }

    // Sends WOL and follows the boot until Apollo answers. Never opens Artemis by itself:
    // the Artemis button simply becomes available once the PC is confirmed ready.
    fun wake(pc: Pc) {
        if (busy.containsKey(pc.id)) return
        scope.launch {
            busy[pc.id] = "WOL 신호 보내는 중…"
            try {
                WakeOnLan.send(context, pc)
                val start = System.currentTimeMillis()
                var lastSend = start
                while (true) {
                    val state = PcStatus.check(pc)
                    states[pc.id] = state
                    val now = System.currentTimeMillis()
                    val sec = (now - start) / 1000
                    if (state.stream) {
                        notify("${pc.name}이(가) 켜졌습니다. 원격 접속할 수 있습니다")
                        break
                    }
                    if (sec > 180) {
                        notify(
                            if (state.agent) "${pc.name}은(는) 켜졌지만 Apollo가 응답하지 않습니다"
                            else "${pc.name}이(가) 3분 안에 켜지지 않았습니다"
                        )
                        break
                    }
                    busy[pc.id] = if (state.agent) "윈도우 켜짐 · Apollo 대기 ${sec}초" else "켜는 중… ${sec}초"
                    if (!state.agent && now - lastSend > 10_000) {
                        WakeOnLan.send(context, pc)
                        lastSend = now
                    }
                    delay(2_000)
                }
            } catch (e: Exception) {
                notify(e.message ?: "WOL 전송 실패")
            } finally {
                busy.remove(pc.id)
            }
        }
    }

    fun runPower(pc: Pc, action: PowerAction) {
        scope.launch {
            busy[pc.id] = "${action.label} 명령 보내는 중…"
            AgentClient.command(pc, action)
                .onSuccess { notify("${pc.name}: ${action.label} 명령을 보냈습니다") }
                .onFailure { notify("${pc.name}: ${it.message ?: "연결 실패"}") }
            busy.remove(pc.id)
        }
    }

    PcRemoteScreen(
        pcs = pcs,
        states = states,
        busy = busy,
        snackbar = snackbar,
        onAdd = { editorTarget = null; editorOpen = true },
        onWake = { wake(it) },
        onStream = { openArtemis() },
        onPower = { pc, action -> pendingAction = pc to action },
        onEdit = { editorTarget = it; editorOpen = true },
    )

    if (editorOpen) {
        PcEditorDialog(
            initial = editorTarget,
            onDismiss = { editorOpen = false },
            onSave = { pc ->
                pcs = if (pcs.any { it.id == pc.id }) pcs.map { if (it.id == pc.id) pc else it } else pcs + pc
                store.save(pcs)
                editorOpen = false
            },
            onDelete = { pc ->
                pcs = pcs.filter { it.id != pc.id }
                store.save(pcs)
                editorOpen = false
            },
        )
    }

    pendingAction?.let { (pc, action) ->
        val destructive = action == PowerAction.RESTART || action == PowerAction.SHUTDOWN
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            icon = { Icon(action.icon, contentDescription = null) },
            title = { Text("${pc.name} ${action.label}", textAlign = TextAlign.Center) },
            text = {
                Text(
                    if (destructive) "${action.label}합니다. 저장하지 않은 작업은 사라집니다."
                    else "${action.label} 모드로 전환합니다.",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingAction = null; runPower(pc, action) }) {
                    Text(
                        action.label,
                        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("취소") } },
        )
    }
}

/** Stateless main screen, also rendered by the layout snapshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcRemoteScreen(
    pcs: List<Pc>,
    states: Map<String, PcState>,
    busy: Map<String, String>,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onWake: (Pc) -> Unit,
    onStream: () -> Unit,
    onPower: (Pc, PowerAction) -> Unit,
    onEdit: (Pc) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PC 리모트", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (pcs.isNotEmpty()) {
                            val onCount = pcs.count { states[it.id]?.on == true }
                            Text(
                                "${pcs.size}대 중 ${onCount}대 켜짐",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (pcs.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("PC 추가") },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // safeDrawing also keeps cards clear of the camera cutout in landscape.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        // Layout follows the actual window width, so it also adapts to pop-up / split view:
        //   < 360dp  Fold cover screen, small phones
        //   < 580dp  regular phones, newer Fold cover screens
        //   >= 580dp Fold inner screen (Fold 1: 586dp ~ Fold 7/8: 750dp), phones in landscape
        //            -> grid when there are several PCs
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val side = if (maxWidth < 360.dp) 12.dp else 16.dp
            val grid = maxWidth >= 580.dp && pcs.size > 1
            if (pcs.isEmpty()) {
                EmptyState(onAdd, Modifier.fillMaxSize().padding(side))
            } else LazyVerticalGrid(
                // 270dp keeps two columns even on the original Fold's 586dp inner screen.
                columns = if (grid) GridCells.Adaptive(270.dp) else GridCells.Fixed(1),
                // A single column is capped so cards don't stretch across the unfolded screen.
                modifier = Modifier.fillMaxHeight().then(
                    if (grid) Modifier.fillMaxWidth() else Modifier.widthIn(max = 640.dp).fillMaxWidth()
                ),
                contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pcs, key = { it.id }) { pc ->
                    PcCard(
                        pc = pc,
                        state = states[pc.id],
                        busy = busy[pc.id],
                        onWake = { onWake(pc) },
                        onStream = onStream,
                        onPower = { onPower(pc, it) },
                        onEdit = { onEdit(pc) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit, modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(104.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Desktop,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("등록된 PC가 없어요", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "PC에 에이전트를 설치한 뒤\n아래 버튼으로 추가하세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onAdd,
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("PC 추가")
        }
    }
}

private val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
private val ActionShape = RoundedCornerShape(16.dp)

@Composable
fun PcCard(
    pc: Pc,
    state: PcState?,
    busy: String?,
    onWake: () -> Unit,
    onStream: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onEdit: () -> Unit,
) {
    val streamReady = state?.stream == true
    val agentOn = state?.agent == true
    val statusColor = when {
        streamReady -> StatusColors.ready
        busy != null || agentOn -> StatusColors.waiting
        else -> StatusColors.off
    }
    val statusText = busy ?: when {
        state == null -> "확인 중…"
        streamReady -> "원격 접속 가능"
        agentOn -> "윈도우 켜짐 · Apollo 응답 없음"
        else -> "꺼짐"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pad = if (maxWidth < 300.dp) 14.dp else 18.dp   // Fold cover screens, small phones
            val inner = maxWidth - pad * 2
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).background(statusColor.copy(alpha = 0.14f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Desktop, contentDescription = null, modifier = Modifier.size(24.dp), tint = statusColor)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            pc.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pc.host,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "편집",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                StatusPill(statusText, statusColor)
                if (busy != null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = statusColor,
                        trackColor = statusColor.copy(alpha = 0.15f),
                    )
                }

                // Show only what can be used now: power-on while off, power commands while Windows runs.
                val off = state?.on != true && busy == null
                val wakeButton: @Composable (Modifier) -> Unit = { m ->
                    Button(onClick = onWake, modifier = m.height(48.dp), shape = ActionShape, contentPadding = CompactButtonPadding) {
                        Icon(AppIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("전원 켜기", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Only enabled once Apollo answers, i.e. the PC is fully up and streamable.
                val streamButton: @Composable (Modifier) -> Unit = { m ->
                    Button(
                        onClick = onStream,
                        enabled = streamReady,
                        modifier = m.height(48.dp),
                        shape = ActionShape,
                        contentPadding = CompactButtonPadding,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
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
                    else -> {
                        wakeButton(Modifier.fillMaxWidth())
                        streamButton(Modifier.fillMaxWidth())
                    }
                }

                // Power tiles: a row of 4 (icon over label) on wide cards, 2 x 2 (icon beside label) otherwise.
                if (agentOn) {
                    val wide = inner >= 340.dp
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
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PowerTile(
    action: PowerAction,
    stacked: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val danger = action == PowerAction.SHUTDOWN
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(14.dp),
        color = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        if (stacked) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text(action.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(action.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
fun PcEditorDialog(
    initial: Pc?,
    onDismiss: () -> Unit,
    onSave: (Pc) -> Unit,
    onDelete: (Pc) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var mac by remember { mutableStateOf(initial?.mac ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var agentPort by remember { mutableStateOf((initial?.agentPort ?: 9876).toString()) }
    var wolTarget by remember { mutableStateOf(initial?.wolTarget ?: "") }
    var wolPort by remember { mutableStateOf((initial?.wolPort ?: 9).toString()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Desktop, contentDescription = null) },
        title = { Text(if (initial == null) "PC 추가" else "PC 편집") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section("기본 정보")
                Field("이름", name) { name = it }
                Field("PC IP 주소", host, "예: 192.168.0.10 (공유기에서 고정 IP 권장)", KeyboardType.Uri) { host = it }
                Field("MAC 주소", mac, "예: 1C:69:7A:12:34:56") { mac = it }
                Section("PC 에이전트")
                Field("토큰", token, "install.ps1 실행 시 출력된 값") { token = it }
                Field("포트", agentPort, keyboard = KeyboardType.Number) { agentPort = it }
                Section("밖에서 켜기 (선택)")
                Field(
                    "외부 WOL 주소", wolTarget,
                    "ipTIME DDNS 주소 (예: myhome.iptime.org)", KeyboardType.Uri,
                ) { wolTarget = it }
                Field("WOL 포트", wolPort, "공유기에서 포워딩한 UDP 포트", KeyboardType.Number) { wolPort = it }
                errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) {
                        Text("이 PC 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ap = agentPort.trim().toIntOrNull()
                val wp = wolPort.trim().toIntOrNull()
                errorMsg = when {
                    name.isBlank() -> "이름을 입력하세요"
                    host.isBlank() -> "PC IP 주소를 입력하세요"
                    runCatching { WakeOnLan.parseMac(mac) }.isFailure -> "MAC 주소 형식이 올바르지 않습니다"
                    ap == null || ap !in 1..65535 -> "에이전트 포트가 올바르지 않습니다"
                    wp == null || wp !in 1..65535 -> "WOL 포트가 올바르지 않습니다"
                    else -> null
                }
                if (errorMsg == null) {
                    onSave(
                        Pc(
                            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            host = host.trim(),
                            mac = mac.trim(),
                            token = token.trim(),
                            agentPort = ap!!,
                            wolTarget = wolTarget.trim(),
                            wolPort = wp!!,
                        )
                    )
                }
            }) { Text("저장", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    supporting: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
