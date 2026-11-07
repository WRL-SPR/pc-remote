package dev.pcremote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URL

/**
 * Asks the ipTIME router itself to send the magic packet (its own WOL menu), through the remote
 * management port.
 *
 * Needed because a router forwards outside WOL packets to the PC's IP only while it still knows
 * that IP's MAC address: a few minutes after the PC is switched off it forgets, and the forwarded
 * packet is dropped. The router's own WOL sends from inside the LAN, so it always works.
 *
 * Uses the JSON API of the ipTIME iUX3 web UI (firmware 15 and later): `POST /cgi/service.cgi`
 * with `session/login`, then `wol/signal`. It is not a published API; it is what the router's
 * own web page uses.
 */
object IptimeRouter {

    private const val TIMEOUT_MS = 6_000

    // JSON-RPC error codes returned by the router.
    private const val CAPTCHA_REQUIRED = -31997
    private const val LOGIN_REJECTED = -31996
    private const val METHOD_NOT_FOUND = -32601

    class RouterException(message: String) : IOException(message)

    /** Throws [RouterException] with a message for the activity log when it did not work. */
    suspend fun wake(context: Context, pc: Pc) = withContext(Dispatchers.IO) {
        val ddns = pc.wolTarget.trim()
        val address = WakeOnLan.resolve(context, ddns)
            ?: throw RouterException("DDNS 주소를 찾지 못했습니다")
        val ip = address.hostAddress ?: ddns
        val base = "http://${if (address is Inet6Address) "[$ip]" else ip}:${pc.routerPort}"
        val session = Session(base)

        session.call(
            "session/login",
            JSONObject().put("id", pc.routerUser).put("pw", pc.routerPassword),
        )
        try {
            val dashed = macText(pc.mac, '-')
            try {
                session.call("wol/signal", JSONArray().put(dashed))
            } catch (e: RpcError) {
                if (e.code != METHOD_NOT_FOUND) throw e
                session.call("wol/wake", JSONObject().put("mac", macText(pc.mac, ':')))
            }
        } catch (e: RpcError) {
            throw RouterException("공유기가 WOL을 거부했습니다. 공유기 WOL 목록에 이 PC의 MAC 주소가 있는지 확인하세요 (${e.code})")
        } finally {
            runCatching { session.call("session/logout", null) }
        }
    }

    private fun macText(mac: String, separator: Char): String =
        WakeOnLan.parseMac(mac).joinToString(separator.toString()) { "%02X".format(it) }

    private class RpcError(val code: Int, message: String) : IOException(message)

    private class Session(private val base: String) {
        private var cookie: String? = null

        fun call(method: String, params: Any?): Any? {
            val body = JSONObject().put("method", method).apply { if (params != null) put("params", params) }
            val conn = try {
                (URL("$base/cgi/service.cgi").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    doOutput = true
                    instanceFollowRedirects = false
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Referer", "$base/ui/")
                    setRequestProperty("Origin", base)
                    cookie?.let { setRequestProperty("Cookie", "efm_session_id=$it") }
                }
            } catch (e: IOException) {
                throw RouterException("공유기 원격 관리에 연결할 수 없습니다")
            }
            try {
                val bytes = body.toString().toByteArray()
                conn.setFixedLengthStreamingMode(bytes.size)
                try {
                    conn.outputStream.use { it.write(bytes) }
                } catch (e: IOException) {
                    throw RouterException("공유기 원격 관리에 연결할 수 없습니다 (원격 관리 포트 확인)")
                }
                val code = conn.responseCode
                if (code != 200) throw RouterException("공유기 응답 오류 ($code)")
                conn.headerFields["Set-Cookie"].orEmpty().forEach { header ->
                    Regex("efm_session_id=([^;]+)").find(header)?.let { cookie = it.groupValues[1] }
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val reply = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw RouterException("ipTIME 관리 페이지 응답이 아닙니다 (펌웨어 15 이상 필요)")
                reply.optJSONObject("error")?.let { error ->
                    val errorCode = error.optInt("code")
                    if (method == "session/login") throw RouterException(
                        when (errorCode) {
                            CAPTCHA_REQUIRED -> "공유기 로그인에 보안 문자가 필요합니다. 관리 페이지에 한 번 직접 로그인한 뒤 다시 시도하세요"
                            LOGIN_REJECTED -> "공유기 아이디 또는 비밀번호가 맞지 않습니다"
                            else -> "공유기 로그인 실패 ($errorCode)"
                        }
                    )
                    throw RpcError(errorCode, error.optString("message"))
                }
                if (method == "session/login" && reply.opt("result") != "done") {
                    throw RouterException("공유기 로그인 실패")
                }
                return reply.opt("result")
            } catch (e: RouterException) {
                throw e
            } catch (e: RpcError) {
                throw e
            } catch (e: IOException) {
                throw RouterException("공유기가 응답하지 않았습니다")
            } finally {
                conn.disconnect()
            }
        }
    }
}
