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
import kotlin.text.Regex
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

                // 修复VLESS配置问题（添加缺失的client-fingerprint等）
                fixVlessConfigInDirectory(context.processingDir)

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
     * - 为VLESS+Reality配置自动添加client-fingerprint: chrome（Reality正常工作的必要条件）
     * - 将VLESS+Reality的WebSocket模式强制改为TCP（Reality在WS模式下不稳定）
     * - 强制启用TLS（Reality必须启用TLS）
     * - Reality支持XTLS flow（如xtls-rprx-vision），保留flow参数
     * - 为所有VLESS配置添加TLS（如果没有的话）
     * - 保留VLESS+WebSocket配置（mihomo内核支持VLESS+WS）
     */
    private fun fixVlessConfig(yaml: String): String {
        try {
            // 检测是否包含VLESS配置
            if (!yaml.contains("type:") || !yaml.contains("vless")) {
                return yaml
            }

            var fixed = yaml
            var modified = false

            Log.d("ProfileProcessor: Starting VLESS config analysis")
            
            // 使用更健壮的方式解析YAML
            val proxyBlocks = parseProxyBlocks(fixed)

            for ((originalBlock, proxyType) in proxyBlocks) {
                // 检查是否是VLESS类型
                if (!proxyType.startsWith("vless")) {
                    continue
                }

                Log.d("ProfileProcessor: Found VLESS proxy: ${originalBlock.lines().firstOrNull()?.trim()}")

                var currentBlock = originalBlock
                var blockModified = false

                // 对于所有VLESS配置：确保启用TLS（VLESS必须TLS）
                val tlsMatch = Regex("""tls:\s*(true|false)""", RegexOption.IGNORE_CASE).find(currentBlock)
                if (tlsMatch == null) {
                    Log.d("ProfileProcessor: VLESS config missing TLS, enabling TLS")
                    currentBlock = enableTLS(currentBlock)
                    blockModified = true
                } else if (tlsMatch.groupValues[1].equals("false", ignoreCase = true)) {
                    Log.d("ProfileProcessor: VLESS config has TLS disabled, enabling TLS")
                    currentBlock = currentBlock.replaceFirst(
                        Regex("""tls:\s*false""", RegexOption.IGNORE_CASE),
                        "tls: true"
                    )
                    blockModified = true
                }

                // 对于VLESS+Reality配置的特殊处理
                if (currentBlock.contains("reality-opts:")) {
                    Log.d("ProfileProcessor: Found VLESS+Reality configuration")
                    
                    // Reality在WebSocket模式下可能不稳定，建议使用TCP
                    if (currentBlock.contains("network: ws") || currentBlock.contains("network: websocket")) {
                        Log.d("ProfileProcessor: Found VLESS+Reality with WebSocket, forcing TCP mode")
                        currentBlock = currentBlock.replace("network: ws", "network: tcp")
                                                    .replace("network: websocket", "network: tcp")
                        blockModified = true
                    }
                    
                    // Reality需要client-fingerprint
                    if (!currentBlock.contains("client-fingerprint:")) {
                        Log.d("ProfileProcessor: Adding client-fingerprint for Reality")
                        currentBlock = addClientFingerprint(currentBlock)
                        blockModified = true
                    }
                }

                // 检查并修复其他常见问题
                // 确保有servername（用于TLS SNI，mihomo使用servername而非server-name）
                val hasTls = currentBlock.contains("tls:") || currentBlock.contains("tls :")
                val hasServerName = currentBlock.contains("server-name:") || currentBlock.contains("servername:")
                
                if (hasTls && !hasServerName) {
                    // 从server字段提取域名作为servername
                    val serverMatch = Regex("""server:\s*(.+)""").find(currentBlock)
                    if (serverMatch != null) {
                        var serverValue = serverMatch.groupValues[1].trim()
                        // 移除引号
                        serverValue = serverValue.removeSurrounding("\"", "\"").removeSurrounding("'", "'")
                        
                        // 检查是否是IP地址，如果是IP地址则不添加servername
                        // IP地址格式：xxx.xxx.xxx.xxx 或 [ipv6]
                        val isIpAddress = Regex("""^\d+\.\d+\.\d+\.\d+$""").matches(serverValue) || 
                                         serverValue.startsWith("[") && serverValue.endsWith("]")
                        
                        if (!isIpAddress) {
                            Log.d("ProfileProcessor: Adding servername: $serverValue")
                            currentBlock = addServerName(currentBlock, serverValue)
                            blockModified = true
                        } else {
                            Log.d("ProfileProcessor: Server is IP address, skipping servername")
                        }
                    }
                }

                if (blockModified) {
                    Log.d("ProfileProcessor: Applying fixes to VLESS proxy")
                    fixed = fixed.replace(originalBlock, currentBlock)
                    modified = true
                }
            }

            return if (modified) {
                Log.d("ProfileProcessor: VLESS config fixed successfully")
                fixed
            } else {
                Log.d("ProfileProcessor: VLESS config already correct, no changes needed")
                yaml
            }
        } catch (e: Exception) {
            Log.w("ProfileProcessor: Error fixing VLESS config: ${e.message}")
            return yaml
        }
    }

    /**
     * 增强的proxy块解析器
     */
    private fun parseProxyBlocks(yaml: String): List<Pair<String, String>> {
        val blocks = mutableListOf<Pair<String, String>>()
        val lines = yaml.lines().toMutableList()
        
        var inProxiesSection = false
        var currentBlockLines = mutableListOf<String>()
        var currentProxyType = ""
        var braceCount = 0
        var inBlock = false
        
        for ((lineIndex, line) in lines.withIndex()) {
            val trimmed = line.trim()
            
            // 检测proxies部分开始
            if (trimmed == "proxies:") {
                inProxiesSection = true
                continue
            }
            
            // 检测其他顶级配置项（结束proxies部分）
            if (inProxiesSection && (
                trimmed.startsWith("proxy-providers:") ||
                trimmed.startsWith("proxy-groups:") ||
                trimmed.startsWith("rules:") ||
                trimmed.startsWith("dns:") ||
                trimmed == "---"
            )) {
                // 保存当前块
                if (currentBlockLines.isNotEmpty() && currentProxyType.isNotEmpty()) {
                    blocks.add(Pair(currentBlockLines.joinToString("\n"), currentProxyType))
                }
                currentBlockLines.clear()
                currentProxyType = ""
                inProxiesSection = false
                continue
            }
            
            // 在proxies部分中
            if (inProxiesSection) {
                // 检测新的proxy项开始
                if (trimmed.startsWith("- name:")) {
                    // 保存之前的块
                    if (currentBlockLines.isNotEmpty() && currentProxyType.isNotEmpty()) {
                        blocks.add(Pair(currentBlockLines.joinToString("\n"), currentProxyType))
                    }
                    // 开始新块
                    currentBlockLines = mutableListOf(line)
                    currentProxyType = ""
                    braceCount = 0
                    inBlock = true
                } else if (inBlock) {
                    currentBlockLines.add(line)
                    
                    // 检测type字段
                    if (trimmed.startsWith("type:") && currentBlockLines.size > 1) {
                        currentProxyType = trimmed.substringAfter("type:").trim()
                    }
                    
                    // 检测proxy块结束（遇到下一个 - name: 或其他顶级配置）
                    if (lineIndex < lines.size - 1) {
                        val nextTrimmed = lines.getOrNull(lineIndex + 1)?.trim() ?: ""
                        if (nextTrimmed.startsWith("- name:") || 
                            (!nextTrimmed.startsWith(" ") && !nextTrimmed.startsWith("\t") && nextTrimmed.isNotEmpty())) {
                            if (currentProxyType.isNotEmpty()) {
                                blocks.add(Pair(currentBlockLines.joinToString("\n"), currentProxyType))
                            }
                            currentBlockLines.clear()
                            currentProxyType = ""
                            inBlock = false
                        }
                    }
                }
            }
        }
        
        // 保存最后一个块
        if (currentBlockLines.isNotEmpty() && currentProxyType.isNotEmpty()) {
            blocks.add(Pair(currentBlockLines.joinToString("\n"), currentProxyType))
        }
        
        return blocks
    }

    /**
     * 启用TLS配置
     */
    private fun enableTLS(proxyBlock: String): String {
        val lines = proxyBlock.lines().toMutableList()
        val indent = detectIndent(proxyBlock)

        // 在uuid之后添加tls: true
        var insertIndex = -1
        for ((index, line) in lines.withIndex()) {
            if (line.trim().startsWith("uuid:")) {
                insertIndex = index + 1
                break
            }
        }

        // 如果没找到，在server之后插入
        if (insertIndex == -1) {
            for ((index, line) in lines.withIndex()) {
                if (line.trim().startsWith("server:")) {
                    insertIndex = index + 1
                    break
                }
            }
        }

        return if (insertIndex > 0 && insertIndex < lines.size) {
            lines.add(insertIndex, "$indent tls: true")
            lines.joinToString("\n")
        } else {
            proxyBlock
        }
    }

    /**
     * 添加servername（mihomo内核使用servername而非server-name）
     */
    private fun addServerName(proxyBlock: String, serverName: String): String {
        val lines = proxyBlock.lines().toMutableList()
        val indent = detectIndent(proxyBlock)

        // 移除引号（如果有）
        val cleanServerName = serverName.removeSurrounding("\"", "\"")
                                        .removeSurrounding("'", "'")

        // 在server之后添加servername
        var insertIndex = -1
        for ((index, line) in lines.withIndex()) {
            if (line.trim().startsWith("server:")) {
                insertIndex = index + 1
                break
            }
        }

        return if (insertIndex > 0 && insertIndex < lines.size) {
            lines.add(insertIndex, "$indent servername: $cleanServerName")
            lines.joinToString("\n")
        } else {
            proxyBlock
        }
    }

    /**
     * 检测缩进
     */
    private fun detectIndent(proxyBlock: String): String {
        // 找到第一个非空行
        for (line in proxyBlock.lines()) {
            if (line.trim().startsWith("- name:")) {
                // 这是块的开始，返回子元素的缩进（多2个空格）
                return "  "
            }
            // 查找第一个有缩进的配置行
            val match = Regex("""^(\s*)\w+:""").find(line)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return "  "
    }

    /**
     * 为proxy块添加client-fingerprint
     */
    private fun addClientFingerprint(proxyBlock: String): String {
        val lines = proxyBlock.lines().toMutableList()
        val indent = detectIndent(proxyBlock)

        // 查找合适的插入位置（在tls或server-name之后）
        var insertIndex = -1
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("server-name:") || 
                trimmed.startsWith("tls:") ||
                trimmed.startsWith("servername:")) {
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

        // 如果找到了插入位置，插入client-fingerprint
        return if (insertIndex > 0 && insertIndex < lines.size) {
            lines.add(insertIndex, "$indent client-fingerprint: chrome")
            lines.joinToString("\n")
        } else {
            proxyBlock
        }
    }

    /**
     * 分割YAML中的proxy块（保留旧方法以兼容）
     */
    private fun splitProxyBlocks(yaml: String): List<Pair<String, String>> {
        return parseProxyBlocks(yaml)
    }
}