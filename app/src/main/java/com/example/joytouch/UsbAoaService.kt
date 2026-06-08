package com.example.joytouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class UsbAoaService : Service() {

    private val binder = LocalBinder()
    private var usbManager: UsbManager? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    @Volatile
    private var outputStream: FileOutputStream? = null
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_ACCESSORY_DETACHED == intent.action) {
                val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                if (accessory != null) {
                    Log.d(TAG, "USB Accessory Detached: ${accessory.model}")
                    closeAccessory()
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): UsbAoaService = this@UsbAoaService
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()

        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // フォアグラウンドサービス開始
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // 起動時に既にアクセサリが接続されているか確認
        checkExistingAccessory()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accessory = intent?.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        if (accessory != null) {
            Log.d(TAG, "Accessory found in Intent: ${accessory.model}")
            openAccessory(accessory)
        } else {
            // Intentになくても、接続済みリストを再確認
            checkExistingAccessory()
        }
        return START_STICKY
    }

    private fun checkExistingAccessory() {
        val accessories = usbManager?.accessoryList
        if (!accessories.isNullOrEmpty()) {
            Log.d(TAG, "Accessory already connected: ${accessories[0].model}")
            openAccessory(accessories[0])
        } else {
            Log.d(TAG, "No connected accessory found in list.")
        }
    }

    private fun openAccessory(accessory: UsbAccessory) {
        if (outputStream != null) {
            Log.d(TAG, "Accessory is already open.")
            return
        }

        try {
            fileDescriptor = usbManager?.openAccessory(accessory)
            if (fileDescriptor != null) {
                val fd = fileDescriptor!!.fileDescriptor
                outputStream = FileOutputStream(fd)
                Log.i(TAG, "!!! USB Accessory Opened Successfully: ${accessory.model} !!!")
            } else {
                Log.e(TAG, "Failed to open accessory - openAccessory returned null (Permission denied?)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening USB accessory", e)
        }
    }

    fun isAccessoryOpened(): Boolean = outputStream != null

    fun sendData(data: ByteArray) {
        val stream = outputStream
        if (stream != null) {
            ioExecutor.submit {
                try {
                    stream.write(data)
                    stream.flush()

                    // 送信データの16進数デバッグログ
                    val hexString = data.joinToString(", ") { "0x%02X".format(it) }
                    Log.d(TAG, "USB Data Sent: [$hexString]")
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing to accessory (Disconnected?)", e)
                    closeAccessory()
                }
            }
        } else {
            // ここで null の場合に再接続を試みる（念のため）
            Log.w(TAG, "Cannot send data: outputStream is null. Attempting re-check...")
            checkExistingAccessory()
        }
    }

    private fun closeAccessory() {
        Log.d(TAG, "Closing accessory and clearing resources")
        try {
            outputStream?.close()
            fileDescriptor?.close()
        } catch (e: Exception) {
            // ignore
        } finally {
            outputStream = null
            fileDescriptor = null
            Log.d(TAG, "USB resources cleared. Ready for next connection.")
        }
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        closeAccessory()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "USB Accessory Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JoyTouch USB Active")
            .setContentText("Connected to PC via USB")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
    }

    companion object {
        private const val TAG = "UsbAoaService"
        private const val CHANNEL_ID = "usb_aoa_channel"
        private const val NOTIFICATION_ID = 2
    }
}