package me.bsdnme.tvbox.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bsdnme.tvbox.BuildConfig
import me.bsdnme.tvbox.core.DeviceForm
import me.bsdnme.tvbox.core.theme.FormFactor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object Analytics {
    // Set after the Worker is deployed. Debug builds never send production analytics.
    private const val ENDPOINT = "https://analytics.bsdnme.me/v1/installations"
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    suspend fun report(context: Context) = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG || !ENDPOINT.startsWith("https://")) return@withContext
        try {
            // Android excludes noBackupFilesDir from both cloud backup and device transfer.
            val idFile = File(context.noBackupFilesDir, "analytics-install-id")
            val id = if (idFile.exists()) idFile.readText().trim() else UUID.randomUUID().toString().also { idFile.writeText(it) }
            val type = when (DeviceForm.resolve(context, false)) {
                FormFactor.Television -> "television"
                FormFactor.Tablet -> "tablet"
                else -> "phone"
            }
            val json = JSONObject().put("id", id)
                .put("manufacturer", Build.MANUFACTURER.ifBlank { "unknown" }.take(100))
                .put("model", Build.MODEL.ifBlank { "unknown" }.take(100))
                .put("deviceType", type).put("appVersion", BuildConfig.VERSION_NAME)
            val request = Request.Builder().url(ENDPOINT)
                .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) {
            // Best effort: a network or storage failure must never block the app.
        }
    }
}
