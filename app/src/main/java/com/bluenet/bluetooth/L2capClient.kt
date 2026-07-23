package com.bluenet.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException

class L2capClient(
    private val device: BluetoothDevice,
    private val psm: Int,
    private val onConnected: (BluetoothSocket) -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: BluetoothSocket? = null
    private var connectionThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun connect() {
        connectionThread = Thread({
            try {
                Log.d(TAG, "Attempting L2CAP connection to ${device.address} on PSM $psm")
                // Android 10+ (API 29+) L2CAP Channel Connection
                val l2capSocket = device.createInsecureL2capChannel(psm)
                l2capSocket.connect()
                socket = l2capSocket

                Log.d(TAG, "L2CAP socket connected successfully to ${device.address}")
                onConnected(l2capSocket)
            } catch (e: IOException) {
                Log.e(TAG, "L2CAP connection failed", e)
                onError("Failed to connect via L2CAP: ${e.localizedMessage}")
                disconnect()
            }
        }, "L2capClientConnectThread").apply { start() }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing client L2CAP socket", e)
        }
        socket = null
        connectionThread?.interrupt()
        connectionThread = null
    }

    companion object {
        private const val TAG = "L2capClient"
    }
}
