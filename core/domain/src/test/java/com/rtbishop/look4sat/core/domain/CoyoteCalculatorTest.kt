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
package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.model.CoyoteChannel
import com.rtbishop.look4sat.core.domain.model.CoyoteProtocol
import com.rtbishop.look4sat.core.domain.model.CoyoteSettings
import com.rtbishop.look4sat.core.domain.model.sanitized
import com.rtbishop.look4sat.core.domain.utility.CoyoteCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoyoteCalculatorTest {

    private val settings = CoyoteSettings(
        initialStrength = 5,
        maxStrength = 30,
        startAngle = 10f,
        fullAngle = 60f,
        channel = CoyoteChannel.A
    )

    @Test
    fun deviation_sameDirection_isZero() {
        assertEquals(0.0, CoyoteCalculator.deviationDeg(0.0, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(0.0, CoyoteCalculator.deviationDeg(123.0, -45.0, 123.0, 45.0), 1e-9)
    }

    @Test
    fun deviation_azimuthQuadrant_isNinety() {
        assertEquals(90.0, CoyoteCalculator.deviationDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
    }

    @Test
    fun deviation_elevationQuadrant_isNinety() {
        assertEquals(90.0, CoyoteCalculator.deviationDeg(0.0, 0.0, 0.0, 90.0), 1e-9)
    }

    @Test
    fun deviation_oppositeDirections_isOneEighty() {
        assertEquals(180.0, CoyoteCalculator.deviationDeg(0.0, 0.0, 180.0, 0.0), 1e-9)
    }

    @Test
    fun deviation_wrapsAcrossNorth() {
        assertEquals(20.0, CoyoteCalculator.deviationDeg(350.0, 0.0, 10.0, 0.0), 1e-9)
    }

    @Test
    fun deviation_usesTopEdgeOfPhone() {
        // Pitch -45 means the top edge is 45 degrees above the horizon.
        assertEquals(0.0, CoyoteCalculator.deviationDeg(0.0, -45.0, 0.0, 45.0), 1e-9)
        // Pitch +45 points below the horizon, 90 degrees away from a 45 degree target.
        assertEquals(90.0, CoyoteCalculator.deviationDeg(0.0, 45.0, 0.0, 45.0), 1e-9)
    }

    @Test
    fun intensity_belowOrAtStartAngle_isZero() {
        assertEquals(0, CoyoteCalculator.intensityPercent(0.0, settings))
        assertEquals(0, CoyoteCalculator.intensityPercent(10.0, settings))
    }

    @Test
    fun intensity_justAboveStartAngle_isInitial() {
        assertEquals(5, CoyoteCalculator.intensityPercent(10.001, settings))
    }

    @Test
    fun intensity_rampsLinearlyByPercent() {
        assertEquals(18, CoyoteCalculator.intensityPercent(35.0, settings))
        assertEquals(6, CoyoteCalculator.intensityPercent(11.0, settings))
    }

    @Test
    fun intensity_atOrBeyondFullAngle_isMax() {
        assertEquals(30, CoyoteCalculator.intensityPercent(60.0, settings))
        assertEquals(30, CoyoteCalculator.intensityPercent(180.0, settings))
    }

    @Test
    fun intensity_equalInitialAndMax_isConstant() {
        val flat = settings.copy(initialStrength = 20, maxStrength = 20)
        assertEquals(20, CoyoteCalculator.intensityPercent(30.0, flat))
    }

    @Test
    fun intensity_zeroMax_isZero() {
        val off = settings.copy(initialStrength = 0, maxStrength = 0)
        assertEquals(0, CoyoteCalculator.intensityPercent(180.0, off))
    }

    @Test
    fun rawStrength_mapsPercentToProtocolRange() {
        assertEquals(0, CoyoteCalculator.rawStrength(0, CoyoteProtocol.V3))
        assertEquals(100, CoyoteCalculator.rawStrength(50, CoyoteProtocol.V3))
        assertEquals(200, CoyoteCalculator.rawStrength(100, CoyoteProtocol.V3))
        assertEquals(700, CoyoteCalculator.rawStrength(50, CoyoteProtocol.V2))
        assertEquals(1400, CoyoteCalculator.rawStrength(100, CoyoteProtocol.V2))
        assertEquals(200, CoyoteCalculator.maxRawStrength(CoyoteProtocol.V3))
        assertEquals(1400, CoyoteCalculator.maxRawStrength(CoyoteProtocol.V2))
    }

    @Test
    fun sanitize_repairsInvertedRanges() {
        val broken = CoyoteSettings(
            initialStrength = 90,
            maxStrength = 20,
            startAngle = 170f,
            fullAngle = 5f,
            channel = CoyoteChannel.BOTH
        ).sanitized()
        assertEquals(20, broken.maxStrength)
        assertEquals(20, broken.initialStrength)
        assertTrue(broken.fullAngle > broken.startAngle)
        assertTrue(broken.startAngle <= 178f)
        assertTrue(broken.fullAngle <= 180f)
    }
}
