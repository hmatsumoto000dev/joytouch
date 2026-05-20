package com.example.joytouch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

@RequiresApi(Build.VERSION_CODES.P)
class MainActivity : AppCompatActivity() {

    // Bluetooth関連
    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    // HIDレポート用バッファ (Byte 0: Buttons, Byte 1: X-Axis, Byte 2: Y-Axis)
    private val reportBuffer = byteArrayOf(0, 128.toByte(), 128.toByte())

    // ボタンのインスタンスリスト
    private lateinit var buttons: List<MaterialButton>
    // 各ボタンの現在の状態（true: 押されている, false: 離されている）
    private val buttonStates = mutableMapOf<Int, Boolean>()

    private var isAppRegistered = false // 登録状態を管理
    private var isProxyConnecting = false // Proxy取得中フラグ

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 権限チェック (Android 12以降用)
        checkPermissions()

        // Bluetooth HID Proxyの取得
        initBluetoothHid()

        // 1. 全ボタンのIDをリスト化して取得
        val buttonIds = listOf(
            R.id.btn_left, R.id.btn_down, R.id.btn_right, R.id.btn_up,
            R.id.btn_atk1, R.id.btn_atk2, R.id.btn_atk3, R.id.btn_atk4,
            R.id.btn_atk5, R.id.btn_atk6, R.id.btn_atk7, R.id.btn_atk8,
        )

        buttons = buttonIds.map { id ->
            findViewById<MaterialButton>(id).apply {
                isClickable = false
            }
        }

        buttons.forEach { buttonStates[it.id] = false }

        // Aboutボタンの設定
        findViewById<View>(R.id.btn_about).setOnClickListener {
            showAboutDialog()
        }

        // Connectボタンの設定
        findViewById<View>(R.id.btn_connect).setOnClickListener {
            connectToHost()
        }

        // 2. ルートレイアウトにマルチタッチリスナーを設定
        val mainLayout = findViewById<View>(R.id.main_layout)
        mainLayout.setOnTouchListener { v, event ->
            handleMultiTouch(event)
            v.performClick()
            true
        }
    }

    /**
     * Bluetooth HIDの初期化
     */
    private fun initBluetoothHid() {
        if (isProxyConnecting || (bluetoothHidDevice != null)) {
            Log.d("BluetoothHID", "Initialization already in progress or completed.")
            return
        }

        Log.d("BluetoothHID", "Initializing HID profile...")
        isProxyConnecting = true

        val success = bluetoothAdapter?.getProfileProxy(
            this,
            object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    Log.d("BluetoothHID", "HID Device Profile connected (Proxy received)")
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    isProxyConnecting = false
                    registerApp()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    Log.d("BluetoothHID", "HID Device Profile disconnected")
                    bluetoothHidDevice = null
                    isAppRegistered = false
                    isProxyConnecting = false
                }
            }
        }, BluetoothProfile.HID_DEVICE)

        if (success == false) {
            isProxyConnecting = false
            Log.e("BluetoothHID", "getProfileProxy failed")
            Toast.makeText(this, "Bluetooth HID Profile not supported", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * HIDデバイスとしてアプリを登録
     */
    @SuppressLint("MissingPermission") // 権限チェックは呼び出し前に行っているため抑制
    private fun registerApp() {
        if (isAppRegistered) {
            Log.d("BluetoothHID", "App already registered, skipping...")
            return
        }

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Android Gamepad",
            "Android HID Gamepad",
            "Android",
            0x04, // 0x00 から 0x04 (Joystick) に変更してデバイスの種類を明示
            HID_REPORT_DESCRIPTOR
        )

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e("BluetoothHID", "Permission missing for registration")
            return
        }

        bluetoothHidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
                super.onAppStatusChanged(device, registered)
                isAppRegistered = registered
                runOnUiThread {
                    val msg = if (registered) "HID Gamepad Ready!" else "HID Registration Failed"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    Log.d("BluetoothHID", "App registration status: $registered")
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                super.onConnectionStateChanged(device, state)
                val stateName = when(state) {
                    BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> state.toString()
                }
                // デバイス名の取得に権限が必要なため、ログ出力を安全にする
                val deviceName = try { device?.name } catch (_: SecurityException) { "Unknown Device" }
                Log.i("BluetoothHID", "!!! Connection state changed: $stateName with device: $deviceName !!!")
                connectedHost = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            }
        })
    }

    // クリーンアップ処理を追加
    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        Log.d("BluetoothHID", "!!! onDestroy called !!!")
        super.onDestroy()
        if (isAppRegistered) {
                bluetoothHidDevice?.unregisterApp()
        }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, bluetoothHidDevice)
    }

    /**
     * ペアリング済みのホスト(PC)へ接続を要求する
     */
    @SuppressLint("MissingPermission")
    private fun connectToHost() {
        if (bluetoothHidDevice == null || !isAppRegistered) {
            if (isProxyConnecting) {
                Toast.makeText(this, "Initializing HID Profile... please wait", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "HID Profile not ready. Starting...", Toast.LENGTH_SHORT).show()
                initBluetoothHid()
            }
            return
        }

        val bondedDevices = bluetoothAdapter?.bondedDevices
        // ペアリング済みデバイスからホスト（名前や種類で判断）を探す
        // ここでは便宜上、LAPTOP/PC/DESKTOPが含まれる名前を優先的に探します
        val host = bondedDevices?.find {
            val name = it.name?.uppercase() ?: ""
            name.contains("LAPTOP") || name.contains("PC") || name.contains("DESKTOP") || name.contains("WINDOWS")
        }

        if (host != null) {
            Toast.makeText(this, "Connecting to ${host.name}...", Toast.LENGTH_SHORT).show()
            val success = bluetoothHidDevice?.connect(host)
            if (success == false) {
                Toast.makeText(this, "Failed to send connect request", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No paired host found. Pair in Settings first.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * HIDレポートを送信
     */
    @SuppressLint("MissingPermission")
    private fun sendHidReport() {
        // レポートの作成
        // Byte 0: 攻撃ボタン (atk1:bit0, atk2:bit1, ...)
        var buttonsByte = 0
        if (buttonStates[R.id.btn_atk1] == true) buttonsByte = buttonsByte or 0x01
        if (buttonStates[R.id.btn_atk2] == true) buttonsByte = buttonsByte or 0x02
        if (buttonStates[R.id.btn_atk3] == true) buttonsByte = buttonsByte or 0x04
        if (buttonStates[R.id.btn_atk4] == true) buttonsByte = buttonsByte or 0x08
        if (buttonStates[R.id.btn_atk5] == true) buttonsByte = buttonsByte or 0x10
        if (buttonStates[R.id.btn_atk6] == true) buttonsByte = buttonsByte or 0x20
        if (buttonStates[R.id.btn_atk7] == true) buttonsByte = buttonsByte or 0x40
        if (buttonStates[R.id.btn_atk8] == true) buttonsByte = buttonsByte or 0x80
        reportBuffer[0] = buttonsByte.toByte()

        // Byte 1: X軸 (0-255, 中央128)
        var xAxis = 128
        if (buttonStates[R.id.btn_left] == true) xAxis = 0
        if (buttonStates[R.id.btn_right] == true) xAxis = 255
        reportBuffer[1] = xAxis.toByte()

        // Byte 2: Y軸 (0-255, 中央128)
        var yAxis = 128
        if (buttonStates[R.id.btn_up] == true) yAxis = 0
        if (buttonStates[R.id.btn_down] == true) yAxis = 255
        reportBuffer[2] = yAxis.toByte()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            connectedHost?.let {
                // 第2引数は 1 (Report ID 1)
                bluetoothHidDevice?.sendReport(it, 1, reportBuffer)
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val requestList = permissions.filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (requestList.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, requestList.toTypedArray(), 1)
            }
        }
    }

    /**
     * Aboutダイアログを表示
     */
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_msg)
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    /**
     * マルチタッチ検知の核心部分
     */
    private fun handleMultiTouch(event: MotionEvent) {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        val currentlyPressedIds = mutableSetOf<Int>()

        for (i in 0 until pointerCount) {
            if (action == MotionEvent.ACTION_UP ||
                action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex)
            ) {
                continue
            }

            val x = event.getX(i)
            val y = event.getY(i)

            for (button in buttons) {
                if (isPointInView(x, y, button)) {
                    currentlyPressedIds.add(button.id)
                }
            }
        }

        for (button in buttons) {
            val isNowPressed = currentlyPressedIds.contains(button.id)
            val wasPressed = buttonStates[button.id] ?: false

            if (isNowPressed != wasPressed) {
                buttonStates[button.id] = isNowPressed
                updateButtonFeedback(button, isNowPressed)

                val statusText = if (isNowPressed) "ON" else "OFF"
                val buttonName = resources.getResourceEntryName(button.id)
                Log.d("Controller", "[$buttonName] $statusText")

                // Bluetoothでレポート送信
                sendHidReport()
            }
        }
    }

    private fun isPointInView(x: Float, y: Float, view: View): Boolean {
        return x >= view.left && x <= view.right && y >= view.top && y <= view.bottom
    }

    private fun updateButtonFeedback(button: MaterialButton, isPressed: Boolean) {
        button.isPressed = isPressed
        button.alpha = if (isPressed) 0.5f else 1.0f
    }

    companion object {
        // Windows/Android双方で受理されやすい極小のゲームパッド記述子
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x05.toByte(), // Usage (Gamepad)
            0xa1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), 0x01.toByte(), // Report ID (1)
            // --- ボタン (8個) ---
            0x05.toByte(), 0x09.toByte(), //   Usage Page (Button)
            0x19.toByte(), 0x01.toByte(), //   Usage Minimum (1)
            0x29.toByte(), 0x08.toByte(), //   Usage Maximum (8)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), //   Report Size (1)
            0x95.toByte(), 0x08.toByte(), //   Report Count (8)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)
            // --- X/Y軸 (2個) ---
            0x05.toByte(), 0x01.toByte(), //   Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(), //   Usage (X)
            0x09.toByte(), 0x31.toByte(), //   Usage (Y)
            0x15.toByte(), 0x00.toByte(), //   Logical Minimum (0)
            0x26.toByte(), 0xff.toByte(), 0x00.toByte(), // Logical Maximum (255)
            0x75.toByte(), 0x08.toByte(), //   Report Size (8)
            0x95.toByte(), 0x02.toByte(), //   Report Count (2)
            0x81.toByte(), 0x02.toByte(), //   Input (Data, Variable, Absolute)
            0xc0.toByte()                 // End Collection
        )
    }
}
