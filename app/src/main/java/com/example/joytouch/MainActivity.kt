package com.example.joytouch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

@RequiresApi(Build.VERSION_CODES.P)
class MainActivity : AppCompatActivity() {

    // Service関連
    private var hidService: HidDeviceService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HidDeviceService.LocalBinder
            hidService = binder.getService()
            isBound = true
            Log.d("MainActivity", "Service connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            hidService = null
            Log.d("MainActivity", "Service disconnected")
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    // HIDレポート用バッファ (Byte 0: Buttons, Byte 1: X-Axis, Byte 2: Y-Axis)
    private val reportBuffer = byteArrayOf(0, 0, 0)

    // ボタンのインスタンスリスト
    private lateinit var buttons: List<MaterialButton>
    // 各ボタンの現在の状態（true: 押されている, false: 離されている）
    private val buttonStates = mutableMapOf<Int, Boolean>()

    private var isEditMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 権限チェック
        checkPermissions()

        // サービスの開始とバインド
        val intent = Intent(this, HidDeviceService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // 全ボタンのIDをリスト化して取得
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

        // モード切替ボタンの設定
        val modeToggle = findViewById<MaterialButton>(R.id.btn_mode_toggle)
        modeToggle.setOnClickListener {
            isEditMode = !isEditMode
            modeToggle.text = if (isEditMode) getString(R.string.mode_edit) else getString(R.string.mode_play)
            setupMode()
        }

        // 保存された位置を復元
        loadButtonPositions()

        // Aboutボタンの設定
        findViewById<View>(R.id.btn_about).setOnClickListener {
            showAboutDialog()
        }

        // Connectボタンの設定
        findViewById<View>(R.id.btn_connect).setOnClickListener {
            connectToHost()
        }

        // ルートレイアウトにマルチタッチリスナーを設定
        val mainLayout = findViewById<View>(R.id.main_layout)
        mainLayout.setOnTouchListener { v, event ->
            if (!isEditMode) {
                handleMultiTouch(event)
            }
            v.performClick()
            true
        }
    }

    private fun setupMode() {
        if (isEditMode) {
            // 編集モード: 各ボタンにドラッグリスナーを設定
            buttons.forEach { button ->
                button.setOnTouchListener(createDragListener())
            }
        } else {
            // 操作モード: 個別のリスナーを解除し、状態をリセット
            buttons.forEach { button ->
                button.setOnTouchListener(null)
                buttonStates[button.id] = false
                updateButtonFeedback(button, false)
            }
            // ニュートラル状態を送信
            sendHidReport()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragListener(): View.OnTouchListener {
        var lastRawX = 0f
        var lastRawY = 0f
        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - lastRawX
                    val deltaY = event.rawY - lastRawY
                    v.translationX += deltaX
                    v.translationY += deltaY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_UP -> {
                    saveButtonPosition(v)
                }
            }
            true
        }
    }

    private fun saveButtonPosition(view: View) {
        val prefs = getSharedPreferences("button_prefs", Context.MODE_PRIVATE)
        val buttonName = resources.getResourceEntryName(view.id)
        prefs.edit().apply {
            putFloat("${buttonName}_tx", view.translationX)
            putFloat("${buttonName}_ty", view.translationY)
            apply()
        }
    }

    private fun loadButtonPositions() {
        val prefs = getSharedPreferences("button_prefs", Context.MODE_PRIVATE)
        buttons.forEach { button ->
            val buttonName = resources.getResourceEntryName(button.id)
            button.translationX = prefs.getFloat("${buttonName}_tx", 0f)
            button.translationY = prefs.getFloat("${buttonName}_ty", 0f)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    /**
     * ペアリング済みのホスト(PC)へ接続を要求する
     */
    @SuppressLint("MissingPermission")
    private fun connectToHost() {
        val service = hidService
        if (service == null) {
            Toast.makeText(this, "Service not bound", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. HIDプロファイルの登録状態をチェック
        if (!service.isReady()) {
            Toast.makeText(this, "HID Profile not registered. Re-registering...", Toast.LENGTH_SHORT).show()
            service.registerApp()
            return
        }

        // 2. 現在の接続状態を確認
        val connectionState = service.getConnectionState()
        if (connectionState == BluetoothProfile.STATE_CONNECTED) {
            Toast.makeText(this, "Already connected to a host", Toast.LENGTH_SHORT).show()
            return
        } else if (connectionState == BluetoothProfile.STATE_CONNECTING) {
            Toast.makeText(this, "Connection in progress...", Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = bluetoothAdapter?.bondedDevices
        val host = bondedDevices?.find {
            val name = it.name?.uppercase() ?: ""
            name.contains("LAPTOP") || name.contains("PC") || name.contains("DESKTOP") || name.contains("WINDOWS")
        }

        if (host != null) {
            Toast.makeText(this, "Connecting to ${host.name}...", Toast.LENGTH_SHORT).show()
            val success = service.connect(host)
            if (!success) {
                Toast.makeText(this, "Failed to initiate connection. Is Bluetooth ON?", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "No paired host found. Pair in Settings first.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * HIDレポートを送信
     */
    private fun sendHidReport() {
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

        var xAxis = 0
        if (buttonStates[R.id.btn_left] == true) xAxis = -127
        if (buttonStates[R.id.btn_right] == true) xAxis = 127
        reportBuffer[1] = xAxis.toByte()

        var yAxis = 0
        if (buttonStates[R.id.btn_up] == true) yAxis = -127
        if (buttonStates[R.id.btn_down] == true) yAxis = 127
        reportBuffer[2] = yAxis.toByte()

        hidService?.sendReport(1, reportBuffer)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN
            ).toMutableList()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }

            val requestList = permissions.filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (requestList.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, requestList.toTypedArray(), 1)
            }
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_msg)
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

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
                sendHidReport()
            }
        }
    }

    private fun isPointInView(x: Float, y: Float, view: View): Boolean {
        val left = view.left + view.translationX
        val right = view.right + view.translationX
        val top = view.top + view.translationY
        val bottom = view.bottom + view.translationY
        return x >= left && x <= right && y >= top && y <= bottom
    }

    private fun updateButtonFeedback(button: MaterialButton, isPressed: Boolean) {
        button.isPressed = isPressed
        button.alpha = if (isPressed) 0.5f else 1.0f
    }
}
