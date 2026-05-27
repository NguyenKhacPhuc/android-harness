package dev.weft.osbridge.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.weft.contracts.SettingsPanel
import dev.weft.contracts.SystemSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of [SystemSettings]. One intent action per
 * panel. All set FLAG_ACTIVITY_NEW_TASK since we launch from
 * application context. Returns false if the action doesn't resolve
 * on the device (rare on stock Android; possible on heavily-modified
 * AOSP forks).
 */
public class AndroidSystemSettings(context: Context) : SystemSettings {
    private val appContext: Context = context.applicationContext

    override suspend fun open(panel: SettingsPanel): Boolean = withContext(Dispatchers.Default) {
        val intent = intentFor(panel) ?: return@withContext false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }.isSuccess
    }

    private fun intentFor(panel: SettingsPanel): Intent? = when (panel) {
        SettingsPanel.APP_DETAILS ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", appContext.packageName, null))

        SettingsPanel.APP_NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)

        SettingsPanel.NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        SettingsPanel.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsPanel.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        SettingsPanel.DATA_USAGE -> Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
        SettingsPanel.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        SettingsPanel.DATE -> Intent(Settings.ACTION_DATE_SETTINGS)
        SettingsPanel.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SettingsPanel.BATTERY -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        SettingsPanel.DISPLAY -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        SettingsPanel.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        SettingsPanel.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        SettingsPanel.DEFAULT_APPS -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        SettingsPanel.ROOT -> Intent(Settings.ACTION_SETTINGS)
    }
}
