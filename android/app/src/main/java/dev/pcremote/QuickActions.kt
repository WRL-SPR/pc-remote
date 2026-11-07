package dev.pcremote

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Actions that run without opening the app, for Samsung Modes & Routines ("앱 열기"), Bixby
 * ("PC 끄기 열어줘") and long-press app shortcuts. Each one is an activity-alias with its own label,
 * so it shows up as a separate entry in the app list once the user turns it on.
 */
enum class QuickAction(val key: String, val label: String, val alias: String, val power: PowerAction?) {
    WAKE("wake", "PC 켜기", "dev.pcremote.action.Wake", null),
    SLEEP("sleep", "PC 절전", "dev.pcremote.action.Sleep", PowerAction.SLEEP),
    HIBERNATE("hibernate", "PC 최대 절전", "dev.pcremote.action.Hibernate", PowerAction.HIBERNATE),
    RESTART("restart", "PC 재시작", "dev.pcremote.action.Restart", PowerAction.RESTART),
    SHUTDOWN("shutdown", "PC 끄기", "dev.pcremote.action.Shutdown", PowerAction.SHUTDOWN);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
        fun fromAlias(className: String?) = entries.firstOrNull { it.alias == className }
    }
}

object QuickActions {
    // Outlives ActionActivity, which finishes immediately; the work only takes a few seconds.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun isInAppList(context: Context, action: QuickAction): Boolean =
        context.packageManager.getComponentEnabledSetting(ComponentName(context, action.alias)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setInAppList(context: Context, action: QuickAction, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, action.alias),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun run(context: Context, action: QuickAction) {
        val store = PcStore(context)
        val pcs = store.load()
        val pc = store.defaultPc(pcs)
        if (pc == null) {
            toast(context, "등록된 PC가 없습니다. PC 리모트 앱에서 먼저 PC를 추가하세요")
            return
        }
        scope.launch {
            val activity = ActivityStore(context)
            val via = " (루틴·빅스비·바로가기)"
            val message = try {
                val power = action.power
                if (power == null) {
                    store.setQuiet(pc.id, 0)
                    WakeOnLan.send(context, pc)
                    activity.add(pc.id, "전원 켜기 신호 보냄$via")
                    if (pc.routerEnabled) RouterWol.send(store, pc)
                        .onSuccess { activity.add(pc.id, "공유기에 켜기 요청 보냄$via") }
                        .onFailure { activity.add(pc.id, "공유기 켜기 요청 실패: ${it.message}$via", ok = false) }
                    "${pc.name}에 WOL 신호를 보냈습니다"
                } else {
                    AgentClient.command(pc, power).fold(
                        onSuccess = { route ->
                            if (power.turnsOff) store.setQuiet(pc.id, System.currentTimeMillis() + PcStore.QUIET_AFTER_POWER_OFF_MS)
                            val outside = if (route == Route.REMOTE) " (외부 연결)" else ""
                            activity.add(pc.id, "${power.label} 명령 보냄$outside$via")
                            "${pc.name}: ${power.label} 명령을 보냈습니다$outside"
                        },
                        onFailure = {
                            activity.add(pc.id, "${power.label} 실패: ${it.message ?: "연결 실패"}$via", ok = false)
                            "${pc.name}: ${it.message ?: "연결 실패"}"
                        },
                    )
                }
            } catch (e: Exception) {
                activity.add(pc.id, "${action.label} 실패: ${e.message ?: "오류"}$via", ok = false)
                "${pc.name}: ${e.message ?: "실패"}"
            }
            toast(context, message)
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}

/** Invisible entry point behind the aliases and shortcuts: starts the action and closes at once. */
class ActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = QuickAction.fromKey(intent.data?.lastPathSegment)
            ?: QuickAction.fromAlias(intent.component?.className)
        if (action != null) QuickActions.run(applicationContext, action)
        finish()
    }
}
