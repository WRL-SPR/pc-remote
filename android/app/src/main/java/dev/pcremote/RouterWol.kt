package dev.pcremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager

/**
 * Asks the ipTIME router to send the magic packet itself (its own WOL feature, `wol/signal`).
 *
 * A port-forwarded magic packet can't reach a PC that is fully shut down: the router forgets
 * the PC's address a few minutes after it goes off and drops the packet, and ipTIME doesn't
 * forward to the broadcast address. The router always knows its own LAN, so this works.
 *
 * Talks to the router's HTTPS remote-management port, reached through the DDNS name. The
 * router's certificate is self-signed, so the first one seen is pinned ([Pc.routerPin]) and a
 * different one is refused afterwards.
 */
object RouterWol {

    /** [signal], keeping the router certificate pinned for this PC. */
    suspend fun send(store: PcStore, pc: Pc): kotlin.Result<Unit> = runCatching {
        store.setRouterPin(pc.id, signal(pc))
    }

    /** Sends the request; returns the router certificate's pin to keep in [Pc.routerPin]. */
    suspend fun signal(pc: Pc): String = withContext(Dispatchers.IO) {
        val host = pc.wolTarget.trim()
        require(host.isNotEmpty() && pc.routerEnabled) { "공유기 원격 관리가 설정되지 않았습니다" }
        val trust = PinningTrustManager(pc.routerPin)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val url = URL("https://$host:${pc.routerPort}/cgi/service.cgi")

        fun call(method: String, params: Any?, cookie: String?): Pair<JSONObject, String?> {
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = ssl.socketFactory
                // The certificate is pinned above, so its name (usually the router's LAN IP) doesn't matter.
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (cookie != null) setRequestProperty("Cookie", cookie)
            }
            try {
                val body = JSONObject().put("method", method)
                if (params != null) body.put("params", params)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val session = conn.headerFields["Set-Cookie"].orEmpty()
                    .firstOrNull { it.startsWith("efm_session_id=") }?.substringBefore(';')
                return JSONObject(text.ifEmpty { "{}" }) to session
            } finally {
                conn.disconnect()
            }
        }

        try {
            val (login, session) = call(
                "session/login",
                JSONObject().put("id", pc.routerId).put("pw", pc.routerPw),
                null,
            )
            if (login.optString("result") != "done" || session == null) {
                val error = login.optJSONObject("error")?.optString("message").orEmpty()
                error(
                    when {
                        "captcha" in error.lowercase() -> "공유기가 보안 문자를 요구합니다. 공유기 화면에서 한 번 로그인해 주세요"
                        else -> "공유기 로그인 실패 (아이디/비밀번호 확인)"
                    }
                )
            }
            try {
                val mac = pc.mac.replace(Regex("[^0-9A-Fa-f]"), "").uppercase().chunked(2).joinToString("-")
                val (wol, _) = call("wol/signal", JSONArray().put(mac), session)
                if (wol.optString("result") != "done") error("공유기가 켜기 요청을 거부했습니다")
            } finally {
                runCatching { call("session/logout", null, session) }
            }
            trust.seenPin ?: pc.routerPin
        } catch (e: SSLHandshakeException) {
            error(if (trust.mismatch) "공유기 인증서가 바뀌었습니다. 설정에서 공유기 인증서를 초기화하세요" else "공유기 HTTPS 연결 실패")
        } catch (e: IOException) {
            error("공유기에 연결할 수 없습니다 (원격 관리 포트 확인)")
        }
    }

    /** Accepts the router's self-signed certificate on first use, then only that one. */
    private class PinningTrustManager(private val pin: String) : X509TrustManager {
        var seenPin: String? = null
        var mismatch = false

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            if (pin.isNotEmpty() && pin != fingerprint) {
                mismatch = true
                throw CertificateException("router certificate changed")
            }
            seenPin = fingerprint
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            throw CertificateException("not a server")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
