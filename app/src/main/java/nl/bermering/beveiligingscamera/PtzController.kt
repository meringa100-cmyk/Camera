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

    fun move(ip: String, x: Double, y: Double) {
        val info = getInfo(ip) ?: return
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
        soap(info.endpoint, "http://www.onvif.org/ver20/ptz/wsdl/ContinuousMove", body)
        Thread.sleep(350)
        stop(info)
    }

    fun home(ip: String) {
        val info = getInfo(ip) ?: return
        val body = """
            <tptz:GotoHomePosition xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${info.profileToken}</tptz:ProfileToken>
            </tptz:GotoHomePosition>
        """.trimIndent()
        soap(info.endpoint, "http://www.onvif.org/ver20/ptz/wsdl/GotoHomePosition", body)
    }

    private fun stop(info: Info) {
        val body = """
            <tptz:Stop xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">
              <tptz:ProfileToken>${info.profileToken}</tptz:ProfileToken>
              <tptz:PanTilt>true</tptz:PanTilt>
              <tptz:Zoom>true</tptz:Zoom>
            </tptz:Stop>
        """.trimIndent()
        soap(info.endpoint, "http://www.onvif.org/ver20/ptz/wsdl/Stop", body)
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
        return try {
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
            val nonceBytes = UUID.randomUUID().toString().replace("-", "").take(16).toByteArray()
            val digestInput = nonceBytes + now.toByteArray(StandardCharsets.UTF_8) + password.toByteArray(StandardCharsets.UTF_8)
            val digest = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(digestInput), Base64.NO_WRAP)
            val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)

            val envelope = """
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

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(envelope) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.also { connection.disconnect() }
        } catch (_: Exception) {
            null
        }
    }
}
