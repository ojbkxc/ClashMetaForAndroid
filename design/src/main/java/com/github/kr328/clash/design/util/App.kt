package com.github.kr328.clash.design.util

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.github.kr328.clash.common.compat.foreground
import com.github.kr328.clash.design.model.AppInfo

fun PackageInfo.toAppInfo(pm: PackageManager): AppInfo {
    val info = applicationInfo
    return AppInfo(
        packageName = packageName,
        icon = info?.loadIcon(pm)?.foreground() ?: pm.getApplicationIcon(packageName),
        label = info?.loadLabel(pm)?.toString() ?: packageName,
        installTime = firstInstallTime,
        updateDate = lastUpdateTime,
    )
}
