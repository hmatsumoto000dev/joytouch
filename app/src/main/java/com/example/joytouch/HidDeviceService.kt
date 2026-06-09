package com.example.joytouch

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class HidDeviceService : Service() {

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var isAppRegistered = false
    private var currentConnectionState = BluetoothProfile.STATE_DISCONNECTED
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): HidDeviceService = this@HidDeviceService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundServiceWithNotification()
        initBluetoothHid()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // もしプロファイルが消えていたら再初期化
        if (bluetoothHidDevice == null) {
            initBluetoothHid()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HID Device Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JoyTouch HID Active")
            .setContentText("Running in background as HID device")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initBluetoothHid() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth is disabled or not available")
            return
        }
        
        Log.d(TAG, "Initializing HID profile...")
        bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    registerApp()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = null
                    isAppRegistered = false
                    currentConnectionState = BluetoothProfile.STATE_DISCONNECTED
                }
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun registerApp() {
        if (isAppRegistered && bluetoothHidDevice != null) return
        if (bluetoothHidDevice == null) {
            initBluetoothHid()
            return
        }

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "JoyTouch Combo",
            "HID Gamepad and Mouse",
            "Android",
            0x04,
            HID_REPORT_DESCRIPTOR
        )

        bluetoothHidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                isAppRegistered = registered
                Log.d(TAG, "App registration status: $registered")
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                currentConnectionState = state
                connectedHost = if (state == BluetoothProfile.STATE_CONNECTED) device else null
                Log.d(TAG, "Connection state changed: $state")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        if (!isAppRegistered) return false
        return bluetoothHidDevice?.connect(device) ?: false
    }

    @SuppressLint("MissingPermission")
    fun sendReport(reportId: Int, data: ByteArray) {
        connectedHost?.let {
            val success = bluetoothHidDevice?.sendReport(it, reportId, data) ?: false
            if (success) {
                val hexString = data.joinToString(", ") { "0x%02X".format(it) }
                Log.d(TAG, "HID Report Sent (ID: $reportId): [$hexString]")
            } else {
                Log.e(TAG, "Failed to send HID Report")
            }
        } ?: Log.w(TAG, "Cannot send HID Report: No connected host")
    }

    fun isReady(): Boolean = isAppRegistered && bluetoothHidDevice != null

    fun getConnectionState(): Int = currentConnectionState

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        if (isAppRegistered) {
            bluetoothHidDevice?.unregisterApp()
        }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HidDeviceService"
        private const val CHANNEL_ID = "hid_service_channel"
        private const val NOTIFICATION_ID = 1

        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // --- Gamepad (Report ID 1) ---
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x05.toByte(), // Usage (Gamepad)
            0xa1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), //   Report ID (1)

            // 1st Byte: Mode (Vendor Defined)
            0x06.toByte(), 0x00.toByte(), 0xff.toByte(),
            0x09.toByte(), 0x01.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0xff.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x02.toByte(),

            // 2nd Byte: Buttons
            0x05.toByte(), 0x09.toByte(),
            0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x08.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x08.toByte(),
            0x81.toByte(), 0x02.toByte(),

            // 3rd & 4th Bytes: Axes
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x30.toByte(),
            0x09.toByte(), 0x31.toByte(),
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7f.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x02.toByte(),
            0x81.toByte(), 0x02.toByte(),
            0xc0.toByte(),                 // End Gamepad Application Collection

            // --- Mouse (Report ID 2) ---
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
            0xa1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x02.toByte(), //   Report ID (2)
            0x09.toByte(), 0x01.toByte(), //   Usage (Pointer)
            0xa1.toByte(), 0x00.toByte(), //   Collection (Physical)
            // Buttons (3)
            0x05.toByte(), 0x09.toByte(),
            0x19.toByte(), 0x01.toByte(),
            0x29.toByte(), 0x03.toByte(),
            0x15.toByte(), 0x00.toByte(),
            0x25.toByte(), 0x01.toByte(),
            0x95.toByte(), 0x03.toByte(),
            0x75.toByte(), 0x01.toByte(),
            0x81.toByte(), 0x02.toByte(),
            // Padding (5 bits)
            0x95.toByte(), 0x01.toByte(),
            0x75.toByte(), 0x05.toByte(),
            0x81.toByte(), 0x03.toByte(),
            // X, Y Displacement
            0x05.toByte(), 0x01.toByte(),
            0x09.toByte(), 0x30.toByte(),
            0x09.toByte(), 0x31.toByte(),
            0x15.toByte(), 0x81.toByte(),
            0x25.toByte(), 0x7f.toByte(),
            0x75.toByte(), 0x08.toByte(),
            0x95.toByte(), 0x02.toByte(),
            0x81.toByte(), 0x06.toByte(),
            0xc0.toByte(),                //   End Pointer Physical Collection
            0xc0.toByte()                 // End Mouse Application Collection
        )
    }
}
