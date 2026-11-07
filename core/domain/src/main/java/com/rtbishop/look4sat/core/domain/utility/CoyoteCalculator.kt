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
package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.CoyoteProtocol
import com.rtbishop.look4sat.core.domain.model.CoyoteSettings
import com.rtbishop.look4sat.core.domain.model.sanitized
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure math shared by the radar screen and the Coyote output engine.
 *
 * The pointing model matches the crosshair Look4Sat already draws on the radar:
 * the elevation of the phone is the negative of the rotation-vector pitch, so
 * the top edge of the phone is what points at the sky. This keeps the shock
 * trigger and the on-screen aim marker consistent.
 */
object CoyoteCalculator {

    /** Great-circle angle between the phone aim and the satellite, in degrees. */
    fun deviationDeg(
        phoneAzimuthDeg: Double,
        phonePitchDeg: Double,
        satAzimuthDeg: Double,
        satElevationDeg: Double
    ): Double {
        val phoneElevationDeg = (-phonePitchDeg).coerceIn(-90.0, 90.0)
        val phoneAzimuthRad = Math.toRadians(phoneAzimuthDeg)
        val phoneElevationRad = Math.toRadians(phoneElevationDeg)
        val satAzimuthRad = Math.toRadians(satAzimuthDeg)
        val satElevationRad = Math.toRadians(satElevationDeg)
        val cosPhoneElevation = cos(phoneElevationRad)
        val cosSatElevation = cos(satElevationRad)
        val dot = cosPhoneElevation * cosSatElevation *
            cos(phoneAzimuthRad - satAzimuthRad) +
            sin(phoneElevationRad) * sin(satElevationRad)
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    /**
     * Strength percentage for a deviation. Zero at or below [CoyoteSettings.startAngle],
     * [CoyoteSettings.initialStrength] just above it, and [CoyoteSettings.maxStrength]
     * at or beyond [CoyoteSettings.fullAngle]. The ramp is linear in percent.
     */
    fun intensityPercent(deviationDeg: Double, settings: CoyoteSettings): Int {
        val safe = settings.sanitized()
        if (deviationDeg <= safe.startAngle || safe.maxStrength <= 0) return 0
        if (safe.fullAngle <= safe.startAngle) return safe.maxStrength
        val span = safe.fullAngle - safe.startAngle
        val fraction = ((deviationDeg - safe.startAngle) / span).coerceIn(0.0, 1.0)
        val strength = safe.initialStrength + (safe.maxStrength - safe.initialStrength) * fraction
        return strength.roundToInt().coerceIn(0, safe.maxStrength)
    }

    /** Converts the app percentage into the intensity range used by each protocol. */
    fun rawStrength(percent: Int, protocol: CoyoteProtocol): Int {
        val safePercent = percent.coerceIn(0, 100)
        return when (protocol) {
            CoyoteProtocol.V3 -> safePercent * 2
            CoyoteProtocol.V2 -> safePercent * 14
        }
    }

    /** Highest raw value the app will ever write (device display 0-200 equivalent). */
    fun maxRawStrength(protocol: CoyoteProtocol): Int {
        return when (protocol) {
            CoyoteProtocol.V3 -> 200
            CoyoteProtocol.V2 -> 1400
        }
    }
}
