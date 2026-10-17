package com.gist.app.qs

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gist.app.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quick Settings tile ("Gist Bubble") that toggles showing the floating Gist bubble in any app,
 * even outside the default reading allowlist (D11, §3.2).
 */
@AndroidEntryPoint
class BubbleTileService : TileService() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val settings = settingsRepository.settingsFlow.first()
            updateTileState(settings.qsTileForceShowEverywhere)
        }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val current = settingsRepository.settingsFlow.first().qsTileForceShowEverywhere
            val next = !current
            settingsRepository.setQsTileForceShowEverywhere(next)
            updateTileState(next)
        }
    }

    private fun updateTileState(forceShow: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (forceShow) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (forceShow) "Shown everywhere" else "Allowlist only"
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
