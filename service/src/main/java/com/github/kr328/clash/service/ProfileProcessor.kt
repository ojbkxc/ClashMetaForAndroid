package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import java.io.File
import java.util.regex.Regex
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.net.URL
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending = PendingDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                val force = snapshot.type != Profile.Type.File
                var cb = callback

                Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                    try {
                        cb?.updateStatus(it)
                    } catch (e: Exception) {
                        cb = null

                        Log.w("Report fetch status: $e", e)
                    }
                }.await()

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString())
                            .deleteRecursively()
                        context.processingDir
                            .copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        var upload: Long = 0
                        var download: Long = 0
                        var total: Long = 0
                        var expire: Long = 0
                        var updateInterval: Long = snapshot.interval
                        if (snapshot?.type == Profile.Type.Url) {
                            if (snapshot.source.startsWith("https://", true)) {
                                val client = OkHttpClient()
                                val versionName = context.packageManager.getPackageInfo(context.packageName, 0).versionName
                                val request = Request.Builder()
                                    .url(snapshot.source)
                                    .header("User-Agent", "ClashMetaForAndroid/$versionName")
                                    .build()

                                client.newCall(request).execute().use { response ->
                                    val userinfo = response.headers["subscription-userinfo"]
                                    if (response.isSuccessful && userinfo != null) {
                                        val flags = userinfo.split(";")
                                        for (flag in flags) {
                                            val info = flag.split("=")
                                            if (info.size < 2 || info[1].isEmpty()) continue
                                            when {
                                                info[0].contains("upload") -> upload =
                                                    BigDecimal(info[1].split('.').first()).longValueExact()

                                                info[0].contains("download") -> download =
                                                    BigDecimal(info[1].split('.').first()).longValueExact()

                                                info[0].contains("total") -> total =
                                                    BigDecimal(info[1].split('.').first()).longValueExact()

                                                info[0].contains("expire") ->
                                                    expire = (info[1].toDouble() * 1000).toLong()
                                            }
                                        }
                                    }

                                    val updateIntervalHeader = response.headers["profile-update-interval"]
                                    if (old == null && snapshot.interval == 0L && response.isSuccessful && updateIntervalHeader != null) {
                                        val intervalHours = updateIntervalHeader.toLongOrNull()
                                        if (intervalHours != null) {
                                            updateInterval = if (intervalHours > 0) {
                                                java.util.concurrent.TimeUnit.HOURS.toMillis(intervalHours)
                                                    .coerceAtLeast(java.util.concurrent.TimeUnit.MINUTES.toMillis(15))
                                            } else {
                                                0L
                                            }
                                        }
                                    }
                                }
                            }
                            val new = Imported(
                                snapshot.uuid,
                                snapshot.name,
                                snapshot.type,
                                snapshot.source,
                                updateInterval,
                                upload,
                                download,
                                total,
                                expire,
                                old?.createdAt ?: System.currentTimeMillis()
                            )
                            if (old != null) {
                                ImportedDao().update(new)
                            } else {
                                ImportedDao().insert(new)
                            }

                            PendingDao().remove(snapshot.uuid)

                            context.pendingDir.resolve(snapshot.uuid.toString())
                                .deleteRecursively()

                            context.sendProfileChanged(snapshot.uuid)
                        } else if (snapshot?.type == Profile.Type.File) {
                            val new = Imported(
                                snapshot.uuid,
                                snapshot.name,
                                snapshot.type,
                                snapshot.source,
                                snapshot.interval,
                                upload,
                                download,
                                total,
                                expire,
                                old?.createdAt ?: System.currentTimeMillis()
                            )
                            if (old != null) {
                                ImportedDao().update(new)
                            } else {
                                ImportedDao().insert(new)
                            }

                            PendingDao().remove(snapshot.uuid)

                            context.pendingDir.resolve(snapshot.uuid.toString())
                                .deleteRecursively()

                            context.sendProfileChanged(snapshot.uuid)
                        }
                    }
                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                var cb = callback

                Clash.fetchAndValid(context.processingDir, snapshot.source, true) {
                    try {
                        cb?.updateStatus(it)
                    } catch (e: Exception) {
                        cb = null

                        Log.w("Report fetch status: $e", e)
                    }
                }.await()

                // 修复VLESS配置问题（添加缺失的client-fingerprint）
                fixVlessConfigInDirectory(context.processingDir)

                profileLock.withLock {
                    if (ImportedDao().exists(snapshot.uuid)) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir
                            .copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)

                val pending = context.pendingDir.resolve(uuid.toString())
                val imported = context.importedDir.resolve(uuid.toString())

                pending.deleteRecursively()
                imported.deleteRecursively()

                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)

                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)

                    store.activeProfile = uuid

                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())

        when {
            name.isBlank() ->
                throw IllegalArgumentException("Empty name")

            source.isEmpty() && type != Profile.Type.File ->
                throw IllegalArgumentException("Invalid url")

            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" ->
                throw IllegalArgumentException("Unsupported url $source")

            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 ->
                throw IllegalArgumentException("Invalid interval")
        }
    }

    /**
     * 修复配置目录中的VLESS配置问题：
     * 自动为VLESS+Reality配置添加缺失的client-fingerprint
     * 这解决了Reality需要uTLS指纹但配置中未指定的问题
     */
    private fun fixVlessConfigInDirectory(dir: File) {
        try {
            val yamlFiles = dir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".yaml") || it.name.endsWith(".yml")) }
                .toList()

            if (yamlFiles.isEmpty()) return

            Log.d("ProfileProcessor: Checking ${yamlFiles.size} config files for VLESS fixes")

            for (yamlFile in yamlFiles) {
                try {
                    val content = yamlFile.readText()
                    val fixed = fixVlessConfig(content)
                    if (fixed != content) {
                        yamlFile.writeText(fixed)
                        Log.d("ProfileProcessor: Fixed VLESS config in ${yamlFile.name}")
                    }
                } catch (e: Exception) {
                    Log.w("ProfileProcessor: Failed to fix ${yamlFile.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w("ProfileProcessor: Failed to fix VLESS configs: ${e.message}")
        }
    }

    /**
     * 修复VLESS配置的YAML内容：
     * - 为VLESS+Reality配置自动添加client-fingerprint: chrome
     * - 这是Reality正常工作的必要条件
     */
    private fun fixVlessConfig(yaml: String): String {
        try {
            // 检测是否包含VLESS配置
            if (!yaml.contains("type:") || !yaml.contains("vless")) {
                return yaml
            }

            var fixed = yaml
            var modified = false

            // 强制使用TCP模式：移除所有WebSocket相关配置
            // 因为Reality在WebSocket模式下无法正常工作
            if (fixed.contains("network: ws") || fixed.contains("network: websocket")) {
                Log.d("ProfileProcessor: Found WebSocket config with VLESS, forcing TCP mode")
                fixed = fixed.replace("network: ws", "network: tcp")
                fixed = fixed.replace("network: websocket", "network: tcp")
                modified = true
            }

            val hasReality = fixed.contains("reality-opts:")
            if (!hasReality) {
                return if (modified) fixed else yaml
            }

            // 分割proxies块（处理多proxy配置）
            val proxyBlocks = splitProxyBlocks(fixed)

            for ((originalBlock, blockIndex) in proxyBlocks.withIndex()) {
                // 检查是否是VLESS类型
                if (!blockIndex.toString().startsWith("vless")) {
                    continue
                }

                // 检查是否有reality-opts但没有client-fingerprint
                if (!blockIndex.toString().contains("vless")) continue
                if (!originalBlock.contains("reality-opts:")) continue
                if (originalBlock.contains("client-fingerprint:")) continue

                Log.d("ProfileProcessor: Found VLESS+Reality without client-fingerprint, adding chrome fingerprint")

                // 修复：添加client-fingerprint
                val fixedBlock = addClientFingerprint(originalBlock)
                if (fixedBlock != originalBlock) {
                    fixed = fixed.replace(originalBlock, fixedBlock)
                    modified = true
                }
            }

            return if (modified) {
                Log.d("ProfileProcessor: VLESS config fixed successfully")
                fixed
            } else {
                yaml
            }
        } catch (e: Exception) {
            Log.w("ProfileProcessor: Error fixing VLESS config: ${e.message}")
            return yaml
        }
    }

    /**
     * 分割YAML中的proxy块
     */
    private fun splitProxyBlocks(yaml: String): List<Pair<String, String>> {
        val blocks = mutableListOf<Pair<String, String>>()
        val lines = yaml.lines()
        var currentBlock = StringBuilder()
        var currentType = ""
        var inProxies = false
        var proxyIndent = 0

        for (line in lines) {
            when {
                // 检测proxies开始
                line.trim().startsWith("proxies:") -> {
                    inProxies = true
                    proxyIndent = line.indexOf("proxies")
                }
                // 检测其他顶级配置项
                line.trim().startsWith("proxy-providers:") ||
                line.trim().startsWith("proxy-groups:") ||
                line.trim().startsWith("rules:") -> {
                    if (currentBlock.isNotEmpty() && currentType.isNotEmpty()) {
                        blocks.add(Pair(currentBlock.toString(), currentType))
                    }
                    currentBlock.clear()
                    currentType = ""
                    inProxies = false
                }
                // 在proxies块中，检测单个proxy的开始
                inProxies && line.trim().startsWith("- name:") -> {
                    if (currentBlock.isNotEmpty() && currentType.isNotEmpty()) {
                        blocks.add(Pair(currentBlock.toString(), currentType))
                    }
                    currentBlock.clear()
                    currentType = ""
                    currentBlock.appendLine(line)
                }
                // 在proxy块中，检测type
                inProxies && line.trim().startsWith("type:") && currentBlock.isNotEmpty() -> {
                    currentType = line.substringAfter("type:").trim()
                    currentBlock.appendLine(line)
                }
                // 继续收集当前proxy的内容
                inProxies && currentBlock.isNotEmpty() -> {
                    currentBlock.appendLine(line)
                }
                // 不在proxies块中的内容
                !inProxies -> {
                    currentBlock.appendLine(line)
                }
            }
        }

        // 添加最后一个块
        if (currentBlock.isNotEmpty() && currentType.isNotEmpty()) {
            blocks.add(Pair(currentBlock.toString(), currentType))
        }

        return blocks
    }

    /**
     * 为proxy块添加client-fingerprint
     */
    private fun addClientFingerprint(proxyBlock: String): String {
        val lines = proxyBlock.lines().toMutableList()
        val indent = "  "

        // 查找合适的插入位置（在tls或server-name之后）
        var insertIndex = -1
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("server-name:") || trimmed.startsWith("tls:")) {
                insertIndex = index + 1
            }
        }

        // 如果没找到，在uuid之后插入
        if (insertIndex == -1) {
            for ((index, line) in lines.withIndex()) {
                if (line.trim().startsWith("uuid:")) {
                    insertIndex = index + 1
                    break
                }
            }
        }

        // 如果还是没找到，在server之后插入
        if (insertIndex == -1) {
            for ((index, line) in lines.withIndex()) {
                if (line.trim().startsWith("server:")) {
                    insertIndex = index + 1
                    break
                }
            }
        }

        // 如果找到了插入位置，插入client-fingerprint
        return if (insertIndex > 0 && insertIndex < lines.size) {
            lines.add(insertIndex, "$indent client-fingerprint: chrome")
            lines.joinToString("\n")
        } else {
            proxyBlock
        }
    }
}