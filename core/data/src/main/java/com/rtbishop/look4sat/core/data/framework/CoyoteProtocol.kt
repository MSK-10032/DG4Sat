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

import java.util.UUID
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * BLE UUIDs from the official DG-LAB Bluetooth protocol repository.
 *
 * V3 uses the standard 16-bit base UUID, V2 uses the 955A... base.
 */
object CoyoteUuids {
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val V3_SERVICE: UUID = UUID.fromString("0000180c-0000-1000-8000-00805f9b34fb")
    val V3_WRITE: UUID = UUID.fromString("0000150a-0000-1000-8000-00805f9b34fb")
    val V3_NOTIFY: UUID = UUID.fromString("0000150b-0000-1000-8000-00805f9b34fb")
    val V3_BATTERY_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val V3_BATTERY: UUID = UUID.fromString("00001500-0000-1000-8000-00805f9b34fb")

    val V2_SERVICE: UUID = UUID.fromString("955a180b-0fe2-f5aa-a094-84b8d4f3e8ad")
    val V2_POWER: UUID = UUID.fromString("955a1504-0fe2-f5aa-a094-84b8d4f3e8ad")
    val V2_WAVE_B: UUID = UUID.fromString("955a1505-0fe2-f5aa-a094-84b8d4f3e8ad")
    val V2_WAVE_A: UUID = UUID.fromString("955a1506-0fe2-f5aa-a094-84b8d4f3e8ad")
    val V2_BATTERY_SERVICE: UUID = UUID.fromString("955a180a-0fe2-f5aa-a094-84b8d4f3e8ad")
    val V2_BATTERY: UUID = UUID.fromString("955a1500-0fe2-f5aa-a094-84b8d4f3e8ad")
}

/** Coyote V3 (name "47L121000"), 20-byte B0 frames every 100 ms. */
object CoyoteV3Protocol {

    const val NAME = "47L121000"
    const val DEFAULT_PULSE_FREQUENCY_MS = 100
    const val DEFAULT_PULSE_INTENSITY = 100
    /** Out-of-range value: the host discards all four pulse pairs of that channel. */
    const val INVALID_PULSE_INTENSITY = 101
    const val DEFAULT_BALANCE = 100
    const val MAX_STRENGTH = 200

    const val MODE_KEEP = 0
    const val MODE_INCREASE = 1
    const val MODE_DECREASE = 2
    const val MODE_ABSOLUTE = 3

    data class B1(val sequence: Int, val strengthA: Int, val strengthB: Int)

    /**
     * Builds a B0 frame.
     *
     * @param modeA/modeB 0 = keep, 1 = increase, 2 = decrease, 3 = absolute.
     * @param frequenciesA/B transmitted pulse frequency values (10-240).
     * @param intensitiesA/B pulse intensities (0-100); use [INVALID_PULSE_INTENSITY]
     * to suppress a channel.
     */
    fun b0(
        sequence: Int,
        modeA: Int,
        modeB: Int,
        strengthA: Int,
        strengthB: Int,
        frequenciesA: IntArray,
        intensitiesA: IntArray,
        frequenciesB: IntArray,
        intensitiesB: IntArray
    ): ByteArray {
        require(frequenciesA.size == 4 && intensitiesA.size == 4)
        require(frequenciesB.size == 4 && intensitiesB.size == 4)
        val data = ByteArray(20)
        data[0] = 0xB0.toByte()
        data[1] = (((sequence and 0x0F) shl 4) or
            ((modeA and 0x03) shl 2) or (modeB and 0x03)).toByte()
        data[2] = strengthA.coerceIn(0, MAX_STRENGTH).toByte()
        data[3] = strengthB.coerceIn(0, MAX_STRENGTH).toByte()
        for (index in 0 until 4) {
            data[4 + index] = frequenciesA[index].coerceIn(0, 240).toByte()
            data[8 + index] = intensitiesA[index].coerceIn(0, INVALID_PULSE_INTENSITY).toByte()
            data[12 + index] = frequenciesB[index].coerceIn(0, 240).toByte()
            data[16 + index] = intensitiesB[index].coerceIn(0, INVALID_PULSE_INTENSITY).toByte()
        }
        return data
    }

    /** BF frame: channel soft limits, frequency balance, pulse- intensity balance. */
    fun bf(
        limitA: Int,
        limitB: Int,
        frequencyBalanceA: Int = DEFAULT_BALANCE,
        frequencyBalanceB: Int = DEFAULT_BALANCE,
        intensityBalanceA: Int = DEFAULT_BALANCE,
        intensityBalanceB: Int = DEFAULT_BALANCE
    ): ByteArray = byteArrayOf(
        0xBF.toByte(),
        limitA.coerceIn(0, MAX_STRENGTH).toByte(),
        limitB.coerceIn(0, MAX_STRENGTH).toByte(),
        frequencyBalanceA.coerceIn(0, 255).toByte(),
        frequencyBalanceB.coerceIn(0, 255).toByte(),
        intensityBalanceA.coerceIn(0, 255).toByte(),
        intensityBalanceB.coerceIn(0, 255).toByte()
    )

    fun parseB1(value: ByteArray): B1? {
        if (value.size < 4 || value[0].toInt() and 0xFF != 0xB1) return null
        return B1(
            sequence = value[1].toInt() and 0xFF,
            strengthA = value[2].toInt() and 0xFF,
            strengthB = value[3].toInt() and 0xFF
        )
    }

    /**
     * Converts a pulse frequency in milliseconds to the transmitted V3 value.
     * Official formula: 10-100 unchanged, 101-600 compressed by 5, 601-1000 by 10.
     */
    fun pulseFrequencyValue(milliseconds: Int): Int = when (milliseconds) {
        in 10..100 -> milliseconds
        in 101..600 -> (milliseconds - 100) / 5 + 100
        in 601..1000 -> (milliseconds - 600) / 10 + 200
        else -> 10
    }.coerceIn(10, 240)
}

/** Coyote V2 (name "D-LAB ESTIM01"), 3-byte little-endian characteristics. */
object CoyoteV2Protocol {

    const val NAME = "D-LAB ESTIM01"
    const val MAX_STRENGTH = 2047
    const val DEFAULT_PULSE_FREQUENCY_MS = 100
    const val DEFAULT_PULSE_WIDTH = 20

    /** PWM_AB2: bits 21-11 channel A, bits 10-0 channel B. */
    fun strength(strengthA: Int, strengthB: Int): ByteArray {
        val value = ((strengthA.coerceIn(0, MAX_STRENGTH) and 0x7FF) shl 11) or
            (strengthB.coerceIn(0, MAX_STRENGTH) and 0x7FF)
        return littleEndian3(value)
    }

    /** PWM_A34 / PWM_B34: bits 19-15 Z, bits 14-5 Y, bits 4-0 X. */
    fun wave(x: Int, y: Int, z: Int): ByteArray {
        val value = ((z.coerceIn(0, 0x1F) and 0x1F) shl 15) or
            ((y.coerceIn(0, 0x3FF) and 0x3FF) shl 5) or
            (x.coerceIn(0, 0x1F) and 0x1F)
        return littleEndian3(value)
    }

    /** Official X/Y recommendation for a pulse frequency in milliseconds. */
    fun waveParams(frequencyMs: Int): Triple<Int, Int, Int> {
        val frequency = frequencyMs.coerceIn(10, 1000)
        val x = ((frequency / 1000.0).pow(0.5) * 15).roundToInt().coerceIn(0, 31)
        val y = (frequency - x).coerceIn(0, 1023)
        return Triple(x, y, DEFAULT_PULSE_WIDTH)
    }

    fun wave(frequencyMs: Int): ByteArray {
        val (x, y, z) = waveParams(frequencyMs)
        return wave(x, y, z)
    }

    fun parseStrength(value: ByteArray): Pair<Int, Int>? {
        if (value.size < 3) return null
        val raw = (value[0].toInt() and 0xFF) or
            ((value[1].toInt() and 0xFF) shl 8) or
            ((value[2].toInt() and 0xFF) shl 16)
        return ((raw shr 11) and 0x7FF) to (raw and 0x7FF)
    }

    private fun littleEndian3(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte()
    )
}
