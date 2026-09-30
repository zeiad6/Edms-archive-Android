package com.zidex.edms

import android.content.Context
import android.net.Uri
import android.net.http.SslCertificate
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import com.zidex.edms.data.KeystoreAes
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Link to one desktop EDMS (see electron/mobile-gateway.cjs on the desktop).
 *
 * Trust = certificate pinning: the pairing QR carries the SHA-256 of the
 * desktop's self-signed certificate; every connection (native and WebView)
 * must present exactly that certificate. Hostnames/CAs are irrelevant, which
 * is what lets the same pin work on a LAN IP, a DDNS name or a VPN address.
 */
data class Link(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val serverName: String,
    val token: String,
    val host: String = hosts.first(),
) {
    val origin get() = "https://$host:$port"

    companion object {
        /** edms://pair?h=a,b&p=43443&fp=<hex>&c=<code>&n=<name> → (link-without-token, code) */
        fun parsePairUri(raw: String): Pair<Link, String>? {
            val u = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
            if (u.scheme != "edms" || u.host != "pair") return null
            val hosts = u.getQueryParameter("h")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val port = u.getQueryParameter("p")?.toIntOrNull() ?: return null
            val fp = u.getQueryParameter("fp")?.lowercase().orEmpty()
            val code = u.getQueryParameter("c").orEmpty()
            if (hosts.isEmpty() || port !in 1..65535 || !fp.matches(Regex("[0-9a-f]{64}")) || code.isEmpty()) return null
            return Link(hosts, port, fp, u.getQueryParameter("n").orEmpty(), token = "") to code
        }
    }
}

object Pins {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** WebView hands us an SslCertificate; recover its DER to check the pin. */
    fun matches(cert: SslCertificate?, fingerprint: String): Boolean {
        val der = cert?.let { SslCertificate.saveState(it).getByteArray("x509-certificate") } ?: return false
        val x509 = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull() ?: return false
        return sha256Hex(x509.encoded) == fingerprint
    }

    // Not the usual "trust this one host" custom trust manager: it trusts exactly
    // one certificate — the desktop's, identified by the SHA-256 the pairing QR
    // carried — and nothing else, so there is no CA path to get wrong.
    @Suppress("CustomX509TrustManager")
    private fun trustManager(fingerprint: String) = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client auth not supported")

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || sha256Hex(chain[0].encoded) != fingerprint) {
                throw CertificateException("certificate does not match the paired desktop")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** HTTPS connection that trusts ONLY the pinned desktop certificate. */
    fun open(url: String, fingerprint: String): HttpsURLConnection {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager(fingerprint)), null) }
        return (URL(url).openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ctx.socketFactory
            // The pin already binds the exact certificate; re-check it here
            // instead of the (meaningless for a self-signed cert) hostname.
            hostnameVerifier = HostnameVerifier { _, session ->
                runCatching { sha256Hex(session.peerCertificates[0].encoded) == fingerprint }.getOrDefault(false)
            }
            connectTimeout = 4000
            readTimeout = 15000
        }
    }
}

object Api {
    /** Redeem a one-time pairing code on the first reachable host. Blocking. */
    fun pair(link: Link, code: String, deviceName: String): Link {
        var last: Exception? = null
        for (h in link.hosts) {
            try {
                val c = Pins.open("https://$h:${link.port}/__edms/pair", link.fingerprint)
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("code", code).put("name", deviceName).toString().toByteArray()) }
                if (c.responseCode == 403) throw IllegalStateException("رمز الاقتران غير صالح أو منتهي — أنشئ رمزاً جديداً من الحاسوب")
                if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                val body = JSONObject(c.inputStream.bufferedReader().readText())
                return link.copy(token = body.getString("token"), host = h, serverName = body.optString("serverName", link.serverName))
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("تعذّر الوصول إلى الحاسوب: ${last?.message}")
    }

    /** First host answering /__edms/ping with our token, or null. Blocking. */
    fun reachableHost(link: Link): String? = (listOf(link.host) + link.hosts).distinct().firstOrNull { h ->
        runCatching {
            val c = Pins.open("https://$h:${link.port}/__edms/ping", link.fingerprint)
            c.setRequestProperty("x-edms-device", link.token)
            c.responseCode == 200
        }.getOrDefault(false)
    }

    /** Plain GET against a public HTTPS endpoint (system CAs). Blocking. */
    fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("accept", "application/vnd.github+json")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }
}

/**
 * Link persistence. The device token is encrypted with an AES-GCM key that
 * never leaves the Android Keystore; everything else is non-secret.
 */
class LinkStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)

    fun load(): Link? {
        val enc = prefs.getString("token", null) ?: return null
        // KeystoreAes returns null when the payload is corrupt or the key was
        // replaced (e.g. after a restore onto another device) — same as "no link".
        val token = KeystoreAes.decrypt(ALIAS, enc)?.takeIf { it.isNotEmpty() } ?: return null
        return Link(
            hosts = prefs.getString("hosts", "")!!.split(',').filter { it.isNotEmpty() },
            port = prefs.getInt("port", 0),
            fingerprint = prefs.getString("fp", "")!!,
            serverName = prefs.getString("name", "")!!,
            token = token,
            host = prefs.getString("host", "")!!,
        ).takeIf { it.hosts.isNotEmpty() && it.host.isNotEmpty() }
    }

    fun save(l: Link) = prefs.edit()
        .putString("hosts", l.hosts.joinToString(","))
        .putInt("port", l.port)
        .putString("fp", l.fingerprint)
        .putString("name", l.serverName)
        .putString("host", l.host)
        .putString("token", encrypt(l.token))
        .apply()

    fun clear() = prefs.edit().clear().apply()

    private fun encrypt(s: String): String = KeystoreAes.encrypt(ALIAS, s)

    private companion object {
        const val ALIAS = "edms-link"
    }
}
