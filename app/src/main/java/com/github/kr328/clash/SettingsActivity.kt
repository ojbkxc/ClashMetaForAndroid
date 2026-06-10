package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.SettingsDesign
import com.github.kr328.clash.SyncLogActivity
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

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
                        SettingsDesign.Request.StartPermissionInfo ->
                            startActivity(PermissionInfoActivity::class.intent)
                        SettingsDesign.Request.ShareApp -> {
                            val downloadUrl = "https://github.com/ojbkxc/ClashMetaForAndroid/releases/download/Prerelease-alpha/cmfa-2.11.29-alpha-arm64-v8a-release.apk"
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