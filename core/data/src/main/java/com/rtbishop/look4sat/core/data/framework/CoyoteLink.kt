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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.rtbishop.look4sat.core.domain.model.CoyoteConnection
import com.rtbishop.look4sat.core.domain.model.CoyoteDevice
import com.rtbishop.look4sat.core.domain.model.CoyoteError
import com.rtbishop.look4sat.core.domain.model.CoyoteProtocol
import com.rtbishop.look4sat.core.domain.model.CoyoteSettings
import com.rtbishop.look4sat.core.domain.model.CoyoteState
import com.rtbishop.look4sat.core.domain.model.sanitized
import com.rtbishop.look4sat.core.domain.repository.ICoyoteLink
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.utility.CoyoteCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "CoyoteLink"
private const val SCAN_DURATION_MS = 12_000L
private const val CONNECT_TIMEOUT_MS = 15_000L
private const val GATT_OP_TIMEOUT_MS = 2_000L
private const val TICK_MS = 100L
private const val MAX_POWER_READ_ATTEMPTS = 3

/**
 * BLE link to a DG-LAB Coyote V2/V3 host plus the pointing output engine.
 *
 * Safety rules enforced here:
 * - pulses are only written while armed, connected and the satellite is above the horizon;
 * - any intensity change is an absolute write, capped by the user's maximum;
 * - V3 soft limits are re-sent on every connection, so even a bug cannot exceed the
 *   configured maximum;
 * - disarming/leaving the screen writes zero, and pulse data expires within ~100 ms
 *   even if the Android process dies.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CoyoteLink(
    context: Context,
    private val appScope: CoroutineScope,
    private val settingsRepo: ISettingsRepo
) : ICoyoteLink {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Volatile
    private var settings: CoyoteSettings = settingsRepo.coyoteSettings.value.sanitized()

    private val _state = MutableStateFlow(
        CoyoteState(deviceName = settings.deviceName, deviceAddress = settings.deviceAddress)
    )
    override val state: StateFlow<CoyoteState> = _state.asStateFlow()

    @Volatile private var armed = false
    @Volatile private var deviationDeg = 180.0
    @Volatile private var targetValid = false
    @Volatile private var targetPercent = 0

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var protocol: CoyoteProtocol? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var batteryChar: BluetoothGattCharacteristic? = null
    @Volatile private var v2PowerChar: BluetoothGattCharacteristic? = null
    @Volatile private var v2WaveAChar: BluetoothGattCharacteristic? = null
    @Volatile private var v2WaveBChar: BluetoothGattCharacteristic? = null

    @Volatile private var v2StrengthA = 0
    @Volatile private var v2StrengthB = 0
    @Volatile private var v2StrengthLoaded = false
    @Volatile private var powerReadAttempts = 0

    @Volatile private var outputActive = false
    @Volatile private var pendingZero = false
    @Volatile private var pendingSoftLimit = false
    @Volatile private var pendingBatteryRead = false
    @Volatile private var pendingPowerRead = false

    @Volatile private var gattBusy = false
    @Volatile private var busySince = 0L
    @Volatile private var descriptorReadyAction: (() -> Unit)? = null
    @Volatile private var v2ForceResponseWrite = false

    private var outputJob: Job? = null
    private var descriptorFallbackJob: Job? = null
    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var scanCallback: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private val scanResults = LinkedHashMap<String, CoyoteDevice>()

    init {
        appScope.launch {
            settingsRepo.coyoteSettings.collect { newSettings ->
                val safe = newSettings.sanitized()
                settings = safe
                if (protocol == CoyoteProtocol.V3) pendingSoftLimit = true
                recomputeTarget()
            }
        }
    }

    //region # Public API

    override fun setPointing(deviationDeg: Double, targetValid: Boolean) {
        this.deviationDeg = deviationDeg
        this.targetValid = targetValid
        val nextTarget = if (armed && targetValid) {
            CoyoteCalculator.intensityPercent(deviationDeg, settings)
        } else {
            0
        }
        targetPercent = nextTarget
        _state.update {
            it.copy(
                deviationDeg = deviationDeg,
                targetValid = targetValid,
                intensityPercent = nextTarget
            )
        }
    }

    override fun arm() {
        if (_state.value.connection != CoyoteConnection.CONNECTED) {
            setError(CoyoteError.NOT_CONNECTED)
            return
        }
        armed = true
        pendingZero = true
        recomputeTarget()
        _state.update { it.copy(armed = true, error = null, intensityPercent = targetPercent) }
    }

    override fun disarm() {
        armed = false
        targetPercent = 0
        pendingZero = true
        _state.update { it.copy(armed = false, intensityPercent = 0) }
    }

    override fun panic() = disarm()

    override fun disconnect() {
        stopScanInternal()
        val targetGatt = gatt
        val wasConnected = _state.value.connection == CoyoteConnection.CONNECTED
        armed = false
        targetPercent = 0
        pendingZero = true
        _state.update {
            it.copy(
                connection = CoyoteConnection.IDLE,
                protocol = null,
                armed = false,
                intensityPercent = 0,
                battery = null,
                error = null,
                isScanning = false
            )
        }
        if (wasConnected && targetGatt != null) {
            // The output loop is the only thread allowed to touch GATT writes, so let it
            // push the pending zero frame first and only then tear the link down.
            appScope.launch {
                delay(400)
                if (gatt === targetGatt) closeCurrentConnection()
            }
        } else {
            closeCurrentConnection()
        }
    }

    override fun startScan() {
        if (_state.value.isScanning) return
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            setError(CoyoteError.BLUETOOTH_UNAVAILABLE)
            return
        }
        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            setError(CoyoteError.PERMISSION_DENIED)
            return
        }
        if (!enabled) {
            setError(CoyoteError.BLUETOOTH_OFF)
            return
        }
        val leScanner = try {
            adapter.bluetoothLeScanner
        } catch (_: SecurityException) {
            setError(CoyoteError.PERMISSION_DENIED)
            return
        }
        if (leScanner == null) {
            setError(CoyoteError.BLUETOOTH_UNAVAILABLE)
            return
        }
        stopScanInternal()
        scanResults.clear()
        scanner = leScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                stopScanInternal()
                _state.update {
                    it.copy(isScanning = false, error = CoyoteError.SCAN_FAILED)
                }
            }
        }
        scanCallback = callback
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            leScanner.startScan(null, scanSettings, callback)
        } catch (_: SecurityException) {
            stopScanInternal()
            _state.update { it.copy(isScanning = false, error = CoyoteError.PERMISSION_DENIED) }
            return
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start scan", exception)
            stopScanInternal()
            _state.update { it.copy(isScanning = false, error = CoyoteError.SCAN_FAILED) }
            return
        }
        _state.update {
            it.copy(isScanning = true, scanResults = emptyList(), error = null)
        }
        scanJob = appScope.launch {
            delay(SCAN_DURATION_MS)
            stopScanInternal()
            _state.update { it.copy(isScanning = false) }
        }
    }

    override fun stopScan() {
        stopScanInternal()
        _state.update { it.copy(isScanning = false) }
    }

    override fun connect(device: CoyoteDevice) {
        stopScanInternal()
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            setError(CoyoteError.BLUETOOTH_UNAVAILABLE)
            return
        }
        val enabled = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            setError(CoyoteError.PERMISSION_DENIED)
            return
        }
        if (!enabled) {
            setError(CoyoteError.BLUETOOTH_OFF)
            return
        }
        closeCurrentConnection()
        armed = false
        targetPercent = 0
        _state.value = CoyoteState(
            connection = CoyoteConnection.CONNECTING,
            protocol = device.protocol,
            deviceName = device.name,
            deviceAddress = device.address,
            scanResults = emptyList()
        )
        val saved = settingsRepo.coyoteSettings.value.sanitized().copy(
            deviceAddress = device.address,
            deviceName = device.name
        )
        settings = saved
        settingsRepo.updateCoyoteSettings(saved)

        connectJob = appScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_state.value.connection == CoyoteConnection.CONNECTING) {
                failConnection(CoyoteError.CONNECT_FAILED)
            }
        }

        val remoteDevice = try {
            adapter.getRemoteDevice(device.address)
        } catch (_: IllegalArgumentException) {
            failConnection(CoyoteError.CONNECT_FAILED)
            return
        } catch (_: SecurityException) {
            failConnection(CoyoteError.PERMISSION_DENIED)
            return
        }
        val newGatt = try {
            remoteDevice.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            failConnection(CoyoteError.PERMISSION_DENIED)
            return
        } catch (exception: Exception) {
            Log.e(TAG, "connectGatt failed", exception)
            failConnection(CoyoteError.CONNECT_FAILED)
            return
        }
        if (newGatt == null) {
            failConnection(CoyoteError.CONNECT_FAILED)
            return
        }
        gatt = newGatt
    }

    override fun connectSaved() {
        val saved = settings
        if (saved.deviceAddress.isBlank()) {
            setError(CoyoteError.NO_SAVED_DEVICE)
            return
        }
        val protocol = when {
            saved.deviceName.startsWith(CoyoteV3Protocol.NAME) -> CoyoteProtocol.V3
            saved.deviceName.equals(CoyoteV2Protocol.NAME, ignoreCase = true) -> CoyoteProtocol.V2
            else -> CoyoteProtocol.V3
        }
        connect(CoyoteDevice(saved.deviceName.ifBlank { "Coyote" }, saved.deviceAddress, 0, protocol))
    }

    //endregion

    //region # Scan

    private fun stopScanInternal() {
        scanJob?.cancel()
        scanJob = null
        val callback = scanCallback
        scanCallback = null
        val leScanner = scanner
        scanner = null
        if (callback != null && leScanner != null) {
            try {
                leScanner.stopScan(callback)
            } catch (_: Exception) {
                // The adapter may already be off; nothing left to stop.
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName
            ?: try {
                result.device.name
            } catch (_: SecurityException) {
                null
            }
            ?: ""
        val advertisedProtocol = when {
            name.startsWith(CoyoteV3Protocol.NAME) -> CoyoteProtocol.V3
            name.equals(CoyoteV2Protocol.NAME, ignoreCase = true) -> CoyoteProtocol.V2
            else -> null
        }
        val services = result.scanRecord?.serviceUuids
        val protocol = advertisedProtocol ?: when {
            services?.any { it.uuid == CoyoteUuids.V3_SERVICE } == true -> CoyoteProtocol.V3
            services?.any { it.uuid == CoyoteUuids.V2_SERVICE } == true -> CoyoteProtocol.V2
            else -> return
        }
        val displayName = when {
            advertisedProtocol != null && name.isNotBlank() -> name
            protocol == CoyoteProtocol.V3 -> "Coyote V3"
            else -> "Coyote V2"
        }
        val address = try {
            result.device.address
        } catch (_: SecurityException) {
            return
        } ?: return
        val device = CoyoteDevice(displayName, address, result.rssi, protocol)
        synchronized(scanResults) {
            val previous = scanResults[address]
            if (previous == null || device.rssi > previous.rssi) {
                scanResults[address] = device
                val sorted = scanResults.values.sortedByDescending { it.rssi }
                _state.update { it.copy(scanResults = sorted) }
            }
        }
    }

    //endregion

    //region # GATT connection

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@CoyoteLink.gatt !== gatt) {
                try {
                    gatt.close()
                } catch (_: Exception) {
                    // Stale callback from a closed connection.
                }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Connected, discovering services")
                        try {
                            gatt.discoverServices()
                        } catch (_: SecurityException) {
                            failConnection(CoyoteError.PERMISSION_DENIED)
                        }
                    } else {
                        failConnection(CoyoteError.GATT_ERROR)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnecting = _state.value.connection == CoyoteConnection.CONNECTING
                    if (_state.value.connection != CoyoteConnection.IDLE) {
                        failConnection(
                            if (wasConnecting) CoyoteError.CONNECT_FAILED else CoyoteError.CONNECTION_LOST
                        )
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@CoyoteLink.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection(CoyoteError.GATT_ERROR)
                return
            }
            try {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (_: Exception) {
                // Optional optimisation.
            }
            val v3Service = gatt.getService(CoyoteUuids.V3_SERVICE)
            if (v3Service != null) {
                setupV3(gatt, v3Service)
                return
            }
            val v2Service = gatt.getService(CoyoteUuids.V2_SERVICE)
            if (v2Service != null) {
                setupV2(gatt, v2Service)
            } else {
                failConnection(CoyoteError.UNSUPPORTED_DEVICE)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattBusy = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write ${characteristic.uuid} failed: $status")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattBusy = false
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (value != null) handleRead(characteristic, value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            gattBusy = false
            handleRead(characteristic, value, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (value != null) handleNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(characteristic, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            gattBusy = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Descriptor write failed: $status")
            }
            descriptorFallbackJob?.cancel()
            descriptorFallbackJob = null
            val action = descriptorReadyAction
            descriptorReadyAction = null
            action?.invoke()
        }
    }

    private fun setupV3(gatt: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        val write = service.getCharacteristic(CoyoteUuids.V3_WRITE)
        val notify = service.getCharacteristic(CoyoteUuids.V3_NOTIFY)
        if (write == null || notify == null) {
            failConnection(CoyoteError.UNSUPPORTED_DEVICE)
            return
        }
        protocol = CoyoteProtocol.V3
        writeChar = write
        batteryChar = gatt.getService(CoyoteUuids.V3_BATTERY_SERVICE)
            ?.getCharacteristic(CoyoteUuids.V3_BATTERY)
        enableNotifications(gatt, notify) { finishSetup() }
    }

    private fun setupV2(gatt: BluetoothGatt, service: android.bluetooth.BluetoothGattService) {
        val power = service.getCharacteristic(CoyoteUuids.V2_POWER)
        val waveA = service.getCharacteristic(CoyoteUuids.V2_WAVE_A)
        val waveB = service.getCharacteristic(CoyoteUuids.V2_WAVE_B)
        if (power == null || waveA == null || waveB == null) {
            failConnection(CoyoteError.UNSUPPORTED_DEVICE)
            return
        }
        protocol = CoyoteProtocol.V2
        writeChar = power
        v2PowerChar = power
        v2WaveAChar = waveA
        v2WaveBChar = waveB
        batteryChar = gatt.getService(CoyoteUuids.V2_BATTERY_SERVICE)
            ?.getCharacteristic(CoyoteUuids.V2_BATTERY)
        enableNotifications(gatt, power) { finishSetup() }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        onReady: () -> Unit
    ) {
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                onReady()
                return
            }
            val descriptor = characteristic.getDescriptor(CoyoteUuids.CCCD)
            if (descriptor == null) {
                onReady()
                return
            }
            descriptorReadyAction = onReady
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            markBusy()
            if (!gatt.writeDescriptor(descriptor)) {
                gattBusy = false
                descriptorReadyAction = null
                onReady()
            } else {
                // Some stacks occasionally drop the descriptor callback. Do not let that
                // wedge the connection in CONNECTING forever.
                descriptorFallbackJob?.cancel()
                descriptorFallbackJob = appScope.launch {
                    delay(1_000)
                    val action = descriptorReadyAction ?: return@launch
                    descriptorReadyAction = null
                    gattBusy = false
                    action()
                }
            }
        } catch (_: SecurityException) {
            failConnection(CoyoteError.PERMISSION_DENIED)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not enable notifications", exception)
            failConnection(CoyoteError.GATT_ERROR)
        }
    }

    private fun finishSetup() {
        if (gatt == null) return
        connectJob?.cancel()
        connectJob = null
        _state.update {
            it.copy(connection = CoyoteConnection.CONNECTED, protocol = protocol, error = null)
        }
        outputActive = false
        pendingZero = true
        pendingSoftLimit = protocol == CoyoteProtocol.V3
        pendingBatteryRead = batteryChar != null
        pendingPowerRead = protocol == CoyoteProtocol.V2 && v2PowerChar != null
        powerReadAttempts = 0
        v2StrengthLoaded = protocol != CoyoteProtocol.V2
        gattBusy = false
        startOutputLoop()
    }

    private fun closeCurrentConnection() {
        outputJob?.cancel()
        outputJob = null
        connectJob?.cancel()
        connectJob = null
        descriptorReadyAction = null
        descriptorFallbackJob?.cancel()
        descriptorFallbackJob = null
        v2ForceResponseWrite = false
        val oldGatt = gatt
        gatt = null
        protocol = null
        writeChar = null
        batteryChar = null
        v2PowerChar = null
        v2WaveAChar = null
        v2WaveBChar = null
        gattBusy = false
        outputActive = false
        pendingZero = false
        pendingSoftLimit = false
        pendingBatteryRead = false
        pendingPowerRead = false
        v2StrengthLoaded = false
        powerReadAttempts = 0
        if (oldGatt != null) {
            try {
                oldGatt.disconnect()
            } catch (_: Exception) {
                // Already disconnected.
            }
            try {
                oldGatt.close()
            } catch (_: Exception) {
                // Already closed.
            }
        }
    }

    private fun failConnection(error: CoyoteError) {
        closeCurrentConnection()
        armed = false
        targetPercent = 0
        _state.update {
            it.copy(
                connection = CoyoteConnection.IDLE,
                protocol = null,
                armed = false,
                intensityPercent = 0,
                battery = null,
                error = error,
                isScanning = false
            )
        }
    }

    private fun setError(error: CoyoteError) {
        _state.update { it.copy(error = error) }
    }

    //endregion

    //region # Output engine

    private fun startOutputLoop() {
        if (outputJob?.isActive == true) return
        outputJob = appScope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (exception: Exception) {
                    Log.e(TAG, "Output tick failed", exception)
                }
                delay(TICK_MS)
            }
        }
    }

    private fun tick() {
        val currentProtocol = protocol ?: return
        if (gatt == null) return
        val now = SystemClock.elapsedRealtime()
        if (gattBusy) {
            if (now - busySince < GATT_OP_TIMEOUT_MS) return
            // Never let a lost GATT callback stall the outputs forever.
            gattBusy = false
        }
        if (pendingBatteryRead) {
            pendingBatteryRead = false
            val characteristic = batteryChar
            if (characteristic != null && !read(characteristic)) {
                pendingBatteryRead = true
            }
            return
        }
        if (currentProtocol == CoyoteProtocol.V2 && pendingPowerRead) {
            pendingPowerRead = false
            val characteristic = v2PowerChar
            if (characteristic == null) {
                v2StrengthLoaded = true
            } else if (!read(characteristic)) {
                handleV2PowerReadFailure()
            }
            return
        }
        if (currentProtocol == CoyoteProtocol.V3 && pendingSoftLimit) {
            if (write(writeChar, softLimitFrame(), noResponse = false)) {
                pendingSoftLimit = false
            }
            return
        }
        if (currentProtocol == CoyoteProtocol.V2 && !v2StrengthLoaded) {
            // Retry if the previous read callback was lost; never drive an unknown channel.
            pendingPowerRead = true
            return
        }

        val raw = rawTargetStrength()
        if (raw > 0) {
            if (sendActiveFrame(raw)) outputActive = true
        } else if (outputActive || pendingZero) {
            if (sendZeroFrame()) {
                outputActive = false
                pendingZero = false
            }
        }
    }

    private fun rawTargetStrength(): Int {
        val currentProtocol = protocol ?: return 0
        if (!armed || !targetValid) return 0
        return CoyoteCalculator.rawStrength(targetPercent, currentProtocol)
            .coerceIn(0, CoyoteCalculator.maxRawStrength(currentProtocol))
    }

    private fun softLimitFrame(): ByteArray {
        val limit = (settings.maxStrength * 2).coerceIn(0, CoyoteV3Protocol.MAX_STRENGTH)
        return CoyoteV3Protocol.bf(limit, limit)
    }

    private fun sendActiveFrame(raw: Int): Boolean {
        return when (protocol) {
            CoyoteProtocol.V3 -> sendV3Frame(raw)
            CoyoteProtocol.V2 -> sendV2ActiveFrame(raw)
            null -> false
        }
    }

    private fun sendV3Frame(rawStrength: Int): Boolean {
        val useA = settings.channel.usesA
        val useB = settings.channel.usesB
        val frequency = CoyoteV3Protocol.pulseFrequencyValue(
            CoyoteV3Protocol.DEFAULT_PULSE_FREQUENCY_MS
        )
        val frame = CoyoteV3Protocol.b0(
            // The official strengthZero() example uses sequence 1 with absolute mode.
            sequence = 1,
            modeA = if (useA) CoyoteV3Protocol.MODE_ABSOLUTE else CoyoteV3Protocol.MODE_KEEP,
            modeB = if (useB) CoyoteV3Protocol.MODE_ABSOLUTE else CoyoteV3Protocol.MODE_KEEP,
            strengthA = if (useA) rawStrength else 0,
            strengthB = if (useB) rawStrength else 0,
            frequenciesA = IntArray(4) { if (useA) frequency else 0 },
            intensitiesA = IntArray(4) {
                if (useA) CoyoteV3Protocol.DEFAULT_PULSE_INTENSITY
                else CoyoteV3Protocol.INVALID_PULSE_INTENSITY
            },
            frequenciesB = IntArray(4) { if (useB) frequency else 0 },
            intensitiesB = IntArray(4) {
                if (useB) CoyoteV3Protocol.DEFAULT_PULSE_INTENSITY
                else CoyoteV3Protocol.INVALID_PULSE_INTENSITY
            }
        )
        return write(writeChar, frame, noResponse = false)
    }

    private fun sendV2ActiveFrame(rawStrength: Int): Boolean {
        val useA = settings.channel.usesA
        val useB = settings.channel.usesB
        val targetA = if (useA) rawStrength else v2StrengthA
        val targetB = if (useB) rawStrength else v2StrengthB
        // The V2 host keeps the voltage until changed. With unacknowledged writes, reassert
        // the intensity every tick; if the stack forced acknowledged writes, only write on a
        // change so the wave frames can use the next tick.
        val needsPowerWrite = !v2ForceResponseWrite ||
            targetA != v2StrengthA || targetB != v2StrengthB
        if (needsPowerWrite) {
            val powerWritten = write(
                v2PowerChar,
                CoyoteV2Protocol.strength(targetA, targetB),
                noResponse = true
            )
            if (!powerWritten) return false
            v2StrengthA = targetA
            v2StrengthB = targetB
            // An acknowledged write occupies the GATT queue; wave frames go out next tick.
            if (gattBusy) return true
        }
        var success = true
        val wave = CoyoteV2Protocol.wave(CoyoteV2Protocol.DEFAULT_PULSE_FREQUENCY_MS)
        if (useA) success = write(v2WaveAChar, wave, noResponse = true) && success
        if (useB) success = write(v2WaveBChar, wave, noResponse = true) && success
        return success
    }

    private fun sendZeroFrame(): Boolean {
        return when (protocol) {
            CoyoteProtocol.V3 -> {
                val useA = settings.channel.usesA
                val useB = settings.channel.usesB
                val frame = CoyoteV3Protocol.b0(
                    sequence = 1,
                    modeA = if (useA) CoyoteV3Protocol.MODE_ABSOLUTE else CoyoteV3Protocol.MODE_KEEP,
                    modeB = if (useB) CoyoteV3Protocol.MODE_ABSOLUTE else CoyoteV3Protocol.MODE_KEEP,
                    strengthA = 0,
                    strengthB = 0,
                    frequenciesA = IntArray(4) { 0 },
                    intensitiesA = IntArray(4) { CoyoteV3Protocol.INVALID_PULSE_INTENSITY },
                    frequenciesB = IntArray(4) { 0 },
                    intensitiesB = IntArray(4) { CoyoteV3Protocol.INVALID_PULSE_INTENSITY }
                )
                write(writeChar, frame, noResponse = false)
            }

            CoyoteProtocol.V2 -> {
                val useA = settings.channel.usesA
                val useB = settings.channel.usesB
                val rawA = if (useA) 0 else v2StrengthA
                val rawB = if (useB) 0 else v2StrengthB
                if (!write(v2PowerChar, CoyoteV2Protocol.strength(rawA, rawB), noResponse = true)) {
                    return false
                }
                v2StrengthA = rawA
                v2StrengthB = rawB
                // Intensity zero is enough; the last wave pattern expires within 100 ms.
                true
            }

            null -> false
        }
    }

    private fun recomputeTarget() {
        val nextTarget = if (armed && targetValid) {
            CoyoteCalculator.intensityPercent(deviationDeg, settings)
        } else {
            0
        }
        targetPercent = nextTarget
        _state.update { it.copy(intensityPercent = nextTarget) }
    }

    //endregion

    //region # GATT helpers

    private fun markBusy() {
        busySince = SystemClock.elapsedRealtime()
        gattBusy = true
    }

    @Suppress("DEPRECATION")
    private fun write(
        characteristic: BluetoothGattCharacteristic?,
        data: ByteArray,
        noResponse: Boolean
    ): Boolean {
        val currentGatt = gatt ?: return false
        if (characteristic == null || gattBusy) return false
        val effectiveNoResponse = noResponse && !v2ForceResponseWrite
        val writeType = if (effectiveNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val success = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // BluetoothStatusCodes.SUCCESS is 0; avoid referencing the API-31 class here.
                currentGatt.writeCharacteristic(characteristic, data, writeType) == 0
            } else {
                characteristic.writeType = writeType
                characteristic.value = data
                currentGatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        } catch (exception: Exception) {
            Log.e(TAG, "Write failed", exception)
            false
        }
        if (success && !effectiveNoResponse) {
            markBusy()
        } else if (!success && effectiveNoResponse) {
            // V2 characteristics do not advertise write-without-response. If the stack
            // rejects that type, fall back to acknowledged writes for the rest of the session.
            v2ForceResponseWrite = true
        }
        return success
    }

    private fun read(characteristic: BluetoothGattCharacteristic?): Boolean {
        val currentGatt = gatt ?: return false
        if (characteristic == null || gattBusy) return false
        markBusy()
        val success = try {
            currentGatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        } catch (exception: Exception) {
            Log.e(TAG, "Read failed", exception)
            false
        }
        if (!success) gattBusy = false
        return success
    }

    private fun handleRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (characteristic.uuid == CoyoteUuids.V2_POWER) {
                handleV2PowerReadFailure()
            }
            return
        }
        if (characteristic.uuid == batteryChar?.uuid && value.isNotEmpty()) {
            val level = value[0].toInt() and 0xFF
            if (level <= 100) _state.update { it.copy(battery = level) }
            return
        }
        if (characteristic.uuid == CoyoteUuids.V2_POWER) {
            val parsed = CoyoteV2Protocol.parseStrength(value)
            if (parsed != null) {
                v2StrengthA = parsed.first
                v2StrengthB = parsed.second
                v2StrengthLoaded = true
                pendingPowerRead = false
            } else {
                handleV2PowerReadFailure()
            }
        }
    }

    private fun handleV2PowerReadFailure() {
        powerReadAttempts++
        if (powerReadAttempts < MAX_POWER_READ_ATTEMPTS) {
            pendingPowerRead = true
        } else {
            // Preserve nothing rather than risk driving an unknown channel.
            v2StrengthA = 0
            v2StrengthB = 0
            v2StrengthLoaded = true
        }
    }

    private fun handleNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        when {
            characteristic.uuid == CoyoteUuids.V3_NOTIFY -> {
                val b1 = CoyoteV3Protocol.parseB1(value)
                if (b1 != null) Log.d(TAG, "B1 seq=${b1.sequence} A=${b1.strengthA} B=${b1.strengthB}")
            }

            characteristic.uuid == CoyoteUuids.V2_POWER -> {
                val parsed = CoyoteV2Protocol.parseStrength(value) ?: return
                v2StrengthA = parsed.first
                v2StrengthB = parsed.second
                v2StrengthLoaded = true
                pendingPowerRead = false
            }
        }
    }

    //endregion
}
