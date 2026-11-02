package com.davideloconte.screenlet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.google.android.material.R as M

/**
 * Coordinates user pairing prompts, SAS numeric comparison, and anti-hammering rate limits.
 */
object Pairing {
    private class Request(val code: String, val pc: String) {
        val answered = CountDownLatch(1)
        @Volatile var accepted = false
        @Volatile var close: (() -> Unit)? = null  // the question's window, when shown
    }

    @Volatile private var current: Request? = null
    private var quietUntil = 0L  // under this
    private val strikes = HashMap<String, Pair<Int, Long>>()  // address -> (questions not accepted, quiet until)
    private val main = Handler(Looper.getMainLooper())

    /** Blocks the calling thread until the user accepts or rejects the pairing confirmation code. */
    fun ask(context: Context, pc: String, code: String, from: String): Boolean? {
        val request = Request(code, pc)
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (current != null || now < quietUntil || now < (strikes[from]?.second ?: 0L)) return null
            current = request
        }
        val open = Intent(context, PairActivity::class.java)
            .putExtra(PairActivity.EXTRA_CODE, code)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Over whatever is on screen with "display over other apps"; over the
        // app's own screen without it.
        if (Settings.canDrawOverlays(context) || Front.shown) context.startActivity(open)
        Notifications.pairing(context, pc, code, open)
        val accepted = request.answered.await(2, TimeUnit.MINUTES) && request.accepted
        synchronized(this) {
            current = null
            val now = SystemClock.elapsedRealtime()
            if (accepted) {
                strikes.remove(from)
            } else {
                quietUntil = now + QUIET
                // 10 s, a minute, 10 minutes, then 10 minutes each time.
                val count = (strikes[from]?.first ?: 0) + 1
                val quiet = QUIET * listOf(1L, 6L, 60L)[minOf(count, 3) - 1]
                strikes[from] = count to now + quiet
                if (strikes.size > 256) strikes.keys.take(128).forEach { strikes.remove(it) }
            }
        }
        Notifications.cancelPairing(context, code)
        main.post { request.close?.invoke() }
        return accepted
    }

    /** Returns the name of the host requesting pairing for the specified verification code. */
    fun pending(code: String): String? = current?.takeIf { it.code == code }?.pc

    /** Records the user or host acceptance decision for the active pairing code. */
    fun answer(code: String, accepted: Boolean) {
        current?.takeIf { it.code == code }?.let {
            it.accepted = accepted
            it.answered.countDown()
        }
    }

    /** Registers a callback to be invoked on the main thread upon prompt dismissal. */
    fun onClose(code: String, close: () -> Unit) {
        current?.takeIf { it.code == code }?.close = close
    }

    private const val QUIET = 10_000L  // ms without questions after one was not accepted
}

/** Full-screen overlay activity presenting the 6-digit pairing code for user confirmation. */
class PairActivity : AppCompatActivity() {
    private var pair: android.view.View? = null
    private val arm = Runnable { pair?.isEnabled = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)  // the wallpaper's colours, as the app's other screens
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        val code = intent.getStringExtra(EXTRA_CODE) ?: return finish()
        val pc = Pairing.pending(code) ?: return finish()
        Pairing.onClose(code) { if (!isFinishing) finish() }  // answered elsewhere, or refused on the PC
        // Over between pending() and onClose(): its close would never come.
        if (Pairing.pending(code) == null) return finish()
        fun answer(accept: Boolean) {
            Pairing.answer(code, accept)
            finish()
        }
        // Back is no: the PC is told at once, not left waiting two minutes.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = answer(false)
        })
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = surface(M.attr.colorSurfaceContainerHigh)
            addView(icon(R.drawable.ic_computer, M.attr.colorSecondary))
            addView(label(getString(R.string.pair_title, pc), M.attr.textAppearanceHeadlineSmall).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            })
            addView(label(getString(R.string.pair_compare), M.attr.textAppearanceBodyMedium,
                M.attr.colorOnSurfaceVariant))
            // The code, big, in groups of three as the PC shows it.
            addView(label(code.chunked(3).joinToString(" "), M.attr.textAppearanceDisplayMedium,
                M.attr.colorOnSecondaryContainer).apply {
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                background = surface(M.attr.colorSecondaryContainer, dp(16f))
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(16), 0, dp(24))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(button(getString(R.string.cancel), Look.TEXT) { answer(false) })
                val yes = button(getString(R.string.pair), Look.FILLED) { answer(true) }.apply { isEnabled = false }
                pair = yes
                addView(yes, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        window.setLayout(minOf(resources.displayMetrics.widthPixels - dp(48), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Temporarily disables confirmation actions on focus changes to prevent accidental taps. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val pair = pair ?: return
        pair.removeCallbacks(arm)
        pair.isEnabled = false
        if (hasFocus) pair.postDelayed(arm, ARM_DELAY)
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val ARM_DELAY = 800L  // ms
    }
}

/** Broadcast receiver handling pairing confirmation actions triggered from notifications. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(PairActivity.EXTRA_CODE) ?: return
        Pairing.answer(code, intent.action == ACTION_ACCEPT)
    }

    companion object {
        const val ACTION_ACCEPT = "com.davideloconte.screenlet.PAIR"
        const val ACTION_REJECT = "com.davideloconte.screenlet.DONT_PAIR"
    }
}

/** Broadcast receiver restoring the companion service on device boot or package replacement. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val again = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (again && Options.alwaysReady(context)) {
            CompanionService.start(context)
        }
    }
}
