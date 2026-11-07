/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.radar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rtbishop.look4sat.core.domain.model.CoyoteChannel
import com.rtbishop.look4sat.core.domain.model.CoyoteConnection
import com.rtbishop.look4sat.core.domain.model.CoyoteDevice
import com.rtbishop.look4sat.core.domain.model.CoyoteError
import com.rtbishop.look4sat.core.domain.model.CoyoteSettings
import com.rtbishop.look4sat.core.domain.model.CoyoteState
import com.rtbishop.look4sat.core.presentation.R
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun DgLabPage(
    settings: CoyoteSettings,
    state: CoyoteState,
    onAction: (RadarAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingAction
        pendingAction = null
        val granted = requiredPermissions.all {
            grants[it] == true ||
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            action?.invoke()
        } else {
            onAction(RadarAction.ShowToast(context.getString(R.string.dglab_permission_denied)))
        }
    }
    val withBluetoothPermission: (() -> Unit) -> Unit = { action ->
        val granted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(requiredPermissions)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            DgLabConnectionSection(
                state = state,
                savedAddress = settings.deviceAddress,
                onScan = { withBluetoothPermission { onAction(RadarAction.StartCoyoteScan) } },
                onStopScan = { onAction(RadarAction.StopCoyoteScan) },
                onConnect = { device ->
                    withBluetoothPermission { onAction(RadarAction.ConnectCoyote(device)) }
                },
                onConnectSaved = {
                    withBluetoothPermission { onAction(RadarAction.ConnectSavedCoyote) }
                },
                onDisconnect = { onAction(RadarAction.DisconnectCoyote) }
            )
        }
        item {
            DgLabLiveSection(state = state, onAction = onAction)
        }
        item {
            DgLabSettingsSection(settings = settings, onAction = onAction)
        }
    }
}

@Composable
private fun DgLabConnectionSection(
    state: CoyoteState,
    savedAddress: String,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (CoyoteDevice) -> Unit,
    onConnectSaved: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val statusColor = if (state.connection == CoyoteConnection.CONNECTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            StatusDot(color = statusColor)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (state.connection) {
                        CoyoteConnection.CONNECTED ->
                            stringResource(R.string.dglab_status_connected)
                        CoyoteConnection.CONNECTING ->
                            stringResource(R.string.dglab_status_connecting)
                        else -> stringResource(R.string.dglab_status_disconnected)
                    },
                    fontWeight = FontWeight.Medium
                )
                val detail = buildString {
                    if (state.connection == CoyoteConnection.CONNECTED) {
                        state.protocol?.let { append(it.name) }
                        if (state.deviceName.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(state.deviceName)
                        }
                    }
                }
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            state.battery?.let { battery ->
                Text(
                    text = stringResource(R.string.dglab_battery, battery),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val errorText = dgLabErrorText(state.error)
        if (errorText != null) {
            Text(text = errorText, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                state.connection == CoyoteConnection.CONNECTED -> {
                    OutlinedButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.dglab_disconnect))
                    }
                }

                state.isScanning -> {
                    OutlinedButton(onClick = onStopScan) {
                        Text(stringResource(R.string.dglab_stop_scan))
                    }
                }

                else -> {
                    OutlinedButton(onClick = onScan) {
                        Text(stringResource(R.string.dglab_scan))
                    }
                    if (savedAddress.isNotBlank()) {
                        OutlinedButton(onClick = onConnectSaved) {
                            Text(stringResource(R.string.dglab_connect_saved))
                        }
                    }
                }
            }
        }

        if (state.isScanning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.dglab_scanning),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.scanResults.isNotEmpty()) {
            state.scanResults.forEach { device ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.name, fontSize = 14.sp)
                        Text(
                            text = "${device.address}  ${device.rssi} dBm",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { onConnect(device) }) {
                        Text(stringResource(R.string.dglab_connect))
                    }
                }
            }
        } else if (state.isScanning) {
            Text(
                text = stringResource(R.string.dglab_no_devices_found),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.connection != CoyoteConnection.CONNECTED && savedAddress.isBlank()) {
            Text(
                text = stringResource(R.string.dglab_connect_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Small colour dot used as a connection indicator.
@Composable
private fun StatusDot(color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(9.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun DgLabLiveSection(state: CoyoteState, onAction: (RadarAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(R.string.dglab_live_title))
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Metric(
                label = stringResource(R.string.dglab_deviation),
                value = String.format(Locale.ROOT, "%.1f°", state.deviationDeg)
            )
            Metric(
                label = stringResource(R.string.dglab_intensity),
                value = "${state.intensityPercent}%"
            )
        }
        Text(
            text = stringResource(
                if (state.targetValid) R.string.dglab_target_valid else R.string.dglab_target_invalid
            ),
            fontSize = 12.sp,
            color = if (state.targetValid) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.dglab_arm), fontSize = 15.sp)
                Text(
                    text = stringResource(R.string.dglab_arm_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.armed,
                enabled = state.connection == CoyoteConnection.CONNECTED,
                onCheckedChange = { checked ->
                    onAction(if (checked) RadarAction.ToggleCoyoteArm else RadarAction.DisarmCoyote)
                }
            )
        }
        OutlinedButton(
            onClick = { onAction(RadarAction.PanicCoyote) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.dglab_panic),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun DgLabSettingsSection(settings: CoyoteSettings, onAction: (RadarAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        SectionTitle(stringResource(R.string.dglab_settings_title))
        if (settings.maxStrength > 0) {
            DgLabSlider(
                label = stringResource(R.string.dglab_initial_strength),
                value = settings.initialStrength.toFloat(),
                range = 0f..settings.maxStrength.toFloat(),
                steps = (settings.maxStrength - 1).coerceAtLeast(0),
                onCommit = { onAction(RadarAction.SetCoyoteInitialStrength(it.roundToInt())) },
                format = { "${it.roundToInt()}%" }
            )
        }
        DgLabSlider(
            label = stringResource(R.string.dglab_max_strength),
            value = settings.maxStrength.toFloat(),
            range = 0f..CoyoteSettings.STRENGTH_MAX.toFloat(),
            steps = CoyoteSettings.STRENGTH_MAX - 1,
            onCommit = { onAction(RadarAction.SetCoyoteMaxStrength(it.roundToInt())) },
            format = { "${it.roundToInt()}%" }
        )
        DgLabSlider(
            label = stringResource(R.string.dglab_start_angle),
            value = settings.startAngle,
            range = 0f..178f,
            steps = 177,
            onCommit = { onAction(RadarAction.SetCoyoteStartAngle(it)) },
            format = { "${it.roundToInt()}°" }
        )
        DgLabSlider(
            label = stringResource(R.string.dglab_full_angle),
            value = settings.fullAngle.coerceAtLeast(settings.startAngle + 1f),
            range = (settings.startAngle + 1f).coerceAtMost(179f)..180f,
            steps = (178f - settings.startAngle).roundToInt().coerceAtLeast(0),
            onCommit = { onAction(RadarAction.SetCoyoteFullAngle(it)) },
            format = { "${it.roundToInt()}°" }
        )
        Text(text = stringResource(R.string.dglab_channel), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChannelChip(CoyoteChannel.A, settings.channel, onAction)
            ChannelChip(CoyoteChannel.B, settings.channel, onAction)
            ChannelChip(CoyoteChannel.BOTH, settings.channel, onAction)
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(
                R.string.dglab_curve,
                settings.startAngle.roundToInt(),
                settings.fullAngle.roundToInt(),
                settings.maxStrength,
                settings.initialStrength
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.dglab_pointing_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.dglab_soft_limit_note),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChannelChip(
    channel: CoyoteChannel,
    selected: CoyoteChannel,
    onAction: (RadarAction) -> Unit
) {
    val label = when (channel) {
        CoyoteChannel.A -> stringResource(R.string.dglab_channel_a)
        CoyoteChannel.B -> stringResource(R.string.dglab_channel_b)
        CoyoteChannel.BOTH -> stringResource(R.string.dglab_channel_both)
    }
    FilterChip(
        selected = channel == selected,
        onClick = { onAction(RadarAction.SetCoyoteChannel(channel)) },
        label = { Text(label) }
    )
}

@Composable
private fun DgLabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
    format: (Float) -> String
) {
    var local by remember(value, range.start, range.endInclusive) {
        mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive))
    }
    Column {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = label, fontSize = 14.sp)
            Text(text = format(local), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = range,
            steps = steps
        )
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun dgLabErrorText(error: CoyoteError?): String? = when (error) {
    null -> null
    CoyoteError.BLUETOOTH_UNAVAILABLE -> stringResource(R.string.dglab_error_bt_unavailable)
    CoyoteError.BLUETOOTH_OFF -> stringResource(R.string.dglab_error_bt_off)
    CoyoteError.PERMISSION_DENIED -> stringResource(R.string.dglab_error_permission)
    CoyoteError.SCAN_FAILED -> stringResource(R.string.dglab_error_scan)
    CoyoteError.CONNECT_FAILED -> stringResource(R.string.dglab_error_connect)
    CoyoteError.CONNECTION_LOST -> stringResource(R.string.dglab_error_lost)
    CoyoteError.UNSUPPORTED_DEVICE -> stringResource(R.string.dglab_error_unsupported)
    CoyoteError.GATT_ERROR -> stringResource(R.string.dglab_error_gatt)
    CoyoteError.NOT_CONNECTED -> stringResource(R.string.dglab_error_not_connected)
    CoyoteError.NO_SAVED_DEVICE -> stringResource(R.string.dglab_error_no_saved)
}
