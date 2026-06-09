package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.util.AppLog
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.v2board.ConfigManager
import com.github.kr328.clash.v2board.SyncLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

                AppLog.d(TAG, " Syncing subscription: name=$profileName, url=***, interval=${intervalMinutes}min")
                SyncLog.add("开始同步订阅: $profileName")
                SyncLog.add("订阅URL: ${SyncLog.maskUrl(subscribeUrl)}")

                // 验证订阅URL格式
                if (!subscribeUrl.startsWith("http://") && !subscribeUrl.startsWith("https://")) {
                    AppLog.w(TAG, " Invalid subscribe URL format: $subscribeUrl")
                    SyncLog.add("错误: 订阅URL格式无效")
                    return@withContext Result.failure(Exception("Invalid subscribe URL format"))
                }

                withProfile {
                    val allProfiles = queryAll()
                    AppLog.d(TAG, " Total profiles: ${allProfiles.size}")
                    SyncLog.add("当前配置文件数量: ${allProfiles.size}")

                    // 优先匹配名称为应用名的配置，其次匹配包含subscribe关键词的URL配置
                    val existing = allProfiles.find {
                        it.type == Profile.Type.Url && it.name == profileName
                    } ?: allProfiles.find {
                        it.type == Profile.Type.Url &&
                                (it.source.contains("subscribe") || it.source.contains("clash"))
                    }

                    if (existing != null) {
                        AppLog.d(TAG, " Found existing profile: ${existing.uuid}, name=${existing.name}")
                        SyncLog.add("找到已有配置: ${existing.name}")
                        // 更新现有配置
                        patch(existing.uuid, profileName, subscribeUrl, intervalMs)
                        SyncLog.add("正在更新配置...")
                        var updateSuccess = false
                        for (attempt in 1..MAX_RETRY) {
                            try {
                                withContext(NonCancellable) {
                                    update(existing.uuid)
                                }
                                updateSuccess = true
                                AppLog.d(TAG, " Updated existing profile: ${existing.uuid} (attempt $attempt)")
                                SyncLog.add("配置更新成功 (尝试 $attempt)")
                                break
                            } catch (e: Exception) {
                                AppLog.w(TAG, " Update attempt $attempt failed: ${e.message}")
                                SyncLog.add("更新失败 (尝试 $attempt): ${e.message}")
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
                                AppLog.d(TAG, " Set active profile: ${updated.uuid}")
                                SyncLog.add("已激活配置: ${updated.name}")
                            }
                            Result.success("订阅已更新")
                        } else {
                            SyncLog.add("错误: 更新订阅失败，已重试 $MAX_RETRY 次")
                            Result.failure(Exception("Failed to update subscription after $MAX_RETRY attempts"))
                        }
                    } else {
                        AppLog.d(TAG, " No existing profile found, creating new one")
                        SyncLog.add("未找到已有配置，创建新配置...")
                        // 创建新配置
                        val uuid = create(Profile.Type.Url, profileName, subscribeUrl)
                        AppLog.d(TAG, " Created pending profile: $uuid")
                        SyncLog.add("配置已创建: $uuid")
                        patch(uuid, profileName, subscribeUrl, intervalMs)

                        var commitSuccess = false
                        for (attempt in 1..MAX_RETRY) {
                            try {
                                withContext(NonCancellable) {
                                    commit(uuid)
                                }
                                commitSuccess = true
                                AppLog.d(TAG, " Committed profile: $uuid (attempt $attempt)")
                                SyncLog.add("配置提交成功 (尝试 $attempt)")
                                break
                            } catch (e: Exception) {
                                AppLog.w(TAG, " Commit attempt $attempt failed: ${e.message}")
                                SyncLog.add("提交失败 (尝试 $attempt): ${e.message}")
                                if (attempt < MAX_RETRY) {
                                    delay(1000L * attempt)
                                }
                            }
                        }
                        if (commitSuccess) {
                            // commit 成功后再激活（pending 状态无法激活）
                            val profile = queryByUUID(uuid)
                            if (profile != null) {
                                setActive(profile)
                                AppLog.d(TAG, " Set active profile: ${profile.uuid}")
                                SyncLog.add("已激活配置: ${profile.name}")
                            }
                            Result.success("订阅已添加")
                        } else {
                            SyncLog.add("错误: 订阅下载失败，已重试 $MAX_RETRY 次")
                            Result.failure(Exception("Failed to commit subscription after $MAX_RETRY attempts"))
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Sync failed: ${e.message}")
                SyncLog.add("同步异常: ${e.message}")
                Result.failure(e)
            }
        }
    }
}
