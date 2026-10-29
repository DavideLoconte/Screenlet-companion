package io.github.davideloconte.screenlet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as M

/**
 * What the app is doing and what it may do: ready for the PC, what lets it
 * open by itself, the paired PCs. Nothing else to set: the PC chooses.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(themeColor(M.attr.colorSurfaceContainer))
            clipToPadding = false
            addView(content)
            // Clear of the status and navigation bars (the app is drawn behind
            // them), and no wider than reads well on a big screen.
            var bars = android.graphics.Insets.NONE
            fun pad(v: View) {
                val side = maxOf(dp(16), (v.width - bars.left - bars.right - dp(MAX_WIDTH)) / 2)
                v.setPadding(bars.left + side, bars.top + dp(8), bars.right + side, bars.bottom + dp(24))
            }
            setOnApplyWindowInsetsListener { v, insets ->
                bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                pad(v)
                insets
            }
            addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) v.post { pad(v) } }
        })
    }

    override fun onStart() {
        super.onStart()
        Front.started()
    }

    override fun onStop() {
        Front.stopped()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        CompanionService.start(this)  // ready now (for a while, if not always)
        refresh()
    }

    private fun refresh() {
        // Made again from scratch: where the user had scrolled to stays.
        val scroller = content.parent as? ScrollView
        val scrolled = scroller?.scrollY ?: 0
        scroller?.post { scroller.scrollTo(0, scrolled) }
        content.removeAllViews()
        content.addView(label(getString(R.string.app_name), M.attr.textAppearanceDisplaySmall).apply {
            setPadding(dp(8), dp(32), dp(8), dp(20))
        })
        content.addView(hero())
        onboarding()?.let { content.addView(it) }

        val always = MaterialSwitch(this).apply {
            isChecked = Options.alwaysReady(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Options.setAlwaysReady(this@MainActivity, on)
                refresh()
            }
        }
        section(R.string.heading_ready, listOf(
            Row(row(R.drawable.ic_ready, getString(R.string.always_ready),
                getString(if (always.isChecked) R.string.always_on else R.string.always_off), always)) { always.toggle() },
        ))

        val paired = Paired.all(this)
        section(R.string.heading_paired, if (paired.isEmpty()) {
            listOf(Row(row(R.drawable.ic_computer, getString(R.string.paired_none_title), getString(R.string.paired_none))))
        } else paired.map { (fingerprint, name) ->
            Row(row(R.drawable.ic_computer, name, null,
                iconButton(R.drawable.ic_forget, R.string.forget) { askForget(fingerprint, name) }))
        })

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        content.addView(label(getString(R.string.version, version), M.attr.textAppearanceBodySmall,
            M.attr.colorOnSurfaceVariant).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Ready, and how the PC finds this tablet: the app's whole job in a card. */
    private fun hero() = MaterialCardView(this, null, M.attr.materialCardViewFilledStyle).apply {
        setCardBackgroundColor(themeColor(M.attr.colorPrimaryContainer))
        radius = dp(28f)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(24), dp(20))
            addView(icon(R.drawable.ic_ready, M.attr.colorOnPrimary, size = 28).apply {
                background = surface(androidx.appcompat.R.attr.colorPrimary, dp(28f))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // Not paired: what to do on the PC. Paired: what this tablet is for.
                val paired = Paired.all(this@MainActivity).isNotEmpty()
                addView(label(getString(if (paired) R.string.ready else R.string.not_paired_title),
                    M.attr.textAppearanceTitleLarge, M.attr.colorOnPrimaryContainer))
                addView(label(if (paired) getString(R.string.status_ready)
                    else getString(R.string.status_unpaired, CompanionService.deviceName(this@MainActivity)),
                    M.attr.textAppearanceBodyMedium, M.attr.colorOnPrimaryContainer))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })
    }

    /** What lets the app reach the user by itself (the pairing code, the
     * desktop opening when the PC asks) and the PC reach it with no network
     * (Wi-Fi Direct), asked together and said why, while anything of it is
     * missing; null once it is all there. */
    private fun onboarding(): View? {
        val notifications = granted(Manifest.permission.POST_NOTIFICATIONS)
        val nearby = granted(Manifest.permission.NEARBY_WIFI_DEVICES)
        val overlay = Settings.canDrawOverlays(this)
        if (notifications && nearby && overlay) return null
        return MaterialCardView(this, null, M.attr.materialCardViewFilledStyle).apply {
            radius = dp(28f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(16))
                addView(label(getString(R.string.onboarding_title), M.attr.textAppearanceTitleMedium))
                addView(label(getString(R.string.onboarding_text), M.attr.textAppearanceBodyMedium,
                    M.attr.colorOnSurfaceVariant).apply { setPadding(0, dp(8), 0, dp(12)) })
                addView(button(getString(R.string.overlay_allow), Look.FILLED) {
                    // Android's questions first, then "display over other apps" (a settings page).
                    val ask = listOfNotNull(Manifest.permission.POST_NOTIFICATIONS.takeIf { !notifications },
                        Manifest.permission.NEARBY_WIFI_DEVICES.takeIf { !nearby })
                    if (ask.isNotEmpty()) requestPermissions(ask.toTypedArray(), 1) else allowOverlay()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.END })
            })
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1) {  // the onboarding card's: then the overlay, if missing
            if (!Settings.canDrawOverlays(this)) allowOverlay() else refresh()
        }
    }

    private fun section(title: Int, rows: List<Row>) {
        content.addView(sectionTitle(getString(title)))
        content.addView(group(rows))
    }

    private fun allowOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun askForget(fingerprint: String, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.forget_title, name))
            .setMessage(R.string.forget_detail)
            .setPositiveButton(R.string.forget) { _, _ ->
                Paired.forget(this, fingerprint)
                CompanionService.instance?.disconnect(fingerprint)  // and what it has open, now
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val MAX_WIDTH = 760  // dp of content
    }
}
