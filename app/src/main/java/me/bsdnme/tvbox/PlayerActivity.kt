package me.bsdnme.tvbox

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.bsdnme.tvbox.core.theme.FeigeMediaTheme
import me.bsdnme.tvbox.core.theme.FormFactor
import me.bsdnme.tvbox.ui.player.PlaybackRequest
import me.bsdnme.tvbox.ui.player.PlayerScreen
import java.lang.ref.WeakReference

/** The system pins this activity alone, leaving the app's navigation activity underneath. */
class PlayerActivity : ComponentActivity() {
    private var inPip by mutableStateOf(false)
    private val pipAllowed: Boolean
        get() = Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            getSharedPreferences("player_preferences", MODE_PRIVATE).getBoolean("floating_player", true) &&
            bridge?.formFactor?.isTv == false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = bridge ?: run { finish(); return }
        active = WeakReference(this)
        enableEdgeToEdge()
        updatePipParams()
        setContent {
            FeigeMediaTheme(formFactor = session.formFactor) {
                PlayerScreen(
                    request = session.request,
                    systemPip = inPip,
                    onClose = { finish() },
                    onMinimize = { if (!enterPip()) finish() },
                    onProgress = session.onProgress,
                )
            }
        }
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= 26 && pipAllowed) {
            val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(true)
            runCatching { setPictureInPictureParams(builder.build()) }
        }
    }

    private fun enterPip(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !pipAllowed) return false
        return runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
                if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(true)
            }.build())
        }.getOrDefault(false)
    }

    @Deprecated("Used on Android 8–11 for Home navigation")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in 26..30 && !inPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onDestroy() {
        if (active?.get() === this) { active = null; bridge = null }
        super.onDestroy()
    }

    companion object {
        private data class Session(
            val request: PlaybackRequest,
            val formFactor: FormFactor,
            val onProgress: (String, String, Float) -> Unit,
        )
        private var bridge: Session? = null
        private var active: WeakReference<PlayerActivity>? = null

        fun start(context: Context, request: PlaybackRequest, formFactor: FormFactor, onProgress: (String, String, Float) -> Unit) {
            active?.get()?.finish()
            active = null
            bridge = Session(request, formFactor, onProgress)
            context.startActivity(Intent(context, PlayerActivity::class.java))
        }
    }
}
