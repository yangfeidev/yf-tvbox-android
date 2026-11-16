package me.bsdnme.tvbox.data

import android.content.Context

/** Authorization is local to this installation and is excluded from Android backup. */
class ContentAuthorization(context: Context) {
    private val marker = context.noBackupFilesDir.resolve("content_authorized")
    private val prefs = context.getSharedPreferences("content_authorization", Context.MODE_PRIVATE)
    val isAuthorized: Boolean get() = marker.isFile
    val lockedUntil: Long get() = prefs.getLong("locked_until", 0L)

    fun authorize(code: String): Boolean {
        if (System.currentTimeMillis() < lockedUntil) return false
        if (code == AUTHORIZATION_CODE) {
            marker.writeText("authorized")
            prefs.edit().clear().apply()
            return true
        }
        val failures = prefs.getInt("failures", 0) + 1
        prefs.edit().putInt("failures", if (failures >= 5) 0 else failures)
            .putLong("locked_until", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0L).apply()
        return false
    }

    companion object {
        // Fixed offline code: changing it affects new authorizations, not devices already authorized.
        private const val AUTHORIZATION_CODE = "08160219"
    }
}
