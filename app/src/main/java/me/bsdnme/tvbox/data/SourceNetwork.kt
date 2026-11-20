package me.bsdnme.tvbox.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import java.security.MessageDigest

data class SourceNetwork(val key: String, val online: Boolean)

/** A network handle is scoped to this boot; no SSID, address or subscriber data is stored. */
fun sourceNetwork(context: Context): SourceNetwork {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return SourceNetwork("", false)
    val network = manager.activeNetwork ?: return SourceNetwork("", false)
    val caps = manager.getNetworkCapabilities(network) ?: return SourceNetwork("", false)
    val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
    // If boot count is unavailable, avoid trusting a network handle across process restarts.
    val bootId = if (boot >= 0) boot.toString() else fallbackBootId
    val id = "$bootId:${network.networkHandle}"
    val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    return SourceNetwork(hash, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
}

private val fallbackBootId = java.util.UUID.randomUUID().toString()
