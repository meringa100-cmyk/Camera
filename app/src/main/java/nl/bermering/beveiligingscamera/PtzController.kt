package nl.bermering.beveiligingscamera

import android.util.Base64
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class PtzController(
    private val username: String,
    private val password: String
) {
    private data class Info(val endpoint: String, val profileToken: String)
    private val cache = mutableMapOf<String, Info>()

    fun move(ip: String, x: Double, y: Double): Boolean {
        val info = getInfo(ip) ?: return false
        val body = """
            <tptz:ContinuousMove xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${info.profileToken}</tptz:ProfileToken>
              <tptz:Velocity>
                <tt:PanTilt xmlns:tt="http://www.onvif.org/ver10/schema"
                    x="$x" y="$y"
                    space="http://www.onvif.org/ver10/tptz/PanTiltSpaces/VelocityGenericSpace"/>
              </tptz:Velocity>
            </tptz:ContinuousMove>
        """.trimIndent()
        val moved = soap(info.endpoint, "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove", body) != null
        Thread.sleep(350)
        val stopped = stop(info)
        return moved
    }

    fun home(ip: String): Boolean {
        val info = getInfo(ip) ?: return false
        val body = """
            <tptz:GotoHomePosition xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${info.profileToken}</tptz:ProfileToken>
            </tptz:GotoHomePosition>
        """.trimIndent()
        return soap(info.endpoint, "http://www.onvif.org/ver20/ptz/wsdl/GotoHomePosition", body) != null
    }

    private fun stop(info: Info): Boolean {
        val body = """
            <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${info.profileToken}</tptz:ProfileToken>
              <tptz:PanTilt>true</tptz:PanTilt>
              <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        return soap(info.endpoint, "http://www.onvif.org/ver20/ptz/wsdl/Stop", body) != null
    }

    private fun getInfo(ip: String): Info? {
        cache[ip]?.let { return it }

        val device = "http://$ip/onvif/device_service"
        val capabilities = soap(device, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities",
            """<tds:GetCapabilities xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:Category>All</tds:Category></tds:GetCapabilities>""")
            ?: return null

        val xaddrs = findXAddrs(capabilities)
        val ptz = xaddrs.firstOrNull { it.contains("ptz", true) } ?: return null
        val media = xaddrs.firstOrNull { it.contains("media", true) } ?: return null

        val profiles = soap(media, "http://www.onvif.org/ver10/media/wsdl/GetProfiles",
            """<trt:GetProfiles xmlns:trt="http://www.onvif.org/ver10/media/wsdl"/>""")
            ?: return null

        val token = findProfileToken(profiles) ?: return null
        return Info(ptz, token).also { cache[ip] = it }
    }

    private fun findXAddrs(xml: String): List<String> {
        val doc = parse(xml) ?: return emptyList()
        val nodes = doc.getElementsByTagNameNS("*", "XAddr")
        val result = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val value = nodes.item(i).textContent?.trim()
            if (!value.isNullOrBlank() && value.startsWith("http")) result.add(value)
        }
        return result.distinct()
    }

    private fun findProfileToken(xml: String): String? {
        val doc = parse(xml) ?: return null
        val profiles = doc.getElementsByTagNameNS("*", "Profiles")
        for (i in 0 until profiles.length) {
            val p = profiles.item(i)
            val ptz = (p as? org.w3c.dom.Element)?.getElementsByTagNameNS("*", "PTZConfiguration")
            if (ptz != null && ptz.length > 0) {
                val token = p.attributes?.getNamedItem("token")?.nodeValue
                if (!token.isNullOrBlank()) return token
            }
        }
        if (profiles.length > 0) return profiles.item(0).attributes?.getNamedItem("token")?.nodeValue
        return null
    }

    private fun parse(xml: String): org.w3c.dom.Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newDocumentBuilder().parse(xml.byteInputStream())
    } catch (_: Exception) {
        null
    }

    private fun soap(endpoint: String, action: String, body: String): String? {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        // Different 360Eyes/EC101 firmware versions accept different ONVIF
        // authentication methods. Try WS-Security Digest, then PasswordText,
        // then HTTP Basic as a compatibility fallback.
        val nonceBytes = UUID.randomUUID().toString().replace("-", "").take(16).toByteArray()
        val digestInput = nonceBytes + now.toByteArray(StandardCharsets.UTF_8) + password.toByteArray(StandardCharsets.UTF_8)
        val digest = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(digestInput),
            Base64.NO_WRAP
        )
        val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)

        val digestEnvelope = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
                xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
              <s:Header><wsse:Security><wsse:UsernameToken>
                <wsse:Username>$username</wsse:Username>
                <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password>
                <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonce</wsse:Nonce>
                <wsu:Created>$now</wsu:Created>
              </wsse:UsernameToken></wsse:Security></s:Header>
              <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent()

        val textEnvelope = """
            <?xml version="1.0" encoding="UTF-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <s:Header><wsse:Security><wsse:UsernameToken>
                <wsse:Username>$username</wsse:Username>
                <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">$password</wsse:Password>
              </wsse:UsernameToken></wsse:Security></s:Header>
              <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent()

        request(endpoint, action, digestEnvelope)?.let { return it }
        request(endpoint, action, textEnvelope)?.let { return it }

        val basic = Base64.encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        return request(endpoint, action, body, "Basic $basic")
    }
        requestDigest(endpoint, action, body)?.let { return it }

        return null
    }

    private fun requestDigest(endpoint: String, action: String, body: String): String? {
        val challenge = requestRaw(endpoint, action, body) ?: return null
        val header = challenge.first
        if (challenge.second != 401 || !header.startsWith("Digest", true)) return null

        val values = Regex("""(realm|nonce|qop|opaque)="?([^",]+)"?""")
            .findAll(header)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val realm = values["realm"] ?: return null
        val nonce = values["nonce"] ?: return null
        val qop = values["qop"]?.split(",")?.firstOrNull()?.trim()
        val uri = URL(endpoint).path.ifBlank { "/" }
        val nc = "00000001"
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        fun md5(s: String): String = MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("POST:$uri")
        val response = if (qop != null) {
            md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }

        val auth = buildString {
            append("Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
            if (qop != null) append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            values["opaque"]?.let { append(", opaque=\"$it\"") }
        }
        return request(endpoint, action, body, auth)
    }

    private fun requestRaw(endpoint: String, action: String, body: String): Pair<String, Int>? {
        return try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val header = connection.getHeaderField("WWW-Authenticate").orEmpty()
            connection.errorStream?.close()
            connection.inputStream?.close()
            connection.disconnect()
            Pair(header, code)
        } catch (_: Exception) {
            null
        }
    }

    private fun request(
        endpoint: String,
        action: String,
        body: String,
        authorization: String? = null
    ): String? {
        return try {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
                if (authorization != null) setRequestProperty("Authorization", authorization)
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
            } else {
                connection.errorStream?.close()
                connection.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}