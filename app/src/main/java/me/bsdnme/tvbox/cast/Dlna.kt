package me.bsdnme.tvbox.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** A DLNA MediaRenderer's AVTransport service. */
data class CastDevice(val name: String, val controlUrl: String, val serviceType: String)
data class CastPosition(val position: Long, val duration: Long)

object Dlna {
    private val client = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    private fun xml(text: String): org.w3c.dom.Document {
        // Android's parser does not implement Xerces' security features. Reject DTDs
        // before parsing so entity declarations cannot be used on any platform.
        require(!text.contains("<!DOCTYPE", ignoreCase = true)) { "DTD is not allowed" }
        return DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
            ).forEach { (feature, enabled) ->
                try { setFeature(feature, enabled) } catch (_: javax.xml.parsers.ParserConfigurationException) { }
            }
        }.newDocumentBuilder().parse(InputSource(StringReader(text)))
    }
    private fun Element.value(name: String): String = getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()
    private fun Element.child(name: String): Element? = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .firstOrNull { (it.localName ?: it.tagName) == name }
    private fun Element.childValue(name: String) = child(name)?.textContent?.trim().orEmpty()
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    suspend fun discover(context: Context): List<CastDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("feigemedia-dlna").apply { setReferenceCounted(false); acquire() }
        try {
            val locations = linkedSetOf<String>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val multicast = InetAddress.getByName("239.255.255.250")
                // Some renderers only answer service searches or ssdp:all.
                val targets = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1", "ssdp:all")
                repeat(2) {
                    for (target in targets) {
                        val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n").toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(search, search.size, multicast, 1900))
                    }
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (System.nanoTime() < deadline) {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    try {
                        socket.receive(packet)
                        String(packet.data, 0, packet.length, Charsets.UTF_8).lineSequence().forEach { line ->
                            if (line.substringBefore(':').trim().equals("location", true)) {
                                line.substringAfter(':', "").trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }?.let(locations::add)
                            }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
            locations.mapNotNull { location ->
                try { readDevice(location) } catch (e: Exception) {
                    Log.w("Dlna", "Unable to read renderer description", e)
                    null
                }
            }.distinctBy { it.controlUrl }
        } finally { if (lock.isHeld) lock.release() }
    }

    private fun readDevice(location: String): CastDevice? {
        val body = client.newCall(Request.Builder().url(location).build()).execute().use {
            if (!it.isSuccessful) return null
            it.body?.string() ?: return null
        }
        return parseDeviceDescription(location, body)
    }

    internal fun parseDeviceDescription(location: String, body: String): CastDevice? {
        val doc = xml(body)
        val devices = doc.getElementsByTagNameNS("*", "device")
        for (i in 0 until devices.length) {
            val device = devices.item(i) as? Element ?: continue
            if (!device.childValue("deviceType").contains(":MediaRenderer:", ignoreCase = true)) continue
            val services = device.child("serviceList")?.getElementsByTagNameNS("*", "service") ?: continue
            for (j in 0 until services.length) {
                val service = services.item(j) as? Element ?: continue
                val type = service.value("serviceType")
                val control = service.value("controlURL")
                if (type.contains(":service:AVTransport:") && control.isNotBlank()) {
                    val base = doc.documentElement.value("URLBase").ifBlank { location }
                    val url = URI(base).resolve(control).toString()
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) continue
                    return CastDevice(device.childValue("friendlyName").ifBlank { "电视设备" }, url, type)
                }
            }
        }
        return null
    }

    suspend fun action(device: CastDevice, action: String, args: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val params = (mapOf("InstanceID" to "0") + args).entries.joinToString("") { "<${it.key}>${escape(it.value)}</${it.key}>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${escape(device.serviceType)}\">$params</u:$action></s:Body></s:Envelope>"
        val request = Request.Builder().url(device.controlUrl).header("SOAPACTION", "\"${device.serviceType}#$action\"")
            .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val result = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val fault = xml(result).documentElement
                    listOf(fault.value("errorCode"), fault.value("errorDescription"))
                        .filter { it.isNotBlank() }.joinToString(" ")
                }.getOrDefault("")
                throw IllegalStateException("电视返回错误 (${response.code})" + if (detail.isNotBlank()) "：$detail" else "")
            }
            result
        }
    }

    suspend fun start(device: CastDevice, url: String, position: Long) {
        action(device, "SetAVTransportURI", mapOf("CurrentURI" to url, "CurrentURIMetaData" to ""))
        action(device, "Play", mapOf("Speed" to "1"))
        if (position > 3000) runCatching { seek(device, position) }
    }
    suspend fun seek(device: CastDevice, position: Long) {
        val seconds = position.coerceAtLeast(0) / 1000
        val time = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        action(device, "Seek", mapOf("Unit" to "REL_TIME", "Target" to time))
    }
    suspend fun position(device: CastDevice): CastPosition {
        val root = xml(action(device, "GetPositionInfo")).documentElement
        fun parse(value: String): Long {
            val parts = value.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
            return if (parts.size == 3) (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000 else 0
        }
        return CastPosition(parse(root.value("RelTime")), parse(root.value("TrackDuration")))
    }
}
