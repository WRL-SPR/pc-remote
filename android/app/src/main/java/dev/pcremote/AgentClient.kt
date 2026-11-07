package dev.pcremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

enum class PowerAction(val path: String, val label: String) {
    SLEEP("sleep", "절전"),
    HIBERNATE("hibernate", "최대 절전"),
    RESTART("restart", "재시작"),
    SHUTDOWN("shutdown", "종료"),
}

/** Talks to PcAgent.ps1 running on the PC. */
object AgentClient {

    suspend fun ping(pc: Pc): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("http://${pc.host}:${pc.agentPort}/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    suspend fun command(pc: Pc, action: PowerAction): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("http://${pc.host}:${pc.agentPort}/${action.path}").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(0)
            conn.setRequestProperty("X-Token", pc.token)
            try {
                conn.outputStream.close()
                when (val code = conn.responseCode) {
                    200 -> Unit
                    403 -> error("토큰이 올바르지 않습니다")
                    else -> error("PC 응답 오류 ($code)")
                }
            } catch (e: IOException) {
                error("PC에 연결할 수 없습니다 (꺼져 있거나 에이전트가 설치되지 않음)")
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * [agent]: Windows has booted and PcAgent answers (power commands available).
 * [stream]: Apollo/Sunshine answers on 47989, so Artemis can connect.
 */
data class PcState(val agent: Boolean, val stream: Boolean) {
    val on get() = agent || stream
}

object PcStatus {
    suspend fun check(pc: Pc): PcState = coroutineScope {
        val agent = async { AgentClient.ping(pc) }
        val stream = async { tcpOpen(pc.host, 47989) }
        PcState(agent.await(), stream.await())
    }

    private suspend fun tcpOpen(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), 1500) }
            true
        }.getOrDefault(false)
    }
}

object Artemis {
    private val PACKAGES = listOf(
        "com.limelight.noir",       // Artemis
        "com.limelight.root.noir",  // Artemis (root build)
        "com.limelight",            // Moonlight (fallback)
        "com.limelight.unofficial",
    )
    private const val RELEASES = "https://github.com/ClassicOldSong/moonlight-android/releases"

    /** Opens Artemis; if it is not installed, opens its download page and returns false. */
    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }
}
