package dev.pcremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var defaultId by remember { mutableStateOf(store.defaultPc(pcs)?.id) }
    var settingsOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val appVersion = remember { Updates.installedVersionName(context) }
    val appVersionCode = remember { Updates.installedVersionCode(context) }
    var latest by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateOpen by remember { mutableStateOf(false) }
    var restartStuck by remember { mutableStateOf<Pc?>(null) }
    val appUpdate = latest?.takeIf { it.appVersionCode > appVersionCode }
    val activityStore = remember { ActivityStore(context) }
    val activities = remember {
        mutableStateMapOf<String, List<Activity>>().apply { pcs.forEach { put(it.id, activityStore.list(it.id)) } }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun log(pc: Pc, text: String, ok: Boolean = true) {
        activities[pc.id] = activityStore.add(pc.id, text, ok)
    }

    fun openArtemis() {
        if (!Artemis.launch(context)) notify("Artemis가 설치되어 있지 않아 다운로드 페이지를 엽니다")
    }

    // New app / PC agent versions from the latest GitHub release, checked when the app opens.
    LaunchedEffect(Unit) {
        runCatching { Updates.fetchLatest() }.onSuccess { latest = it }
    }

    fun checkForUpdate() {
        scope.launch {
            runCatching { Updates.fetchLatest() }
                .onSuccess {
                    latest = it
                    if (it.appVersionCode > appVersionCode) updateOpen = true
                    else notify("최신 버전입니다 ($appVersion)")
                }
                .onFailure { notify("업데이트 정보를 가져오지 못했습니다") }
        }
    }

    // Refresh online status every 5 seconds while the app is on screen.
    LaunchedEffect(pcs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = System.currentTimeMillis()
                coroutineScope {
                    pcs.forEach { pc ->
                        // Leave a PC alone right after it was told to sleep / shut down.
                        if (now < store.quietUntil(pc.id)) states[pc.id] = PcState(agent = false, stream = false)
                        else launch { states[pc.id] = PcStatus.check(pc) }
                    }
                }
                // Picks up entries written by Routines / Bixby actions while the app was closed.
                pcs.forEach { activities[it.id] = activityStore.list(it.id) }
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
            store.setQuiet(pc.id, 0)
            try {
                val note = WakeOnLan.send(context, pc)
                log(pc, "전원 켜기 신호 보냄" + (note?.let { " ($it)" } ?: ""), ok = note == null)
                if (pc.routerEnabled) launch {
                    RouterWol.send(store, pc)
                        .onSuccess { log(pc, "공유기에 켜기 요청 보냄"); pcs = store.load() }
                        .onFailure { log(pc, "공유기 켜기 요청 실패: ${it.message}", ok = false) }
                }
                val start = System.currentTimeMillis()
                var lastSend = start
                while (true) {
                    val state = PcStatus.check(pc)
                    states[pc.id] = state
                    val now = System.currentTimeMillis()
                    val sec = (now - start) / 1000
                    if (state.stream) {
                        notify("${pc.name}이(가) 켜졌습니다. 원격 접속할 수 있습니다")
                        log(pc, "원격 접속 가능 확인 · ${formatDuration(sec)} 걸림")
                        break
                    }
                    if (sec > 180) {
                        val message = if (state.agent) "켜졌지만 Apollo가 응답하지 않음" else "3분 안에 켜지지 않음"
                        notify("${pc.name}: $message")
                        log(pc, message, ok = false)
                        break
                    }
                    busy[pc.id] = if (state.agent) "윈도우 켜짐 · Apollo 대기 ${sec}초" else "켜는 중… ${sec}초"
                    if (!state.agent && now - lastSend > 10_000) {
                        // A failed resend must not end the boot tracking; the next one retries.
                        runCatching { WakeOnLan.send(context, pc) }
                        lastSend = now
                    }
                    delay(2_000)
                }
            } catch (e: Exception) {
                notify(e.message ?: "WOL 전송 실패")
                log(pc, "켜기 실패: ${e.message ?: "WOL 전송 실패"}", ok = false)
            } finally {
                busy.remove(pc.id)
            }
        }
    }

    // Follows a restart: the agent has to go away and come back within 3 minutes. A PC that
    // shut Windows down but never booted again (stuck at the hardware stage) can't be woken by
    // WOL either, so the user is told to use the power button.
    suspend fun followRestart(pc: Pc) {
        val start = System.currentTimeMillis()
        var wentDown = false
        while (true) {
            delay(3_000)
            val state = PcStatus.check(pc)
            states[pc.id] = state
            val sec = (System.currentTimeMillis() - start) / 1000
            if (!state.agent) wentDown = true
            else if (wentDown) {
                log(pc, "재시작 완료 · ${formatDuration(sec)} 걸림")
                return
            }
            if (sec > 180) {
                if (wentDown) {
                    log(pc, "재시작 후 3분 안에 돌아오지 않음", ok = false)
                    restartStuck = pc
                } else {
                    notify("${pc.name}: 재시작되지 않았습니다")
                    log(pc, "재시작 명령 후에도 PC가 꺼지지 않음", ok = false)
                }
                return
            }
            busy[pc.id] = if (wentDown) "재시작 중… ${sec}초" else "재시작 준비 중… ${sec}초"
        }
    }

    fun runPower(pc: Pc, action: PowerAction) {
        scope.launch {
            busy[pc.id] = "${action.label} 명령 보내는 중…"
            AgentClient.command(pc, action)
                .onSuccess { route ->
                    if (action.turnsOff) {
                        store.setQuiet(pc.id, System.currentTimeMillis() + PcStore.QUIET_AFTER_POWER_OFF_MS)
                        states[pc.id] = PcState(agent = false, stream = false)
                    }
                    val outside = if (route == Route.REMOTE) " (외부 연결)" else ""
                    notify("${pc.name}: ${action.label} 명령을 보냈습니다$outside")
                    log(pc, "${action.label} 명령 보냄$outside")
                    if (action == PowerAction.RESTART) followRestart(pc)
                }
                .onFailure {
                    notify("${pc.name}: ${it.message ?: "연결 실패"}")
                    log(pc, "${action.label} 실패: ${it.message ?: "연결 실패"}", ok = false)
                }
            busy.remove(pc.id)
        }
    }

    PcRemoteScreen(
        pcs = pcs,
        states = states,
        busy = busy,
        defaultId = defaultId,
        activities = activities,
        selectedId = selectedId,
        appUpdate = appUpdate,
        latestAgentVersion = latest?.agentVersion,
        snackbar = snackbar,
        onSelect = { selectedId = it },
        onUpdate = { updateOpen = true },
        onAdd = { editorTarget = null; editorOpen = true },
        onSettings = { settingsOpen = true },
        onWake = { wake(it) },
        onStream = { openArtemis() },
        onPower = { pc, action -> pendingAction = pc to action },
        onEdit = { editorTarget = it; editorOpen = true },
    )

    if (editorOpen) {
        PcEditorDialog(
            initial = editorTarget,
            isDefault = editorTarget == null && pcs.isEmpty() || editorTarget?.id == defaultId,
            onDismiss = { editorOpen = false },
            onSave = { pc, makeDefault ->
                pcs = if (pcs.any { it.id == pc.id }) pcs.map { if (it.id == pc.id) pc else it } else pcs + pc
                store.save(pcs)
                if (makeDefault) store.defaultId = pc.id
                defaultId = store.defaultPc(pcs)?.id
                editorOpen = false
            },
            onDelete = { pc ->
                pcs = pcs.filter { it.id != pc.id }
                store.save(pcs)
                activityStore.clear(pc.id)
                activities.remove(pc.id)
                if (selectedId == pc.id) selectedId = null
                defaultId = store.defaultPc(pcs)?.id
                editorOpen = false
            },
        )
    }

    if (settingsOpen) {
        QuickActionsDialog(
            defaultPc = pcs.firstOrNull { it.id == defaultId },
            appVersion = appVersion,
            appUpdate = appUpdate,
            onUpdate = { settingsOpen = false; updateOpen = true },
            onCheckUpdate = { checkForUpdate() },
            onDismiss = { settingsOpen = false },
        )
    }

    if (updateOpen) {
        latest?.let { UpdateDialog(it, appVersion, onDismiss = { updateOpen = false }) }
    }

    restartStuck?.let { pc ->
        AlertDialog(
            onDismissRequest = { restartStuck = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("${pc.name}이(가) 다시 켜지지 않았습니다", textAlign = TextAlign.Center) },
            text = {
                Text(
                    "재시작 명령 후 3분이 지나도 응답이 없습니다. 윈도우는 꺼졌지만 다시 켜지는 중에 멈췄을 수 있습니다.\n\n" +
                        "이 상태에서는 원격으로 켤 수 없으니 본체 전원 버튼을 길게 눌러 끈 뒤 다시 켜 주세요.",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { restartStuck = null }) { Text("확인") } },
            dismissButton = {
                TextButton(onClick = { restartStuck = null; wake(pc) }) { Text("켜기 신호 보내 보기") }
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

/**
 * Stateless main screen, also rendered by the layout snapshot tests.
 *   wide (>= 580dp: Fold inner screen, phones in landscape): PC list on the left, details on the right
 *   narrow (Fold cover, bar phones): PC cards; tapping one opens its details full screen
 * The layout follows the actual window width, so it also adapts to pop-up / split view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcRemoteScreen(
    pcs: List<Pc>,
    states: Map<String, PcState>,
    busy: Map<String, String>,
    defaultId: String?,
    activities: Map<String, List<Activity>>,
    selectedId: String?,
    appUpdate: ReleaseInfo?,
    latestAgentVersion: String?,
    snackbar: SnackbarHostState,
    onSelect: (String?) -> Unit,
    onUpdate: () -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onWake: (Pc) -> Unit,
    onStream: () -> Unit,
    onPower: (Pc, PowerAction) -> Unit,
    onEdit: (Pc) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 580.dp && pcs.isNotEmpty()
        val selected = pcs.firstOrNull { it.id == selectedId }
        // Narrow screen showing one PC's details. Survives folding: the same selection opens the
        // two-pane view when unfolded and the full-screen details when folded.
        val detailOnly = !wide && selected != null
        val shown = selected ?: pcs.firstOrNull { it.id == defaultId } ?: pcs.firstOrNull()
        val listWidth = if (maxWidth < 700.dp) 240.dp else 280.dp
        // With a single PC the list would be mostly empty, so its details take the whole width.
        val showList = wide && pcs.size > 1
        val side = if (maxWidth < 360.dp) 12.dp else 16.dp

        if (detailOnly && LocalOnBackPressedDispatcherOwner.current != null) {
            BackHandler { onSelect(null) }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                val barColors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                )
                if (detailOnly) {
                    TopAppBar(
                        title = { Text(selected!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = { onSelect(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                            }
                        },
                        actions = {
                            IconButton(onClick = { onEdit(selected!!) }) {
                                Icon(Icons.Outlined.Edit, contentDescription = "편집", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        colors = barColors,
                    )
                } else {
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
                        actions = {
                            if (appUpdate != null) {
                                Button(
                                    onClick = onUpdate,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp),
                                ) {
                                    Text("업데이트 ${appUpdate.appVersion}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            IconButton(onClick = onSettings) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = "빅스비 · 루틴 연동",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = barColors,
                    )
                }
            },
            floatingActionButton = {
                if (pcs.isNotEmpty() && !showList && !detailOnly) {
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
            // safeDrawing also keeps content clear of the camera cutout in landscape.
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                when {
                    pcs.isEmpty() -> EmptyState(onAdd, Modifier.fillMaxSize().padding(side))

                    wide && !showList -> PcDetailPane(
                        pc = shown!!,
                        state = states[shown.id],
                        busy = busy[shown.id],
                        isDefault = true,
                        showDefaultBadge = false,
                        latestAgentVersion = latestAgentVersion,
                        activities = activities[shown.id].orEmpty(),
                        showEdit = true,
                        onWake = { onWake(shown) },
                        onStream = onStream,
                        onPower = { onPower(shown, it) },
                        onEdit = { onEdit(shown) },
                        modifier = Modifier.fillMaxSize(),
                        bottomPadding = 96.dp,   // clear of the "PC 추가" button
                    )

                    wide -> Row(Modifier.fillMaxSize()) {
                        PcListPane(
                            pcs = pcs,
                            states = states,
                            busy = busy,
                            selectedId = shown!!.id,
                            defaultId = defaultId,
                            onSelect = { onSelect(it.id) },
                            onAdd = onAdd,
                            modifier = Modifier.width(listWidth).fillMaxHeight(),
                        )
                        PcDetailPane(
                            pc = shown,
                            state = states[shown.id],
                            busy = busy[shown.id],
                            isDefault = shown.id == defaultId,
                            showDefaultBadge = pcs.size > 1,
                            latestAgentVersion = latestAgentVersion,
                            activities = activities[shown.id].orEmpty(),
                            showEdit = true,
                            onWake = { onWake(shown) },
                            onStream = onStream,
                            onPower = { onPower(shown, it) },
                            onEdit = { onEdit(shown) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    detailOnly -> PcDetailPane(
                        pc = selected!!,
                        state = states[selected.id],
                        busy = busy[selected.id],
                        isDefault = selected.id == defaultId,
                        showDefaultBadge = pcs.size > 1,
                        latestAgentVersion = latestAgentVersion,
                        activities = activities[selected.id].orEmpty(),
                        showEdit = false,
                        onWake = { onWake(selected) },
                        onStream = onStream,
                        onPower = { onPower(selected, it) },
                        onEdit = { onEdit(selected) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> LazyColumn(
                        // Capped so cards don't stretch on wide-but-short windows (split view).
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(pcs, key = { it.id }) { pc ->
                            PcCard(
                                pc = pc,
                                state = states[pc.id],
                                busy = busy[pc.id],
                                isDefault = pcs.size > 1 && pc.id == defaultId,
                                onOpen = { onSelect(pc.id) },
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

@Composable
fun PcCard(
    pc: Pc,
    state: PcState?,
    busy: String?,
    isDefault: Boolean,
    onOpen: () -> Unit,
    onWake: () -> Unit,
    onStream: () -> Unit,
    onPower: (PowerAction) -> Unit,
    onEdit: () -> Unit,
) {
    val ui = statusUi(pc, state, busy)
    ElevatedCard(
        onClick = onOpen,
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
                        Modifier.size(46.dp).background(ui.color.copy(alpha = 0.14f), RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Desktop, contentDescription = null, modifier = Modifier.size(24.dp), tint = ui.color)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pc.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isDefault) {
                                Spacer(Modifier.width(6.dp))
                                DefaultBadge()
                            }
                        }
                        Text(
                            ui.address,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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

                StatusPill(ui.text, ui.color)
                BusyBar(busy, ui.color)
                PcActionButtons(state, busy, inner, large = false, onWake = onWake, onStream = onStream)
                PowerTiles(state, busy, inner, onPower)
                Text(
                    "연결 정보 · 최근 활동 ›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
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
fun PowerTile(
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
    isDefault: Boolean,
    onDismiss: () -> Unit,
    onSave: (Pc, Boolean) -> Unit,
    onDelete: (Pc) -> Unit,
) {
    var makeDefault by remember { mutableStateOf(isDefault) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var mac by remember { mutableStateOf(initial?.mac ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var agentPort by remember { mutableStateOf((initial?.agentPort ?: 9876).toString()) }
    var wolTarget by remember { mutableStateOf(initial?.wolTarget ?: "") }
    var wolPort by remember { mutableStateOf((initial?.wolPort ?: 9).toString()) }
    var remoteAgentPort by remember {
        mutableStateOf(initial?.remoteAgentPort?.takeIf { it > 0 }?.toString() ?: "")
    }
    var routerPort by remember { mutableStateOf(initial?.routerPort?.takeIf { it > 0 }?.toString() ?: "") }
    var routerId by remember { mutableStateOf(initial?.routerId ?: "") }
    var routerPw by remember { mutableStateOf(initial?.routerPw ?: "") }
    var routerPin by remember { mutableStateOf(initial?.routerPin ?: "") }
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
                Field("토큰", token, "PC의 '연결 정보 보기'에 나오는 값") { token = it }
                Field("포트", agentPort, keyboard = KeyboardType.Number) { agentPort = it }
                Section("집 밖에서 사용 (선택)")
                Field(
                    "DDNS 주소", wolTarget,
                    "포트 없이 주소만 (예: myhome.iptime.org)", KeyboardType.Uri,
                ) { wolTarget = it }
                Field(
                    "WOL 외부 포트", wolPort,
                    "공유기 포트포워딩: UDP 이 포트 → PC 내부 9", KeyboardType.Number,
                ) { wolPort = it }
                Field(
                    "에이전트 외부 포트", remoteAgentPort,
                    "공유기 포트포워딩: TCP 이 포트 → PC 내부 ${agentPort.trim().ifEmpty { "9876" }} · 비우면 사용 안 함",
                    KeyboardType.Number,
                ) { remoteAgentPort = it }
                Section("공유기로 켜기 (선택)")
                Text(
                    "완전히 종료한 PC는 밖에서 보낸 WOL이 공유기에서 막힙니다. ipTIME의 HTTPS 원격 관리를 켜 두면 " +
                        "앱이 공유기에 로그인해 공유기가 직접 PC를 켜게 합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(
                    "HTTPS 원격 관리 포트", routerPort,
                    "ipTIME 보안 기능 → 공유기 접속/보안관리 · 비우면 사용 안 함", KeyboardType.Number,
                ) { routerPort = it; routerPin = "" }
                Field("공유기 관리자 아이디", routerId) { routerId = it }
                Field("공유기 관리자 비밀번호", routerPw, password = true) { routerPw = it }
                if (routerPin.isNotEmpty()) {
                    TextButton(onClick = { routerPin = "" }) { Text("공유기 인증서 다시 받기") }
                }
                Section("빅스비 · 루틴")
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { makeDefault = !makeDefault },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = makeDefault, onCheckedChange = { makeDefault = it })
                    Text("빅스비·루틴·바로가기가 이 PC를 제어", style = MaterialTheme.typography.bodyMedium)
                }
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
                val rp = if (remoteAgentPort.isBlank()) 0 else remoteAgentPort.trim().toIntOrNull()
                val rtp = if (routerPort.isBlank()) 0 else routerPort.trim().toIntOrNull()
                errorMsg = when {
                    name.isBlank() -> "이름을 입력하세요"
                    host.isBlank() -> "PC IP 주소를 입력하세요"
                    runCatching { WakeOnLan.parseMac(mac) }.isFailure -> "MAC 주소 형식이 올바르지 않습니다"
                    ap == null || ap !in 1..65535 -> "에이전트 포트가 올바르지 않습니다"
                    ':' in wolTarget || '/' in wolTarget -> "DDNS 주소에는 포트나 http:// 없이 주소만 입력하세요"
                    wp == null || wp !in 1..65535 -> "WOL 외부 포트가 올바르지 않습니다"
                    rp == null || rp !in 0..65535 -> "에이전트 외부 포트가 올바르지 않습니다"
                    rp > 0 && wolTarget.isBlank() -> "에이전트 외부 포트를 쓰려면 DDNS 주소를 입력하세요"
                    rtp == null || rtp !in 0..65535 -> "원격 관리 포트가 올바르지 않습니다"
                    rtp > 0 && wolTarget.isBlank() -> "공유기로 켜려면 DDNS 주소를 입력하세요"
                    rtp > 0 && (routerId.isBlank() || routerPw.isEmpty()) -> "공유기 관리자 아이디와 비밀번호를 입력하세요"
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
                            remoteAgentPort = rp!!,
                            routerPort = rtp!!,
                            routerId = routerId.trim(),
                            routerPw = routerPw,
                            // A different DDNS name may be a different router: pin its certificate afresh.
                            routerPin = if (wolTarget.trim() == initial?.wolTarget) routerPin else "",
                        ),
                        makeDefault,
                    )
                }
            }) { Text("저장", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

val QuickAction.icon: ImageVector
    get() = power?.icon ?: AppIcons.Power

/** Turns the Routines / Bixby app-list entries on or off and explains how to use them. */
@Composable
fun QuickActionsDialog(
    defaultPc: Pc?,
    appVersion: String,
    appUpdate: ReleaseInfo?,
    onUpdate: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val inAppList = remember {
        mutableStateMapOf<QuickAction, Boolean>().apply {
            QuickAction.entries.forEach { put(it, QuickActions.isInAppList(context, it)) }
        }
    }
    fun toggle(action: QuickAction, on: Boolean) {
        QuickActions.setInAppList(context, action, on)
        inAppList[action] = on
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
        title = { Text("빅스비 · 루틴 연동") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "켠 동작은 앱 목록에 별도 아이콘으로 추가됩니다. 열면 화면 없이 바로 실행됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                QuickAction.entries.forEach { action ->
                    val on = inAppList[action] == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { toggle(action, !on) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val danger = action == QuickAction.SHUTDOWN
                        Box(
                            Modifier.size(34.dp).background(
                                if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(10.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                action.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(action.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = { toggle(action, it) })
                    }
                }
                Section("대상 PC")
                Text(
                    defaultPc?.name ?: "등록된 PC가 없습니다",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "PC가 여러 대라면 PC 편집 화면에서 바꿀 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Section("사용 방법")
                Text(
                    "• 모드 및 루틴: 실행할 동작 → 앱 → 앱 열기 → \"PC 끄기\" 등 선택\n" +
                        "• 빅스비: \"하이 빅스비, PC 끄기 열어줘\"\n" +
                        "• 앱 아이콘을 길게 누르면 켜기·절전·재시작·끄기 바로가기",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Section("앱 정보")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PC 리모트 $appVersion", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (appUpdate != null) "새 버전 ${appUpdate.appVersion}이 있습니다" else "업데이트는 GitHub 릴리스에서 받습니다",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (appUpdate != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (appUpdate != null) {
                        Button(onClick = onUpdate) { Text("업데이트") }
                    } else {
                        TextButton(onClick = onCheckUpdate) { Text("업데이트 확인") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기", fontWeight = FontWeight.SemiBold) } },
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
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}
