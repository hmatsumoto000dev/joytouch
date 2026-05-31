package com.example.joytouch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

class UsbAoaService : Service() {

    private val binder = LocalBinder()
    private var usbManager: UsbManager? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null

    inner class LocalBinder : Binder() {
        fun getService(): UsbAoaService = this@UsbAoaService
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accessory = intent?.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        if (accessory != null) {
            openAccessory(accessory)
        }
        return START_STICKY
    }

    private fun openAccessory(accessory: UsbAccessory) {
        fileDescriptor = usbManager?.openAccessory(accessory)
        if (fileDescriptor != null) {
            val fd = fileDescriptor!!.fileDescriptor
            outputStream = FileOutputStream(fd)
            Log.d(TAG, "Accessory opened: ${accessory.model}")
        } else {
            Log.e(TAG, "Failed to open accessory")
        }
    }

    fun sendData(data: ByteArray) {
        val stream = outputStream
        if (stream != null) {
            try {
                stream.write(data)
                stream.flush()

                // 送信データのデバッグログを追加
                val hexString = data.joinToString(", ") { "0x%02X".format(it) }
                Log.d("UsbAoaService", "USB Data Sent: [$hexString]")
            } catch (e: IOException) {
                Log.e(TAG, "Error writing to accessory", e)
                closeAccessory()
            }
        } else {
            // streamがnull（未接続）の場合のログ
            Log.w(TAG, "Cannot send data: outputStream is null (Accessory not opened)")
        }
    }

    private fun closeAccessory() {
        try {
            outputStream?.close()
            fileDescriptor?.close()
        } catch (e: IOException) {
            // ignore
        } finally {
            outputStream = null
            fileDescriptor = null
        }
    }

    override fun onDestroy() {
        closeAccessory()
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
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // Use appropriate icon
            .build()
    }

    companion object {
        private const val TAG = "UsbAoaService"
        private const val CHANNEL_ID = "usb_aoa_channel"
        private const val NOTIFICATION_ID = 2
    }
}
