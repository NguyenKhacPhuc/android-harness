package dev.weft.osbridge.shortcuts

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import dev.weft.contracts.AppShortcuts
import dev.weft.contracts.ShortcutSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [AppShortcuts] using [ShortcutManager].
 * Available API 25+ (Nougat). Pre-Nougat devices get a no-op
 * implementation that always returns false / empty.
 *
 * We deliberately use a generic icon (an existing app launcher icon)
 * because apps that need custom shortcut icons can build their own
 * [ShortcutInfo] directly. The substrate's job is the LLM-facing
 * "push a shortcut for X" use case, not full shortcut design.
 */
class AndroidAppShortcuts(context: Context) : AppShortcuts {
    private val appContext: Context = context.applicationContext

    @Suppress("NewApi")
    private val mgr: ShortcutManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            appContext.getSystemService(ShortcutManager::class.java)
        } else null

    override suspend fun push(spec: ShortcutSpec): Boolean = withContext(Dispatchers.IO) {
        val m = mgr ?: return@withContext false
        val intent = buildIntent(spec.target) ?: return@withContext false
        val builder = ShortcutInfo.Builder(appContext, spec.id)
            .setShortLabel(spec.shortLabel)
            .setLongLabel(spec.longLabel ?: spec.shortLabel)
            .setIntent(intent)
            .setIcon(defaultIcon())
        val info = runCatching { builder.build() }.getOrNull() ?: return@withContext false

        // pushDynamicShortcut (API 30+) replaces; addDynamicShortcuts
        // is the API 25 equivalent that merges by id. Use the older
        // form for compatibility.
        runCatching { m.addDynamicShortcuts(listOf(info)) }.isSuccess
    }

    override suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        val m = mgr ?: return@withContext false
        runCatching { m.removeDynamicShortcuts(listOf(id)) }.isSuccess
    }

    override suspend fun list(): List<ShortcutSpec> = withContext(Dispatchers.IO) {
        val m = mgr ?: return@withContext emptyList()
        runCatching {
            m.dynamicShortcuts.map { info ->
                ShortcutSpec(
                    id = info.id,
                    shortLabel = info.shortLabel?.toString().orEmpty(),
                    longLabel = info.longLabel?.toString(),
                    target = info.intent?.dataString
                        ?: info.intent?.`package`
                        ?: "",
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun buildIntent(target: String): Intent? {
        val intent = when {
            target.contains(':') -> Intent(Intent.ACTION_VIEW, Uri.parse(target))
            else -> appContext.packageManager.getLaunchIntentForPackage(target)
                ?: return null
        }
        // ShortcutManager requires an explicit action — fall back to MAIN.
        if (intent.action == null) intent.action = Intent.ACTION_VIEW
        return intent
    }

    private fun defaultIcon(): Icon {
        // Use this app's own launcher icon as the shortcut icon. Apps
        // that want custom icons should build ShortcutInfo themselves.
        val iconRes = runCatching {
            appContext.packageManager.getApplicationInfo(appContext.packageName, 0).icon
        }.getOrDefault(0)
        return if (iconRes != 0) Icon.createWithResource(appContext, iconRes)
        else Icon.createWithResource("android", android.R.drawable.sym_def_app_icon)
    }
}
