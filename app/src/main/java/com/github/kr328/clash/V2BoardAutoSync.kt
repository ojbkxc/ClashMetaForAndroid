package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.v2board.V2BoardSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object V2BoardAutoSync {
    private const val PROFILE_NAME = "\u84dd\u661f\u7f51\u7edc"
    private const val TAG = "V2BoardAutoSync"

    suspend fun sync(context: Context, subscribeUrl: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val sync = V2BoardSync.getInstance(context)
                val intervalMinutes = sync.config.syncInterval
                val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes)
                val profileName = PROFILE_NAME

                withProfile {
                    val existing = queryAll().find {
                        it.type == Profile.Type.Url &&
                                it.source.contains("/api/v1/client/subscribe")
                    }

                    if (existing != null) {
                        patch(existing.uuid, profileName, subscribeUrl, intervalMs)
                        update(existing.uuid)
                        Log.d("$TAG: Updated existing profile: ${existing.uuid}")
                        Result.success("Subscription updated")
                    } else {
                        val uuid = create(Profile.Type.Url, profileName, subscribeUrl)
                        patch(uuid, profileName, subscribeUrl, intervalMs)
                        commit(uuid)
                        setActive(queryByUUID(uuid)!!)
                        Log.d("$TAG: Created new profile: $uuid")
                        Result.success("Subscription added")
                    }
                }
            } catch (e: Exception) {
                Log.w("$TAG sync failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    suspend fun checkAndSync(context: Context) {
        withContext(Dispatchers.IO) {
            val sync = V2BoardSync.getInstance(context)
            if (!sync.session.isLoggedIn) return@withContext

            sync.ensureWorkingDomain()

            val result = sync.fetchSubscribeUrl()
            if (result.isSuccess) {
                sync(context, result.getOrNull()!!)
            }
        }
    }
}
