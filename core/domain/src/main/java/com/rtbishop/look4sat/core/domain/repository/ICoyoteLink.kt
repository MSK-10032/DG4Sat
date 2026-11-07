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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.CoyoteDevice
import com.rtbishop.look4sat.core.domain.model.CoyoteState
import kotlinx.coroutines.flow.StateFlow

/**
 * Connection to a DG-LAB Coyote host over BLE, plus the pointing-driven output engine.
 *
 * The engine only emits pulses while it is [arm]ed and the last pointing update was
 * valid. Disarming, losing the connection or leaving the radar screen drives the
 * channel intensity back to zero.
 */
interface ICoyoteLink {
    val state: StateFlow<CoyoteState>
    fun startScan()
    fun stopScan()
    fun connect(device: CoyoteDevice)
    fun connectSaved()
    fun disconnect()
    fun arm()
    fun disarm()
    fun panic()
    fun setPointing(deviationDeg: Double, targetValid: Boolean)
}
