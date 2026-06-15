package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.v2board.ConfigManager
import com.github.kr328.clash.v2board.SyncLog
import com.github.kr328.clash.v2board.V2BoardSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object V2BoardAutoSync {
    private const val TAG = "V2BoardAutoSync"
    private const val MAX_RETRY = 2

    suspend fun sync(context: Context, subscribeUrl: String, email: String = ""): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val intervalMinutes = ConfigManager.getSyncIntervalMinutes()
                val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes)
                val baseName = ConfigManager.getAppName()
                val session = V2BoardSession(context)

                Log.d("$TAG: Syncing: baseName=$baseName, email=$email, interval=${intervalMinutes}min")
                SyncLog.add("开始同步订阅: $baseName")
                SyncLog.add("订阅URL: ${SyncLog.maskUrl(subscribeUrl)}")
                if (email.isNotBlank()) {
                    SyncLog.add("用户邮箱: $email")
                }

                if (!subscribeUrl.startsWith("http://") && !subscribeUrl.startsWith("https://")) {
                    Log.w("$TAG: Invalid subscribe URL format: $subscribeUrl")
                    SyncLog.add("错误: 订阅URL格式无效")
                    return@withContext Result.failure(Exception("Invalid subscribe URL format"))
                }

                withProfile {
                    val allProfiles = queryAll()
                    Log.d("$TAG: Total profiles: ${allProfiles.size}")
                    SyncLog.add("当前配置文件数量: ${allProfiles.size}")

                    // 按 email→UUID 映射精确匹配（多账号场景核心逻辑）
                    val existing = findExistingProfile(allProfiles, session, email, baseName)

                    if (existing != null) {
                        Log.d("$TAG: Found existing profile: ${existing.uuid}, name=${existing.name}")
                        SyncLog.add("找到已有配置: ${existing.name} (UUID: ${existing.uuid})")
                        patch(existing.uuid, baseName, subscribeUrl, intervalMs)
                        SyncLog.add("正在更新配置...")
                        var updateSuccess = false
                        for (attempt in 1..MAX_RETRY) {
                            try {
                                withContext(NonCancellable) {
                                    update(existing.uuid)
                                }
                                updateSuccess = true
                                Log.d("$TAG: Updated profile: ${existing.uuid} (attempt $attempt)")
                                SyncLog.add("配置更新成功 (尝试 $attempt)")
                                break
                            } catch (e: Exception) {
                                Log.w("$TAG: Update attempt $attempt failed: ${e.message}")
                                SyncLog.add("更新失败 (尝试 $attempt): ${e.message}")
                                if (attempt < MAX_RETRY) {
                                    delay(1000L * attempt)
                                }
                            }
                        }
                        if (updateSuccess) {
                            val updated = queryByUUID(existing.uuid)
                            if (updated != null) {
                                setActive(updated)
                                Log.d("$TAG: Set active profile: ${updated.uuid}")
                                SyncLog.add("已激活配置: ${updated.name}")
                            }
                            // 保存 email→UUID 映射
                            if (email.isNotBlank()) {
                                session.setProfileUuidForEmail(email, existing.uuid.toString())
                                SyncLog.add("已保存邮箱映射: $email → ${existing.uuid}")
                            }
                            session.v2boardProfileUuid = existing.uuid.toString()
                            Result.success("订阅已更新")
                        } else {
                            SyncLog.add("错误: 更新订阅失败，已重试 $MAX_RETRY 次")
                            Result.failure(Exception("Failed to update subscription after $MAX_RETRY attempts"))
                        }
                    } else {
                        Log.d("$TAG: No existing profile found, creating new one")
                        SyncLog.add("未找到已有配置，创建新配置...")

                        // 自动递增命名：直接使用邮箱，如果邮箱为空则使用 baseName
                        // 配置名称包含邮箱，方便识别不同账号
                        val newName = generateNextProfileName(allProfiles, baseName, email)
                        SyncLog.add("新配置名称: $newName")

                        val uuid = create(Profile.Type.Url, newName, subscribeUrl)
                        Log.d("$TAG: Created pending profile: $uuid")
                        SyncLog.add("配置已创建: $uuid ($newName)")

                        // 保存 email→UUID 映射
                        if (email.isNotBlank()) {
                            session.setProfileUuidForEmail(email, uuid.toString())
                            SyncLog.add("已保存邮箱映射: $email → $uuid")
                        }
                        session.v2boardProfileUuid = uuid.toString()

                        patch(uuid, newName, subscribeUrl, intervalMs)

                        var commitSuccess = false
                        for (attempt in 1..MAX_RETRY) {
                            try {
                                withContext(NonCancellable) {
                                    commit(uuid)
                                }
                                commitSuccess = true
                                Log.d("$TAG: Committed profile: $uuid (attempt $attempt)")
                                SyncLog.add("配置提交成功 (尝试 $attempt)")
                                break
                            } catch (e: Exception) {
                                Log.w("$TAG: Commit attempt $attempt failed: ${e.message}")
                                SyncLog.add("提交失败 (尝试 $attempt): ${e.message}")
                                if (attempt < MAX_RETRY) {
                                    delay(1000L * attempt)
                                }
                            }
                        }
                        if (commitSuccess) {
                            val profile = queryByUUID(uuid)
                            if (profile != null) {
                                setActive(profile)
                                Log.d("$TAG: Set active profile: ${profile.uuid}")
                                SyncLog.add("已激活配置: ${profile.name}")
                            }
                            Result.success("订阅已添加 ($newName)")
                        } else {
                            SyncLog.add("错误: 订阅下载失败，已重试 $MAX_RETRY 次")
                            Result.failure(Exception("Failed to commit subscription after $MAX_RETRY attempts"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("$TAG sync failed: ${e.message}")
                SyncLog.add("同步异常: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /**
     * 按优先级查找已存在的订阅配置：
     * 1. email → UUID 映射（最精确，多账号场景）
     *    如果 email 可用但无映射，说明是新账号，直接返回 null（不 fallback 到 UUID/名称）
     * 2. session 中保存的 UUID（仅当 email 不可用时）
     * 3. 同名匹配（fallback，仅当 email 不可用时）
     */
    private fun findExistingProfile(
        allProfiles: List<Profile>,
        session: V2BoardSession,
        email: String,
        baseName: String
    ): Profile? {
        // 1. 如果有 email，用 email→UUID 映射精确查找
        if (email.isNotBlank()) {
            val uuidByEmail = session.getProfileUuidForEmail(email)
            if (uuidByEmail != null) {
                val profile = allProfiles.find {
                    it.uuid.toString() == uuidByEmail && it.type == Profile.Type.Url
                }
                if (profile != null) {
                    SyncLog.add("通过邮箱映射找到配置: ${profile.name}")
                    return profile
                }
                // UUID 映射存在但配置已被删除，则当作新配置处理
                SyncLog.add("邮箱映射的配置已不存在，将创建新配置")
            } else {
                // email 可用但无映射 → 新账号，不 fallback 到旧账号的 UUID
                SyncLog.add("邮箱 $email 无映射，将创建新配置")
            }
            // 有 email 时不 fallback 到 session UUID 或名称匹配，防止跨账号覆写
            return null
        }

        // 2. email 不可用时，fallback 到 session 中保存的 UUID
        val savedUuid = session.v2boardProfileUuid
        if (savedUuid.isNotBlank()) {
            val profile = allProfiles.find {
                it.uuid.toString() == savedUuid && it.type == Profile.Type.Url
            }
            if (profile != null) {
                SyncLog.add("通过 session UUID 找到配置: ${profile.name}")
                return profile
            }
        }

        // 3. email 不可用时，fallback 到同名匹配
        val profile = allProfiles.find {
            it.type == Profile.Type.Url && it.name == baseName
        }
        if (profile != null) {
            SyncLog.add("通过名称匹配找到配置: ${profile.name}")
            return profile
        }

        return null
    }

    /**
     * 自动生成下一个配置名称：
     * - 如果有 email：直接使用完整邮箱
     * - 如果没有 email：使用 baseName + 数字后缀
     * 配置名称包含邮箱，方便识别不同账号
     */
    private fun generateNextProfileName(allProfiles: List<Profile>, baseName: String, email: String): String {
        val urlProfiles = allProfiles.filter { it.type == Profile.Type.Url }
        
        // 如果有 email，直接使用完整邮箱作为配置名称
        if (email.isNotBlank() && email.contains("@")) {
            // 检查是否已存在同名配置
            val existing = urlProfiles.find { it.name == email }
            if (existing == null) {
                // 没有同名配置，直接使用完整邮箱
                return email
            }
            // 存在同名配置，返回相同名称（后续会更新而不是创建）
            return email
        }
        
        // 邮箱无效时使用 baseName + 数字后缀
        val baseNameConfigs = urlProfiles.filter { 
            it.name == baseName || it.name.startsWith("$baseName-")
        }
        
        if (baseNameConfigs.isEmpty()) {
            return baseName
        }
        
        // 找最大数字后缀
        val maxSuffix = baseNameConfigs.map { profile ->
            if (profile.name == baseName) 0
            else profile.name.removePrefix("$baseName-").toIntOrNull() ?: 0
        }.maxOrNull() ?: 0
        
        return "$baseName-${maxSuffix + 1}"
    }

    /**
     * 修复VLESS配置的常见问题：
     * 1. 如果配置了Reality但没有client-fingerprint，自动添加
     * 2. 确保TLS配置正确
     * 这个方法在订阅URL被处理前调用，确保配置能够正常连接
     */
    private fun fixVlessConfig(configYaml: String): String {
        try {
            var fixed = configYaml
            
            // 检测是否包含VLESS + Reality配置
            val hasVlessReality = fixed.contains("type: vless") && 
                                  fixed.contains("reality-opts:")
            
            if (hasVlessReality) {
                // 检查每个proxies项
                val proxyRegex = Regex("""(?s)(- name:.*?(?=- name:|proxies:|\z))""")
                val matches = proxyRegex.findAll(fixed)
                
                for (match in matches) {
                    val proxyBlock = match.value
                    
                    // 检查是否是VLESS类型
                    if (!proxyBlock.contains("type: vless")) continue
                    
                    // 检查是否有reality-opts
                    if (!proxyBlock.contains("reality-opts:")) continue
                    
                    // 检查是否有client-fingerprint
                    if (proxyBlock.contains("client-fingerprint:")) continue
                    
                    Log.d("$TAG: Found VLESS+Reality config without client-fingerprint, adding default 'chrome'")
                    SyncLog.add("⚠️ 检测到VLESS+Reality配置缺少client-fingerprint，正在自动修复...")
                    
                    // 在reality-opts后添加client-fingerprint
                    val fixedProxyBlock = proxyBlock.replaceAfter(
                        "reality-opts:",
                        "\n  public-key: ${extractPublicKey(proxyBlock)}"
                    ).let { original ->
                        // 如果上面的替换没有添加内容（可能格式不同），尝试另一种方式
                        if (original == proxyBlock) {
                            val realityLine = proxyBlock.lines().find { it.trim().startsWith("public-key:") }
                            if (realityLine != null && realityLine.contains("public-key:")) {
                                // public-key已存在，不需要重复添加
                                proxyBlock
                            } else {
                                // 在reality-opts:后添加
                                proxyBlock.replace("reality-opts:", "reality-opts:\n  public-key: AUTO_FIXED")
                            }
                        } else {
                            // 检查是否需要添加完整配置
                            original
                        }
                    }
                    
                    // 在proxy块中查找并添加client-fingerprint（在server-name之后或tls之后）
                    val needsClientFingerprint = !fixedProxyBlock.contains("client-fingerprint:")
                    if (needsClientFingerprint) {
                        // 在server-name或tls之后添加client-fingerprint
                        val withFingerprint = fixedProxyBlock.let { block ->
                            val serverNameMatch = Regex("""server-name:.*?\n""").find(block)
                            if (serverNameMatch != null) {
                                block.replace(serverNameMatch.value, serverNameMatch.value + "  client-fingerprint: chrome\n")
                            } else {
                                val tlsMatch = Regex("""tls: (true|false)""").find(block)
                                if (tlsMatch != null) {
                                    block.replace(tlsMatch.value, tlsMatch.value + "\n  client-fingerprint: chrome")
                                } else {
                                    // 如果都找不到，在server之后添加
                                    val serverMatch = Regex("""server:.*?\n""").find(block)
                                    if (serverMatch != null) {
                                        block.replace(serverMatch.value, serverMatch.value + "  client-fingerprint: chrome\n")
                                    } else {
                                        block + "\n  client-fingerprint: chrome"
                                    }
                                }
                            }
                        }
                        
                        fixed = fixed.replace(proxyBlock, withFingerprint)
                        SyncLog.add("✅ 已自动添加 client-fingerprint: chrome")
                    }
                }
            }
            
            return fixed
        } catch (e: Exception) {
            Log.w("$TAG: Failed to fix VLESS config: ${e.message}")
            return configYaml
        }
    }
    
    /**
     * 从proxy配置块中提取public key
     */
    private fun extractPublicKey(proxyBlock: String): String {
        val publicKeyMatch = Regex("""public-key:\s*(\S+)""").find(proxyBlock)
        return publicKeyMatch?.groupValues?.get(1) ?: "AUTO_FIXED"
    }
}