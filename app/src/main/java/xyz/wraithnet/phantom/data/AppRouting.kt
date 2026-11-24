package xyz.wraithnet.phantom.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService

/** Exactly one Android VpnService per-app mode at a time. */
object AppRouting {
    const val ALL = "all"
    const val ONLY = "only"
    const val EXCLUDE = "exclude"
    private const val FILE = "phantom_preferences"
    private const val KEY_MODE = "app_route_mode"
    private const val KEY_PACKAGES = "app_route_packages"

    fun mode(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, ALL)
            ?.takeIf { it in setOf(ALL, ONLY, EXCLUDE) } ?: ALL

    fun packages(context: Context): Set<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()

    fun save(context: Context, mode: String, packages: Set<String>) {
        require(mode in setOf(ALL, ONLY, EXCLUDE)) { "Недопустимый режим маршрутизации" }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode).putStringSet(KEY_PACKAGES, packages.toSet()).apply()
    }

    fun apply(context: Context, builder: VpnService.Builder) =
        apply(context, builder, mode(context), packages(context))

    fun apply(context: Context, builder: VpnService.Builder,
        selectedMode: String, selectedPackages: Set<String>) {
        require(selectedMode in setOf(ALL, ONLY, EXCLUDE)) { "Недопустимый режим маршрутизации" }
        val selected = selectedPackages.filter { it != context.packageName }.distinct()
        when (selectedMode) {
            ONLY -> {
                require(selected.isNotEmpty()) { "Выберите хотя бы одно приложение для VPN" }
                var count = 0
                for (app in selected) {
                    try {
                        builder.addAllowedApplication(app)
                        count++
                    } catch (_: PackageManager.NameNotFoundException) {
                        // App may have been removed after it was selected.
                    }
                }
                require(count > 0) { "Выбранных приложений больше нет на устройстве" }
            }
            else -> {
                // Xray's own sockets must bypass the VPN to avoid a feedback loop.
                builder.addDisallowedApplication(context.packageName)
                if (selectedMode == EXCLUDE) {
                    for (app in selected) {
                        try {
                            builder.addDisallowedApplication(app)
                        } catch (_: PackageManager.NameNotFoundException) {
                            // Ignore packages uninstalled since their selection.
                        }
                    }
                }
            }
        }
    }
}
