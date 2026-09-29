package nl.bermering.beveiligingscamera

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class DvrIpController {
    data class Recording(
        val beginTime: String,
        val endTime: String,
        val fileName: String,
        val fileLength: String
    )

    private val magic = 0xFF
    private var sequence = 0

    fun probe(ip: String, port: Int): String {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), 2000)
                "OPEN"
            }
        } catch (e: Exception) {
            when (e) {
                is java.net.SocketTimeoutException -> "TIMEOUT"
                is java.net.ConnectException -> "CLOSED"
                else -> e.javaClass.simpleName
            }
        }
    }

    fun loginDiagnostic(ip: String, username: String, password: String): String {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 34567), 3000)
                socket.soTimeout = 4000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val login = JSONObject().apply {
                    put("EncryptType", "MD5")
                    put("LoginType", "DVRIP-Web")
                    put("PassWord", sofiaHash(password))
                    put("UserName", username)
                }
                send(output, 1000, 0L, login)
                val reply = readMessage(input) ?: return "geen antwoord"
                "antwoord msg=" + reply.msgId + ": " + reply.payload
            }
        } catch (e: Exception) {
            e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    fun query(ip: String, username: String, password: String, beginTime: String, endTime: String): List<Recording> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, 34567), 3000)
            socket.soTimeout = 10000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val login = JSONObject().apply {
                put("EncryptType", "MD5")
                put("LoginType", "DVRIP-Web")
                put("PassWord", sofiaHash(password))
                put("UserName", username)
            }
            send(output, 1000, 0L, login)
            val loginReply = readMessage(input) ?: error("Geen login-antwoord op TCP 34567 (na login)")
            val loginJson = JSONObject(loginReply.payload)
            val loginRet = loginJson.optInt("Ret", -1)
            if (loginRet != 100) error("DVRIP login antwoord: Ret=$loginRet")
            val sessionText = loginJson.optString("SessionID")
            if (sessionText.isBlank()) error("Login antwoord zonder SessionID: $loginReply")
            val sessionId = sessionText.removePrefix("0x").toLong(16)

            val query = JSONObject().apply {
                put("Name", "OPFileQuery")
                put("OPFileQuery", JSONObject().apply {
                    put("BeginTime", beginTime)
                    put("Channel", 0)
                    put("DriverTypeMask", "0x0000FFFF")
                    put("EndTime", endTime)
                    put("Event", "*")
                    put("StreamType", "0x00000000")
                    put("Type", "h264")
                })
            }
            send(output, 1440, sessionId, query)
            val reply = readMessage(input) ?: error("Login gelukt, maar geen OPFileQuery-antwoord op TCP 34567")
            val json = JSONObject(reply.payload)
            val ret = json.optInt("Ret", -1)
            if (ret != 100) return emptyList()

            val array = json.optJSONArray("OPFileQuery") ?: return emptyList()
            val result = ArrayList<Recording>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                result.add(
                    Recording(
                        item.optString("BeginTime"),
                        item.optString("EndTime"),
                        item.optString("FileName"),
                        item.optString("FileLength")
                    )
                )
            }
            return result
        }
    }

    private data class Message(val msgId: Int, val payload: String)

    private fun send(output: BufferedOutputStream, msgId: Int, sessionId: Long, json: JSONObject) {
        val payload = (json.toString() + "\n\u0000").toByteArray(StandardCharsets.UTF_8)
        val header = ByteArray(20)
        header[0] = magic.toByte()
        header[1] = 0
        putLe16(header, 2, 0)
        putLe32(header, 4, sessionId)
        putLe32(header, 8, sequence++.toLong())
        header[12] = 0
        header[13] = 0
        putLe16(header, 14, msgId)
        putLe32(header, 16, payload.size.toLong())
        output.write(header)
        output.write(payload)
        output.flush()
    }

    private fun readMessage(input: BufferedInputStream): Message? {
        val header = ByteArray(20)
        readFully(input, header) ?: return null
        if ((header[0].toInt() and 0xFF) != 0xFF) error("Ongeldige DVRIP-header")
        val msgId = le16(header, 14)
        val payloadLength = le32(header, 16).toInt()
        val payload = readPayload(input, payloadLength)
        val text = payload.toString(StandardCharsets.UTF_8).trimEnd('\n', '\u0000')
        return Message(msgId, text)
    }

    private fun readPayload(input: BufferedInputStream, length: Int): ByteArray {
        if (length < 0 || length > 2_000_000) error("Ongeldige DVRIP-payload")
        val data = ByteArray(length)
        readFully(input, data) ?: error("Onvolledig DVRIP-antwoord")
        return data
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray): ByteArray? {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) return null
            offset += n
        }
        return buffer
    }

    private fun putLe16(b: ByteArray, offset: Int, value: Int) {
        b[offset] = (value and 0xFF).toByte()
        b[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putLe32(b: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) b[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun le16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[offset + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun sofiaHash(password: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(password.toByteArray(StandardCharsets.UTF_8))
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val out = StringBuilder(8)
        var i = 0
        while (i < 16) {
            out.append(chars[((digest[i].toInt() and 0xFF) + (digest[i + 1].toInt() and 0xFF)) % 62])
            i += 2
        }
        return out.toString()
    }
}
