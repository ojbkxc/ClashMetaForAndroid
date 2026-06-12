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

                        // 自动递增命名：蓝星 → 蓝星1 → 蓝星2 → ...
                        val newName = generateNextProfileName(allProfiles, baseName)
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
     * 2. session 中保存的 UUID
     * 3. 同名匹配（fallback）
     */
    private fun findExistingProfile(
        allProfiles: List<Profile>,
        session: V2BoardSession,
        email: String,
        baseName: String
    ): Profile? {
        // 1. 如果有 email，用 email→UUID 映射查找
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
            }
        }

        // 2. Fallback: 用 session 中保存的 UUID 查找
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

        // 3. Fallback: 同名匹配
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
     * 自动生成下一个配置名称：蓝星 → 蓝星1 → 蓝星2 → ...
     */
    private fun generateNextProfileName(allProfiles: List<Profile>, baseName: String): String {
        val urlProfiles = allProfiles.filter { it.type == Profile.Type.Url }

        // 如果没有同名配置，直接用 baseName
        if (urlProfiles.none { it.name == baseName }) {
            return baseName
        }

        // 找最大的后缀数字
        var maxSuffix = 0
        for (profile in urlProfiles) {
            val name = profile.name
            if (name.startsWith(baseName)) {
                val suffix = name.removePrefix(baseName)
                if (suffix.isEmpty()) {
                    // "蓝星" 本身算 0
                    maxSuffix = maxOf(maxSuffix, 0)
                } else {
                    val num = suffix.toIntOrNull()
                    if (num != null) {
                        maxSuffix = maxOf(maxSuffix, num)
                    }
                }
            }
        }

        return "$baseName${maxSuffix + 1}"
    }
}