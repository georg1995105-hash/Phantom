package xyz.wraithnet.phantom

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.wraithnet.phantom.service.VpnRuntimeBridge
import androidx.compose.ui.graphics.toArgb
import xyz.wraithnet.phantom.ui.theme.themeScheme
import xyz.wraithnet.phantom.ui.theme.phantomSystemNavigationColor
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.wraithnet.phantom.service.PhantomVpnService
import xyz.wraithnet.phantom.service.PhantomQuickTileService
import xyz.wraithnet.phantom.ui.home.HomeScreen
import xyz.wraithnet.phantom.ui.theme.PhantomTheme
import xyz.wraithnet.phantom.ui.theme.PhantomThemeMode

class MainActivity : ComponentActivity() {
    private var pendingProfile: String? = null
    private var statusGeneration = 0L
    private val vpnStatus = mutableStateOf(PhantomVpnService.STATUS_DISCONNECTED)
    private val vpnError = mutableStateOf("")
    private val downloadBytes = mutableLongStateOf(-1L)
    private val uploadBytes = mutableLongStateOf(-1L)
    private val connectedSinceElapsed = mutableLongStateOf(0L)
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
            if (intent?.action == PhantomVpnService.ACTION_TRAFFIC) {
                downloadBytes.longValue = intent.getLongExtra(PhantomVpnService.EXTRA_DOWNLOAD_BYTES, -1L)
                uploadBytes.longValue = intent.getLongExtra(PhantomVpnService.EXTRA_UPLOAD_BYTES, -1L)
                return
            }
            statusGeneration++
            vpnStatus.value = intent?.getStringExtra(PhantomVpnService.EXTRA_STATUS)
                ?: PhantomVpnService.STATUS_ERROR
            vpnError.value = intent?.getStringExtra(PhantomVpnService.EXTRA_ERROR).orEmpty()
            connectedSinceElapsed.longValue = intent?.getLongExtra(
                PhantomVpnService.EXTRA_CONNECTED_SINCE, 0L) ?: 0L
            if (!PhantomVpnService.isTunnelActive(vpnStatus.value)) {
                downloadBytes.longValue = -1L
                uploadBytes.longValue = -1L
            }
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
            this, vpnReceiver, IntentFilter(PhantomVpnService.ACTION_STATUS).apply {
                addAction(PhantomVpnService.ACTION_TRAFFIC)
            }, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent {
            val preferences = remember { getSharedPreferences("phantom_preferences", MODE_PRIVATE) }
            var theme by remember {
                mutableStateOf(
                    PhantomThemeMode.fromStored(preferences.getString("theme", null))
                )
            }
            val lightBars = theme == PhantomThemeMode.LIGHT ||
                (theme == PhantomThemeMode.AUTO && !isSystemInDarkTheme())
            val palette = themeScheme(theme, isSystemInDarkTheme())
            SideEffect {
                if (Build.VERSION.SDK_INT >= 29) {
                    // Blend the Android navigation area into the theme, without hiding Back/Home.
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
                // Android 15 enforces transparent status bars; request the same
                // on older Android so the Compose inset remains visible.
                @Suppress("DEPRECATION")
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                window.navigationBarColor = palette.phantomSystemNavigationColor().toArgb()
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
                    downloadBytes = downloadBytes.longValue,
                    uploadBytes = uploadBytes.longValue,
                    connectedSinceElapsed = connectedSinceElapsed.longValue,
                    onStartVpn = ::requestVpn,
                    onStopVpn = ::stopVpnService,
                    onThemeChange = { next ->
                        theme = next
                        preferences.edit().putString("theme", next.name).apply()
                    }
                )
            }
        }
        if (savedInstanceState == null &&
            intent?.action == PhantomQuickTileService.ACTION_CONNECT_FROM_TILE) {
            connectFromQuickTile()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalImport.value = extractImportedText(intent)
        if (intent.action == PhantomQuickTileService.ACTION_CONNECT_FROM_TILE) {
            connectFromQuickTile()
        }
    }

    private fun connectFromQuickTile() {
        val profile = PhantomQuickTileService.selectedProfile(this)
        if (!profile.isNullOrBlank()) requestVpn(profile)
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
        xyz.wraithnet.phantom.service.PhantomUpdateInstallReceiver.setForeground(this, true)
        refreshVersion.intValue++
        val prefs = getSharedPreferences("phantom_preferences", MODE_PRIVATE)
        prefs.edit().putBoolean("ui_visible", true).apply()
        // Query the process that owns the native core. Preferences are not a
        // reliable cross-process status source after a UI or VPN process death.
        val generation = statusGeneration
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                VpnRuntimeBridge.snapshot(applicationContext)
            }
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
            if (!snapshot.available) {
                if (statusGeneration == generation) vpnError.value = "Состояние VPN временно недоступно"
                return@launch
            }
            if (statusGeneration == generation) {
                vpnStatus.value = snapshot.status
                connectedSinceElapsed.longValue = snapshot.connectedSince
                vpnError.value = snapshot.error
                if (!snapshot.running) {
                    downloadBytes.longValue = -1L
                    uploadBytes.longValue = -1L
                }
            }
            if (snapshot.status != PhantomVpnService.STATUS_DISCONNECTED) {
                startService(Intent(this@MainActivity, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_QUERY))
            }
            if (snapshot.running) {
                startService(Intent(this@MainActivity, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_TRAFFIC_ON))
            }
        }
    }

    override fun onPause() {
        xyz.wraithnet.phantom.service.PhantomUpdateInstallReceiver.setForeground(this, false)
        val prefs = getSharedPreferences("phantom_preferences", MODE_PRIVATE)
        prefs.edit().putBoolean("ui_visible", false).apply()
        if (PhantomVpnService.isTunnelActive(vpnStatus.value)) {
            runCatching {
                startService(Intent(this, PhantomVpnService::class.java)
                    .setAction(PhantomVpnService.ACTION_TRAFFIC_OFF))
            }
        }
        super.onPause()
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
