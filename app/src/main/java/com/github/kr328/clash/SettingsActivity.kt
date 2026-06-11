package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.SyncLogActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class SettingsActivity : BaseActivity<SettingsDesign>() {
    override suspend fun main() {
        val design = SettingsDesign(this)

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        SettingsDesign.Request.StartApp ->
                            startActivity(AppSettingsActivity::class.intent)
                        SettingsDesign.Request.StartNetwork ->
                            startActivity(NetworkSettingsActivity::class.intent)
                        SettingsDesign.Request.StartOverride ->
                            startActivity(OverrideSettingsActivity::class.intent)
                        SettingsDesign.Request.StartMetaFeature ->
                            startActivity(MetaFeatureSettingsActivity::class.intent)
                        SettingsDesign.Request.ViewSyncLog ->
                            startActivity(SyncLogActivity::class.intent)
                        SettingsDesign.Request.StartRoot ->
                            startActivity(RootSettingsActivity::class.intent)
                        SettingsDesign.Request.ShareApp -> {
                            launch {
                                val downloadUrl = getShareUrl()
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Download URL", downloadUrl)
                                clipboard.setPrimaryClip(clip)
                                design.showToast(getString(com.github.kr328.clash.design.R.string.copied_to_clipboard), com.github.kr328.clash.design.ui.ToastDuration.Short)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun getShareUrl(): String {
        return withContext(Dispatchers.IO) {
            try {
                val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                val release = UpdateChecker.checkForUpdate(this@SettingsActivity)
                
                release?.apkDownloadUrl ?: generateCurrentVersionUrl(currentVersion)
            } catch (e: Exception) {
                val currentVersion = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                } catch (ex: Exception) {
                    "unknown"
                }
                generateCurrentVersionUrl(currentVersion)
            }
        }
    }

    private fun generateCurrentVersionUrl(version: String): String {
        val versionTag = if (version.startsWith("v")) version else "v$version"
        return "https://github.com/ojbkxc/ClashMetaForAndroid/releases/download/$versionTag/cmfa-$version-arm64-v8a-release.apk"
    }
}