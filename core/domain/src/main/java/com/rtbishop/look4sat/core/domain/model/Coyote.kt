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
package com.rtbishop.look4sat.core.domain.model

/** Coyote stimulator protocol generation. */
enum class CoyoteProtocol { V2, V3 }

/** Output channel of the Coyote host. */
enum class CoyoteChannel {
    A, B, BOTH;

    val usesA: Boolean get() = this == A || this == BOTH
    val usesB: Boolean get() = this == B || this == BOTH
}

/** A Coyote host found during a BLE scan. */
data class CoyoteDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val protocol: CoyoteProtocol
)

/**
 * User settings for the satellite pointing link.
 *
 * Strength values are percentages of the intensity range the official app exposes
 * (0-200 internally): [initialStrength] is used as soon as the deviation exceeds
 * [startAngle], and the strength rises linearly to [maxStrength] at [fullAngle].
 * Intensities are never allowed to exceed [maxStrength].
 */
data class CoyoteSettings(
    val deviceAddress: String = "",
    val deviceName: String = "",
    val initialStrength: Int = 5,
    val maxStrength: Int = 30,
    val startAngle: Float = 10f,
    val fullAngle: Float = 60f,
    val channel: CoyoteChannel = CoyoteChannel.A
) {
    companion object {
        const val STRENGTH_MIN = 0
        const val STRENGTH_MAX = 100
        const val ANGLE_MIN = 0f
        const val ANGLE_MAX = 180f
    }
}

/** Repairs out-of-range values, including combinations such as initial > max. */
fun CoyoteSettings.sanitized(): CoyoteSettings {
    val safeMax = maxStrength.coerceIn(CoyoteSettings.STRENGTH_MIN, CoyoteSettings.STRENGTH_MAX)
    val safeInitial = initialStrength.coerceIn(CoyoteSettings.STRENGTH_MIN, safeMax)
    // Keep at least a one-degree ramp; 180 degrees of deviation is the physical maximum.
    val safeStart = startAngle.coerceIn(CoyoteSettings.ANGLE_MIN, CoyoteSettings.ANGLE_MAX - 2f)
    val safeFull = fullAngle.coerceIn(safeStart + 1f, CoyoteSettings.ANGLE_MAX)
    return copy(
        initialStrength = safeInitial,
        maxStrength = safeMax,
        startAngle = safeStart,
        fullAngle = safeFull
    )
}

enum class CoyoteConnection { IDLE, CONNECTING, CONNECTED }

/** Localised by the UI; the data layer never returns raw error text. */
enum class CoyoteError {
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_OFF,
    PERMISSION_DENIED,
    SCAN_FAILED,
    CONNECT_FAILED,
    CONNECTION_LOST,
    UNSUPPORTED_DEVICE,
    GATT_ERROR,
    NOT_CONNECTED,
    NO_SAVED_DEVICE
}

/** Observable state of the Coyote link. */
data class CoyoteState(
    val connection: CoyoteConnection = CoyoteConnection.IDLE,
    val protocol: CoyoteProtocol? = null,
    val deviceName: String = "",
    val deviceAddress: String = "",
    val battery: Int? = null,
    val isScanning: Boolean = false,
    val scanResults: List<CoyoteDevice> = emptyList(),
    val armed: Boolean = false,
    val deviationDeg: Double = 180.0,
    val targetValid: Boolean = false,
    val intensityPercent: Int = 0,
    val error: CoyoteError? = null
)
