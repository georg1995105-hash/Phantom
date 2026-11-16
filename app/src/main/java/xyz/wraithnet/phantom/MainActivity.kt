package xyz.wraithnet.phantom

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import xyz.wraithnet.phantom.service.SubscriptionRefreshWorker
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.wraithnet.phantom.service.PhantomVpnService
import xyz.wraithnet.phantom.ui.home.HomeScreen
import xyz.wraithnet.phantom.ui.theme.PhantomTheme
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode

class MainActivity : ComponentActivity() {
    private var pendingProfile: String? = null
    private val vpnStatus = mutableStateOf(PhantomVpnService.STATUS_DISCONNECTED)
    private val vpnError = mutableStateOf("")
    private val refreshVersion = mutableIntStateOf(0)
    private val externalImport = mutableStateOf<String?>(null)

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingProfile?.let(::startVpnService)
        } else {
            vpnStatus.value = PhantomVpnService.STATUS_ERROR
            vpnError.value = "Разрешение Android VPN не предоставлено"
        }
        pendingProfile = null
    }

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            vpnStatus.value = intent?.getStringExtra(PhantomVpnService.EXTRA_STATUS)
                ?: PhantomVpnService.STATUS_ERROR
            vpnError.value = intent?.getStringExtra(PhantomVpnService.EXTRA_ERROR).orEmpty()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        externalImport.value = extractImportedText(intent)
        val refreshWork = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            1, TimeUnit.HOURS,
        ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "phantom-subscription-refresh", ExistingPeriodicWorkPolicy.KEEP, refreshWork
        )
        ContextCompat.registerReceiver(
            this, vpnReceiver, IntentFilter(PhantomVpnService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            val preferences = remember { getSharedPreferences("phantom_preferences", MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(
                    runCatching {
                        PhantomThemeMode.valueOf(
                            preferences.getString("theme", PhantomThemeMode.AUTO.name)
                                ?: PhantomThemeMode.AUTO.name
                        )
                    }.getOrDefault(PhantomThemeMode.AUTO)
                )
            }
            val lightBars = theme == PhantomThemeMode.LIGHT ||
                (theme == PhantomThemeMode.AUTO && !isSystemInDarkTheme())
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            PhantomTheme(mode = theme) {
                HomeScreen(
                    refreshVersion = refreshVersion.intValue,
                    externalImport = externalImport.value,
                    onExternalImportConsumed = { externalImport.value = null },
                    vpnStatus = vpnStatus.value,
                    vpnError = vpnError.value,
                    onStartVpn = ::requestVpn,
                    onStopVpn = ::stopVpnService,
                    onThemeChange = { next ->
                        theme = next
                        preferences.edit().putString("theme", next.name).apply()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalImport.value = extractImportedText(intent)
    }

    private fun extractImportedText(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }?.trim().orEmpty()
        if (raw.isBlank() || raw.length > 500_000) return null
        return raw
    }

    override fun onResume() {
        super.onResume()
        refreshVersion.intValue++
        if (getSharedPreferences("phantom_preferences", MODE_PRIVATE).getBoolean("vpn_running", false)) {
            startService(Intent(this, PhantomVpnService::class.java).setAction(PhantomVpnService.ACTION_QUERY))
        }
    }

    private fun requestVpn(profile: String) {
        pendingProfile = profile
        val intent = VpnService.prepare(this)
        if (intent == null) {
            startVpnService(profile)
            pendingProfile = null
        } else {
            vpnPermission.launch(intent)
        }
    }

    private fun startVpnService(profile: String) {
        val intent = Intent(this, PhantomVpnService::class.java)
            .setAction(PhantomVpnService.ACTION_START)
            .putExtra(PhantomVpnService.EXTRA_PROFILE, profile)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        startService(Intent(this, PhantomVpnService::class.java).setAction(PhantomVpnService.ACTION_STOP))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(vpnReceiver) }
        super.onDestroy()
    }
}
