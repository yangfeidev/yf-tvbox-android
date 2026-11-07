package me.bsdnme.tvbox.data

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class FamilyPin(context: Context) {
    private val prefs = context.getSharedPreferences("family_pin", Context.MODE_PRIVATE)
    val isSet: Boolean get() = prefs.contains("hash")
    val lockedUntil: Long get() = prefs.getLong("locked_until", 0L)
    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun set(pin: String) {
        require(pin.matches(Regex("[0-9]{4,6}")))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
            .putInt("failures", 0).putLong("locked_until", 0L).commit()
    }
    fun verify(pin: String): Boolean {
        if (System.currentTimeMillis() < lockedUntil) return false
        val salt = runCatching { Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP) }.getOrNull() ?: return false
        val correct = MessageDigest.isEqual(expected, derive(pin, salt))
        val failures = if (correct) 0 else prefs.getInt("failures", 0) + 1
        prefs.edit().putInt("failures", if (failures >= 5) 0 else failures)
            .putLong("locked_until", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0L).commit()
        return correct
    }
}
