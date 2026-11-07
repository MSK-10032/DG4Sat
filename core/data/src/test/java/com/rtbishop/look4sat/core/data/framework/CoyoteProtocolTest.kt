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
package com.rtbishop.look4sat.core.data.framework

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CoyoteProtocolTest {

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun v3B0_matchesOfficialPulseExample() {
        // Example No.1 from the official V3 protocol documentation.
        val frame = CoyoteV3Protocol.b0(
            sequence = 0,
            modeA = CoyoteV3Protocol.MODE_KEEP,
            modeB = CoyoteV3Protocol.MODE_KEEP,
            strengthA = 0,
            strengthB = 0,
            frequenciesA = intArrayOf(10, 10, 10, 10),
            intensitiesA = intArrayOf(0, 10, 20, 30),
            frequenciesB = intArrayOf(0, 0, 0, 0),
            intensitiesB = intArrayOf(0, 0, 0, 101)
        )
        assertEquals("b00000000a0a0a0a000a141e0000000000000065", frame.toHex())
    }

    @Test
    fun v3B0_encodesSequenceAndModes() {
        val frame = CoyoteV3Protocol.b0(
            sequence = 1,
            modeA = CoyoteV3Protocol.MODE_ABSOLUTE,
            modeB = CoyoteV3Protocol.MODE_KEEP,
            strengthA = 42,
            strengthB = 0,
            frequenciesA = intArrayOf(100, 100, 100, 100),
            intensitiesA = intArrayOf(100, 100, 100, 100),
            frequenciesB = intArrayOf(0, 0, 0, 0),
            intensitiesB = intArrayOf(101, 101, 101, 101)
        )
        assertEquals(20, frame.size)
        assertEquals(0xB0, frame[0].toInt() and 0xFF)
        assertEquals(0x1C, frame[1].toInt() and 0xFF)
        assertEquals(42, frame[2].toInt() and 0xFF)
    }

    @Test
    fun v3Bf_matchesOfficialLayout() {
        val frame = CoyoteV3Protocol.bf(limitA = 150, limitB = 30)
        assertArrayEquals(
            byteArrayOf(0xBF.toByte(), 150.toByte(), 30.toByte(), 100, 100, 100, 100),
            frame
        )
    }

    @Test
    fun v3PulseFrequencyConversion_matchesDocumentation() {
        val inputs = intArrayOf(100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
        val expected = intArrayOf(100, 120, 140, 160, 180, 200, 210, 220, 230, 240)
        inputs.forEachIndexed { index, input ->
            assertEquals(
                "input $input",
                expected[index],
                CoyoteV3Protocol.pulseFrequencyValue(input)
            )
        }
        assertEquals(102, CoyoteV3Protocol.pulseFrequencyValue(110))
        assertEquals(110, CoyoteV3Protocol.pulseFrequencyValue(150))
        assertEquals(205, CoyoteV3Protocol.pulseFrequencyValue(650))
        assertEquals(208, CoyoteV3Protocol.pulseFrequencyValue(680))
        assertEquals(215, CoyoteV3Protocol.pulseFrequencyValue(750))
        assertEquals(10, CoyoteV3Protocol.pulseFrequencyValue(0))
        assertEquals(10, CoyoteV3Protocol.pulseFrequencyValue(5000))
    }

    @Test
    fun v3ParseB1_readsIntensities() {
        val b1 = CoyoteV3Protocol.parseB1(byteArrayOf(0xB1.toByte(), 3, 45, 90))
        assertNotNull(b1)
        assertEquals(3, b1?.sequence)
        assertEquals(45, b1?.strengthA)
        assertEquals(90, b1?.strengthB)
        assertNull(CoyoteV3Protocol.parseB1(byteArrayOf(0xB0.toByte(), 0, 0, 0)))
        assertNull(CoyoteV3Protocol.parseB1(byteArrayOf(0xB1.toByte(), 0)))
    }

    @Test
    fun v2Pwm_matchesOfficialExampleBytes() {
        assertArrayEquals(
            byteArrayOf(0x21, 0x01, 0x00),
            CoyoteV2Protocol.wave(x = 1, y = 9, z = 0)
        )
        assertArrayEquals(
            byteArrayOf(0x21, 0x01, 0x0A),
            CoyoteV2Protocol.wave(x = 1, y = 9, z = 20)
        )
    }

    @Test
    fun v2WaveParams_useOfficialFormula() {
        assertEquals(Triple(5, 95, 20), CoyoteV2Protocol.waveParams(100))
        assertEquals(Triple(2, 8, 20), CoyoteV2Protocol.waveParams(10))
        assertArrayEquals(
            byteArrayOf(0xE5.toByte(), 0x0B, 0x0A),
            CoyoteV2Protocol.wave(100)
        )
    }

    @Test
    fun v2Strength_roundTripsBothChannels() {
        val encoded = CoyoteV2Protocol.strength(strengthA = 1400, strengthB = 321)
        assertEquals(3, encoded.size)
        val decoded = CoyoteV2Protocol.parseStrength(encoded)
        assertNotNull(decoded)
        assertEquals(1400, decoded?.first)
        assertEquals(321, decoded?.second)
    }

    @Test
    fun v2Strength_clampsToElevenBits() {
        val decoded = CoyoteV2Protocol.parseStrength(
            CoyoteV2Protocol.strength(strengthA = 5000, strengthB = -3)
        )
        assertNotNull(decoded)
        assertEquals(2047, decoded?.first)
        assertEquals(0, decoded?.second)
    }
}
