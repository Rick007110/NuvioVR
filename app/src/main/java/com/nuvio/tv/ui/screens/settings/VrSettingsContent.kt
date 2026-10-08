@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.vr.VrSession

@Composable
internal fun VrSettingsContent(
    initialFocusRequester: FocusRequester? = null
) {
    val passthroughEnabled by VrSession.passthroughEnabled.collectAsState()
    val dimDuringPlayback by VrSession.dimDuringPlayback.collectAsState()
    val cinemaHall by VrSession.cinemaHall.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.settings_vr),
            subtitle = stringResource(R.string.vr_settings_subtitle)
        )
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.vr_settings_environment)
        ) {
            SettingsToggleRow(
                title = stringResource(R.string.vr_passthrough_title),
                subtitle = stringResource(R.string.vr_passthrough_subtitle),
                checked = passthroughEnabled,
                onToggle = VrSession::togglePassthrough,
                modifier = if (initialFocusRequester != null) {
                    Modifier.focusRequester(initialFocusRequester)
                } else {
                    Modifier
                }
            )
            SettingsToggleRow(
                title = stringResource(R.string.vr_cinema_hall_title),
                subtitle = stringResource(R.string.vr_cinema_hall_subtitle),
                checked = cinemaHall,
                onToggle = { VrSession.setCinemaHall(!cinemaHall) },
                enabled = !passthroughEnabled
            )
            SettingsToggleRow(
                title = stringResource(R.string.vr_dim_playback_title),
                subtitle = stringResource(R.string.vr_dim_playback_subtitle),
                checked = dimDuringPlayback,
                onToggle = { VrSession.setDimDuringPlayback(!dimDuringPlayback) },
                enabled = !passthroughEnabled
            )
        }
        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth(),
            title = stringResource(R.string.vr_settings_screen)
        ) {
            SettingsActionRow(
                title = stringResource(R.string.vr_recenter_title),
                subtitle = stringResource(R.string.vr_recenter_subtitle),
                onClick = VrSession::requestRecenter
            )
        }
    }
}
