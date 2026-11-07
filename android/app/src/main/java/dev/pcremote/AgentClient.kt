package dev.pcremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class PowerAction(val path: String, val label: String, val turnsOff: Boolean) {
    SLEEP("sleep", "절전", true),
    HIBERNATE("hibernate", "최대 절전", true),
    RESTART("restart", "재시작", false),
    SHUTDOWN("shutdown", "종료", true),
}

/** How the PC was reached: its LAN address at home, or the DDNS name through router forwarding. */
enum class Route { LOCAL, REMOTE }

private const val LOCAL_TIMEOUT_MS = 1500
private const val REMOTE_TIMEOUT_MS = 3500

/** Talks to PcAgent.ps1 running on the PC. */
object AgentClient {
    private val random = SecureRandom()

    /** Agent's installed version if it answers ("" for agents older than 1.2.0), null if it doesn't. */
    suspend fun ping(host: String, port: Int, timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("http://$host:$port/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            try {
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                runCatching { JSONObject(body).optString("version") }.getOrDefault("")
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /**
     * Sends a signed power command, first to the LAN address and, if that can't be reached and
     * the PC has a DDNS fallback, through the router. The token itself is never sent.
     */
    suspend fun command(pc: Pc, action: PowerAction): Result<Route> = withContext(Dispatchers.IO) {
        runCatching {
            val targets = buildList {
                add(Triple(pc.host, pc.agentPort, Route.LOCAL))
                pc.remoteHost?.let { add(Triple(it, pc.remoteAgentPort, Route.REMOTE)) }
            }
            for ((host, port, route) in targets) {
                val conn = signedRequest(pc, host, port, action, if (route == Route.LOCAL) LOCAL_TIMEOUT_MS else REMOTE_TIMEOUT_MS)
                try {
                    try {
                        conn.connect()
                    } catch (e: IOException) {
                        continue  // not reachable this way; try the next route
                    }
                    conn.outputStream.close()
                    when (val code = conn.responseCode) {
                        200 -> return@runCatching route
                        403 -> {
                            val reason = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            error(
                                if ("clock" in reason) "휴대폰과 PC의 시계가 2분 넘게 차이 납니다"
                                else "토큰이 맞지 않거나 PC 에이전트가 예전 버전입니다"
                            )
                        }
                        else -> error("PC 응답 오류 ($code)")
                    }
                } catch (e: IOException) {
                    error("PC가 응답하지 않았습니다")
                } finally {
                    conn.disconnect()
                }
            }
            error(
                if (pc.remoteHost == null) "PC에 연결할 수 없습니다 (꺼져 있거나, 집 밖이라면 외부 포트 설정 필요)"
                else "PC에 연결할 수 없습니다 (꺼져 있거나 에이전트가 실행 중이 아님)"
            )
        }
    }

    private fun signedRequest(pc: Pc, host: String, port: Int, action: PowerAction, timeoutMs: Int): HttpURLConnection {
        val time = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(16).also { random.nextBytes(it) }.toHex()
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(pc.token.toByteArray(), "HmacSHA256")) }
        val signature = mac.doFinal("${action.path}\n$time\n$nonce".toByteArray()).toHex()
        return (URL("http://$host:$port/${action.path}").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = 5000
            doOutput = true
            setFixedLengthStreamingMode(0)
            setRequestProperty("X-Time", time)
            setRequestProperty("X-Nonce", nonce)
            setRequestProperty("X-Signature", signature)
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/**
 * [agent]: Windows has booted and PcAgent answers (power commands available).
 * [stream]: Apollo/Sunshine answers on 47989, so Artemis can connect.
 * [route]: whether the answer came over the LAN or through the DDNS name.
 * [agentVersion]: installed PC agent version ("" = older than 1.2.0), known while [agent] is true.
 */
data class PcState(
    val agent: Boolean,
    val stream: Boolean,
    val route: Route = Route.LOCAL,
    val agentVersion: String? = null,
) {
    val on get() = agent || stream
}

object PcStatus {
    /** Checks the LAN address first; when nothing answers there, tries the DDNS name if set up. */
    suspend fun check(pc: Pc): PcState = coroutineScope {
        val agent = async { AgentClient.ping(pc.host, pc.agentPort, LOCAL_TIMEOUT_MS) }
        val stream = async { tcpOpen(pc.host, 47989, LOCAL_TIMEOUT_MS) }
        val localVersion = agent.await()
        val local = PcState(localVersion != null, stream.await(), Route.LOCAL, localVersion)
        val remote = pc.remoteHost
        if (local.on || remote == null) return@coroutineScope local

        val remoteAgent = async { AgentClient.ping(remote, pc.remoteAgentPort, REMOTE_TIMEOUT_MS) }
        val remoteStream = async { tcpOpen(remote, 47989, REMOTE_TIMEOUT_MS) }
        val remoteVersion = remoteAgent.await()
        PcState(remoteVersion != null, remoteStream.await(), Route.REMOTE, remoteVersion)
    }

    private suspend fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
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
