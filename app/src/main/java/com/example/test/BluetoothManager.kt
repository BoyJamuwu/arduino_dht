
package com.example.test

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

object BluetoothManager {

    private val uuid = UUID.fromString(
        "00001101-0000-1000-8000-00805F9B34FB"
    )

    private var socket: BluetoothSocket? = null
    private var readerThread: Thread? = null

    @Volatile
    var lastMessage: String? = null
        private set

    @Volatile
    var lastTemperature: String? = null
        private set

    @Volatile
    var lastHumidity: String? = null
        private set

    @Volatile
    private var messageListener: ((String) -> Unit)? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(
        device: BluetoothDevice,
        onResult: (Boolean, String) -> Unit
    ) {
        Thread {
            var newSocket: BluetoothSocket? = null

            try {
                disconnect()

                newSocket =
                    device.createRfcommSocketToServiceRecord(uuid)

                newSocket.connect()
                socket = newSocket

                onResult(
                    true,
                    device.name ?: "Dispositivo Bluetooth"
                )

            } catch (e: Exception) {
                try {
                    newSocket?.close()
                } catch (_: IOException) {
                }

                socket = null

                onResult(false, e.message ?: "Error desconocido")
            }
        }.start()
    }

    fun send(text: String): Boolean {
        return try {
            val activeSocket = socket ?: return false

            synchronized(activeSocket) {
                activeSocket.outputStream.write(
                    text.toByteArray(Charsets.UTF_8)
                )
                activeSocket.outputStream.flush()
            }

            true

        } catch (e: IOException) {
            false
        }
    }

    fun setMessageListener(listener: (String) -> Unit) {
        messageListener = listener

        lastMessage?.let {
            listener(it)
        }
    }

    fun clearMessageListener() {
        messageListener = null
    }

    fun startReading() {
        val activeSocket = socket ?: return

        if (readerThread?.isAlive == true) return

        readerThread = Thread {
            try {
                val reader = BufferedReader(
                    InputStreamReader(activeSocket.inputStream)
                )

                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break

                    lastMessage = line

                    // Extraer temperatura
                    val temperaturaRegex = Regex(
                        """(?:Temperatura|Temp):\s*(-?\d+(?:\.\d+)?)"""
                    )

                    // Extraer humedad
                    val humedadRegex = Regex(
                        """Humedad:\s*(\d+(?:\.\d+)?)"""
                    )

                    temperaturaRegex.find(line)?.let {
                        lastTemperature = it.groupValues[1]
                    }

                    humedadRegex.find(line)?.let {
                        lastHumidity = it.groupValues[1]
                    }

                    // Entregar mensaje a la pantalla activa
                    messageListener?.invoke(line)
                }

            } catch (e: IOException) {
                // La conexión se cerró o se perdió
            }
        }.apply {
            name = "BluetoothReader"
            start()
        }
    }

    fun disconnect() {
        try {
            readerThread?.interrupt()
            socket?.close()
        } catch (_: IOException) {
        }

        readerThread = null
        socket = null
    }
}
