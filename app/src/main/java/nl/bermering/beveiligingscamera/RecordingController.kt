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

class RecordingController(private val username: String, private val password: String) {
    data class Result(val search: String?, val replay: String?, val recording: String?)
    fun inspect(ip: String): Result {
        val device = "http://$ip/onvif/device_service"
        val body = """<tds:GetServices xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><tds:IncludeCapability>true</tds:IncludeCapability></tds:GetServices>"""
        val xml = soap(device, "http://www.onvif.org/ver10/device/wsdl/GetServices", body) ?: return Result(null, null, null)
        val doc = parse(xml) ?: return Result(null, null, null)
        val nodes = doc.getElementsByTagNameNS("*", "Service")
        var search: String? = null; var replay: String? = null; var recording: String? = null
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? org.w3c.dom.Element ?: continue
            val ns = e.getElementsByTagNameNS("*", "Namespace").item(0)?.textContent.orEmpty()
            val x = e.getElementsByTagNameNS("*", "XAddr").item(0)?.textContent?.trim()
            if (x.isNullOrBlank()) continue
            when {
                ns.contains("/search/wsdl", true) -> search = x
                ns.contains("/replay/wsdl", true) -> replay = x
                ns.contains("/recording/wsdl", true) -> recording = x
            }
        }
        return Result(search, replay, recording)
    }
    private fun parse(xml: String): org.w3c.dom.Document? = try {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
    } catch (_: Exception) { null }
    private fun soap(endpoint: String, action: String, body: String): String? {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val nonceBytes = UUID.randomUUID().toString().replace("-", "").take(16).toByteArray(StandardCharsets.UTF_8)
        val digest = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(nonceBytes + now.toByteArray(StandardCharsets.UTF_8) + password.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
        val nonce = Base64.encodeToString(nonceBytes, Base64.NO_WRAP)
        val env = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd" xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"><s:Header><wsse:Security><wsse:UsernameToken><wsse:Username>$username</wsse:Username><wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</wsse:Password><wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonce</wsse:Nonce><wsu:Created>$now</wsu:Created></wsse:UsernameToken></wsse:Security></s:Header><s:Body>$body</s:Body></s:Envelope>"""
        return try {
            val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 3000; readTimeout = 4000; doOutput = true
                setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
            }
            OutputStreamWriter(c.outputStream, StandardCharsets.UTF_8).use { it.write(env) }
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } catch (_: Exception) { null }
    }
}
