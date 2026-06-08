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
            "Android Gamepad",
            "Android HID Gamepad",
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
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x05.toByte(), // Usage (Gamepad)
            0xa1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), // Report ID (1)

            // --- 1st Byte: Mode (0x00=Gamepad, 0x01=Touchpad) ---
            // Defined as a vendor-defined constant to make it 4 bytes total
            0x06.toByte(), 0x00.toByte(), 0xff.toByte(), // Usage Page (Vendor Defined)
            0x09.toByte(), 0x01.toByte(),               // Usage (Vendor Defined)
            0x15.toByte(), 0x00.toByte(),               // Logical Minimum (0)
            0x25.toByte(), 0xff.toByte(),               // Logical Maximum (255)
            0x75.toByte(), 0x08.toByte(),               // Report Size (8 bits)
            0x95.toByte(), 0x01.toByte(),               // Report Count (1)
            0x81.toByte(), 0x02.toByte(),               // Input (Data, Variable, Absolute)

            // --- 2nd Byte: Buttons (8 bits) ---
            0x05.toByte(), 0x09.toByte(), // Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //   Usage Minimum (1)
            0x29.toByte(), 0x08.toByte(), //   Usage Maximum (8)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)

            // --- 3rd & 4th Bytes: X, Y Axes ---
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //   Usage (X)
            0x09.toByte(), 0x31.toByte(), //   Usage (Y)
            0x15.toByte(), 0x81.toByte(), //   Logical Minimum (-127)
            0x25.toByte(), 0x7f.toByte(), //   Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x95.toByte(), 0x02.toByte(), //   Report Count (2)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)

            0xc0.toByte()                 // End Collection
        )
    }
}
