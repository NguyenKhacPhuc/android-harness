package dev.weft.osbridge.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.weft.contracts.AppInfo
import dev.weft.contracts.Apps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [Apps]. Queries [PackageManager] for
 * installed apps and their launchable-activity subset.
 *
 * Android 11+ enforces package visibility — by default we only see
 * apps that handle one of our declared intent queries. The
 * `MAIN/LAUNCHER` query is implicitly visible to every caller, so
 * [listLaunchable] works without manifest changes. [isInstalled]
 * checks across the visible scope; for apps outside it the call
 * returns false even when the app is present.
 *
 * To see beyond the launchable subset, hosts can add
 * `<queries><package android:name="..."/></queries>` entries to their
 * manifest, or the `QUERY_ALL_PACKAGES` permission (Play Store will
 * scrutinize this).
 */
class AndroidApps(context: Context) : Apps {
    private val appContext: Context = context.applicationContext
    private val pm: PackageManager = appContext.packageManager

    override suspend fun isInstalled(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun listLaunchable(limit: Int): List<AppInfo> = withContext(Dispatchers.IO) {
        val cap = limit.coerceIn(1, Apps.LAUNCHABLE_LIMIT_MAX)
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        // Distinct by package — a package can declare multiple
        // launcher activities (e.g. "Settings" + "Wireless Settings");
        // we only care about the package + its primary label.
        val seen = HashSet<String>()
        val items = ArrayList<AppInfo>()
        for (resolve in resolveInfos) {
            val pkg = resolve.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { resolve.loadLabel(pm)?.toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            val (versionName, system) = runCatching {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
                val isSystem = (info.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                info.versionName to isSystem
            }.getOrDefault(null to false)
            items += AppInfo(
                packageName = pkg,
                label = label,
                versionName = versionName,
                systemApp = system,
            )
            if (items.size >= cap) break
        }
        items.sortedBy { it.label.lowercase() }
    }
}
