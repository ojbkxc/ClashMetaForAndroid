package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.ConfigOptimizer
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null
        var consecutiveFailures = 0

        reload.trySend(Unit)

        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")

                val configDir = service.importedDir.resolve(active.uuid.toString())
                ConfigOptimizer.optimize(configDir)

                Clash.load(configDir).await()

                val remove = SelectionDao().querySelections(active.uuid)
                    .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                    .map { it.proxy }

                SelectionDao().removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name

                service.sendProfileLoaded(current)

                consecutiveFailures = 0

                Log.d("Profile ${active.name} loaded")
            } catch (e: Exception) {
                Log.e("Profile load failed: ${e.message}", e)

                consecutiveFailures++

                // If initial load fails, retry with backoff instead of crashing
                if (loaded == null) {
                    val backoff = minOf(consecutiveFailures * 5_000L, 60_000L)
                    Log.w("ConfigurationModule: retrying initial load in ${backoff}ms (attempt $consecutiveFailures)")
                    delay(backoff)
                    reload.trySend(Unit)
                    continue
                }

                // Re-load of already running profile failed: keep old config and retry after delay
                val backoff = minOf(consecutiveFailures * 10_000L, 120_000L)
                Log.w("ConfigurationModule: profile reload failed, retrying in ${backoff}ms (attempt $consecutiveFailures)")
                delay(backoff)
                reload.trySend(Unit)
            }
        }
    }
}