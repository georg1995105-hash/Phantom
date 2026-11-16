package xyz.wraithnet.phantom.service

import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import xyz.wraithnet.phantom.MainActivity
import xyz.wraithnet.phantom.R
import xyz.wraithnet.phantom.data.SubscriptionStore

/** Android Quick Settings tile. Never starts a tunnel without VPN permission. */
class PhantomQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        renderState()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { toggleVpn() }
        else toggleVpn()
    }

    private fun toggleVpn() {
        val prefs = getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
        if (prefs.getBoolean("vpn_running", false)) {
            runCatching {
                startService(Intent(this, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_STOP))
            }.onFailure { launchApplication(connect = false) }
            return
        }
        val profile = selectedProfile(this)
        if (profile.isNullOrBlank()) {
            launchApplication(connect = false)
            return
        }
        val prepared = try { VpnService.prepare(this) } catch (_: Exception) { null }
        // A null result normally means permission is granted; if the platform
        // denies starting an FGS from this service, ask the Activity to do it.
        if (prepared != null) {
            launchApplication(connect = true)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_START)
                    .putExtra(PhantomVpnService.EXTRA_PROFILE, profile)
            )
        }.onFailure { launchApplication(connect = true) }
    }

    private fun launchApplication(connect: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (connect) intent.action = ACTION_CONNECT_FROM_TILE
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(
                this, if (connect) 502 else 501, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderState() {
        val tile = qsTile ?: return
        val running = getSharedPreferences("phantom_preferences", Context.MODE_PRIVATE)
            .getBoolean("vpn_running", false)
        tile.label = "Phantom VPN"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_phantom)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (running) "Подключено" else "Отключено"
        }
        tile.updateTile()
    }

    companion object {
        const val ACTION_CONNECT_FROM_TILE = "xyz.wraithnet.phantom.CONNECT_FROM_TILE"

        fun selectedProfile(context: Context): String? {
            val selectedId = context.getSharedPreferences(
                "phantom_preferences", Context.MODE_PRIVATE
            ).getString("selected_server_id", null)
            val subscriptions = SubscriptionStore(context).read()
            val servers = subscriptions.flatMap { it.servers }
            return servers.firstOrNull { it.id == selectedId }?.raw
                ?: servers.firstOrNull()?.raw
        }

        /** The tile can also be added manually from Android's Edit tiles screen. */
        fun offerToAdd(context: Context, onResult: (String) -> Unit) {
            if (Build.VERSION.SDK_INT < 33) {
                onResult("Откройте редактирование шторки и добавьте плитку Phantom VPN")
                return
            }
            try {
                val manager = context.getSystemService(StatusBarManager::class.java)
                manager.requestAddTileService(
                    ComponentName(context, PhantomQuickTileService::class.java),
                    "Phantom VPN",
                    Icon.createWithResource(context, R.drawable.ic_stat_phantom),
                    context.mainExecutor
                ) { code ->
                    onResult(when (code) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                            "Плитка Phantom добавлена в шторку"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            "Плитка Phantom уже добавлена"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                            "Плитка не добавлена. Её можно добавить через редактирование шторки."
                        else -> "Откройте редактирование быстрых настроек и найдите Phantom VPN"
                    })
                }
            } catch (_: Exception) {
                onResult("Не удалось открыть добавление. Добавьте плитку через редактирование шторки.")
            }
        }

        fun notifyStateChanged(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, PhantomQuickTileService::class.java)
                )
            }
        }
    }
}
