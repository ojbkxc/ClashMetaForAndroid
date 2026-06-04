package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.v2board.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object V2BoardAutoSync {
    private const val TAG = "V2BoardAutoSync"
    private const val MAX_RETRY = 2

    suspend fun sync(context: Context, subscribeUrl: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val intervalMinutes = ConfigManager.getSyncIntervalMinutes()
                val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes)
                val profileName = ConfigManager.getAppName()

                Log.d("$TAG: Syncing subscription: name=$profileName, url=$subscribeUrl, interval=${intervalMinutes}min")

                // 验证订阅URL格式
                if (!subscribeUrl.startsWith("http://") && !subscribeUrl.startsWith("https://")) {
                    Log.w("$TAG: Invalid subscribe URL format: $subscribeUrl")
                    return@withContext Result.failure(Exception("Invalid subscribe URL format"))
                }

                withProfile {
                    val allProfiles = queryAll()
                    Log.d("$TAG: Total profiles: ${allProfiles.size}")

                    // 优先匹配名称为应用名的配置，其次匹配包含subscribe关键词的URL配置
                    val existing = allProfiles.find {
                        it.type == Profile.Type.Url && it.name == profileName
                    } ?: allProfiles.find {
                        it.type == Profile.Type.Url &&
                                (it.source.contains("subscribe") || it.source.contains("clash"))
                    }

                    if (existing != null) {
                        Log.d("$TAG: Found existing profile: ${existing.uuid}, name=${existing.name}")
                        // 更新现有配置
                        patch(existing.uuid, profileName, subscribeUrl, intervalMs)
                        var updateSuccess = false
                        for (attempt in 1..MAX_RETRY) {
                            try {
                                update(existing.uuid)
                                updateSuccess = true
                                Log.d("$TAG: Updated existing profile: ${existing.uuid} (attempt $attempt)")
                                break
                            } catch (e: Exception) {
                                Log.w("$TAG: Update attempt $attempt failed: ${e.message}")
                                if (attempt < MAX_RETRY) {
                                    delay(1000L * attempt)
                                }
                            }
                        }
                        if (updateSuccess) {
                            // 重新查询获取最新状态
                            val updated = queryByUUID(existing.uuid)
                            if (updated != null) {
                                setActive(updated)
                                Log.d("$TAG: Set active profile: ${updated.uuid}")
                            }
                            Result.success("Subscription updated")
                        } else {
                            Result.failure(Exception("Failed to update subscription after $MAX_RETRY attempts"))
                        }
                    } else {
                        Log.d("$TAG: No existing profile found, creating new one")
                        // 创建新配置
                        val uuid = create(Profile.Type.Url, profileName, subscribeUrl)
                        Log.d("$TAG: Created pending profile: $uuid")
                        patch(uuid, profileName, subscribeUrl, intervalMs)
                        var commitSuccess = false
                        for (attempt in 1..MAX_RETRY) {
                            try {
                                commit(uuid)
                                commitSuccess = true
                                Log.d("$TAG: Committed profile: $uuid (attempt $attempt)")
                                break
                            } catch (e: Exception) {
                                Log.w("$TAG: Commit attempt $attempt failed: ${e.message}")
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
                                Result.success("Subscription added")
                            } else {
                                Log.w("$TAG: Profile created but not found: $uuid")
                                Result.failure(Exception("Profile created but not found"))
                            }
                        } else {
                            // 清理失败的配置
                            Log.w("$TAG: Cleaning up failed profile: $uuid")
                            try { delete(uuid) } catch (_: Exception) {}
                            Result.failure(Exception("Failed to create subscription after $MAX_RETRY attempts"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("$TAG sync failed: ${e.message}")
                Result.failure(e)
            }
        }
    }
}
