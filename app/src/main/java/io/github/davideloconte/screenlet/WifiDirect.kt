package io.github.davideloconte.screenlet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import java.security.SecureRandom

/**
 * With no Wi-Fi network to share with the PC (nor a cable), the tablet makes
 * one: a Wi-Fi Direct group it owns, with a name and passphrase of its own,
 * which paired PCs learn in the hello (never an unpaired one) and join as an
 * ordinary Wi-Fi network. The tablet is then at GROUP_OWNER, and the link is
 * the same TLS as over any network.
 */
object WifiDirect {
    const val GROUP_OWNER = "192.168.49.1"  // Android's, always
    private const val PREFS = "wifi-direct"
    private var channel: WifiP2pManager.Channel? = null
    @Volatile var up = false
        private set

    /** The group's name and passphrase, made once. */
    fun credentials(context: Context): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("ssid", null)?.let { ssid -> return ssid to prefs.getString("passphrase", "")!! }
        val random = SecureRandom()
        val letters = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun pick(n: Int) = String(CharArray(n) { letters[random.nextInt(letters.length)] })
        // Android requires "DIRECT-" and two characters first.
        val ssid = "DIRECT-${pick(2)}-Screenlet"
        val passphrase = pick(20)
        prefs.edit().putString("ssid", ssid).putString("passphrase", passphrase).apply()
        return ssid to passphrase
    }

    fun allowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("MissingPermission")  // allowed() is checked
    fun start(context: Context) {
        if (up || !allowed(context)) return
        val manager = context.getSystemService(WifiP2pManager::class.java) ?: return
        val channel = channel ?: manager.initialize(context, context.mainLooper, null).also { channel = it }
        val (ssid, passphrase) = credentials(context)
        fun create(band: Int) {
            val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(passphrase)
                .setGroupOperatingBand(band).build()
            manager.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    up = true
                    Log.i(CompanionService.TAG, "wifi direct: group $ssid up")
                }

                override fun onFailure(reason: Int) {
                    Log.i(CompanionService.TAG, "wifi direct: no group on band $band: $reason")
                    // 5 GHz first (less crowded, faster); any band otherwise.
                    if (band == WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) create(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                }
            })
        }
        create(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
    }

    fun stop(context: Context) {
        val channel = channel ?: return
        if (!up) return
        up = false
        context.getSystemService(WifiP2pManager::class.java)?.removeGroup(channel, null)
        Log.i(CompanionService.TAG, "wifi direct: group down")
    }
}
