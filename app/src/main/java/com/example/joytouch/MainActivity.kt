package com.example.joytouch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    // Service関連
    private var hidService: HidDeviceService? = null
    private var usbAoaService: UsbAoaService? = null
    private var isBound = false
    private var isUsbBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as HidDeviceService.LocalBinder
            hidService = binder.getService()
            isBound = true
            Log.d("MainActivity", "HID Service connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            hidService = null
            Log.d("MainActivity", "HID Service disconnected")
        }
    }

    private val usbConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as UsbAoaService.LocalBinder
            usbAoaService = binder.getService()
            isUsbBound = true
            Log.d("MainActivity", "USB AOA Service connected")
            
            // 初回起動時にAccessory情報があれば渡す
            intent?.let { handleUsbIntent(it) }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isUsbBound = false
            usbAoaService = null
            Log.d("MainActivity", "USB AOA Service disconnected")
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    // HIDレポート用バッファ (Byte 0: Mode, Byte 1: Buttons, Byte 2: X-Axis, Byte 3: Y-Axis)
    private val reportBuffer = byteArrayOf(0, 0, 0, 0)

    // ボタンのインスタンスリスト
    private lateinit var buttons: List<MaterialButton>
    // 各ボタンの現在の状態（true: 押されている, false: 離されている）
    private val buttonStates = mutableMapOf<Int, Boolean>()

    private var isEditMode = false
    private var activeEditViewId = View.NO_ID
    private var isTouchpadMode = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastSendTime = 0L

    // 各ボタンの編集サブモード（true: サイズ変更, false: 位置移動）
    private val isResizeModeMap = mutableMapOf<Int, Boolean>()
    // ダブルタップ判定用
    private val lastClickTimeMap = mutableMapOf<Int, Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 権限チェック
        checkPermissions()

        // サービスの開始とバインド
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val hidIntent = Intent(this, HidDeviceService::class.java)
            startForegroundService(hidIntent)
            bindService(hidIntent, connection, Context.BIND_AUTO_CREATE)
        }

        val usbIntent = Intent(this, UsbAoaService::class.java)
        startForegroundService(usbIntent)
        bindService(usbIntent, usbConnection, Context.BIND_AUTO_CREATE)

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

        buttons.forEach { 
            buttonStates[it.id] = false 
            isResizeModeMap[it.id] = false
        }

        // モード切替ボタンの設定（後方互換性のためのダミー取得）
        val resetBtn = findViewById<MaterialButton>(R.id.btn_reset)

        // ナビゲーションメニューの設定
        val topNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.top_navigation)
        topNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_gamepad -> {
                    if (isEditMode) return@setOnItemSelectedListener false
                    isTouchpadMode = false
                    findViewById<View>(R.id.touchpad_area).visibility = View.GONE
                    sendHidReport()
                    true
                }
                R.id.nav_touchpad -> {
                    if (isEditMode) return@setOnItemSelectedListener false
                    isTouchpadMode = true
                    findViewById<View>(R.id.touchpad_area).visibility = View.VISIBLE
                    sendHidReport()
                    true
                }
                R.id.nav_edit -> {
                    isEditMode = !isEditMode
                    resetBtn.visibility = if (isEditMode) View.VISIBLE else View.GONE
                    setupMode()
                    // 編集モード中は他のメニューを無効化するなどの視覚的フィードバック
                    item.title = if (isEditMode) "Play Mode" else "Edit Mode"
                    true
                }
                R.id.nav_connect -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        connectToHost()
                    } else {
                        Toast.makeText(this, "Bluetooth HID is not supported on this Android version", Toast.LENGTH_LONG).show()
                    }
                    false // 選択状態にはしない
                }
                R.id.nav_about -> {
                    showAboutDialog()
                    false // 選択状態にはしない
                }
                else -> false
            }
        }

        // 初期配置に戻すボタン
        resetBtn.setOnClickListener {
            resetLayout()
        }

        // 保存された位置を復元
        loadButtonPositions()

        setupTouchpad()

        // フルスクリーン（イマーシブモード）の設定
        hideSystemUI()

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
        activeEditViewId = View.NO_ID
        if (isEditMode) {
            // 編集モード: 各ボタンにドラッグリスナーを設定
            buttons.forEach { button ->
                button.setOnTouchListener(createDragListener())
                updateButtonEditVisual(button)
            }
        } else {
            // 操作モード: 個別のリスナーを解除し、状態をリセット
            buttons.forEach { button ->
                button.setOnTouchListener(null)
                buttonStates[button.id] = false
                button.strokeWidth = 0 // 枠線を消す
                updateButtonFeedback(button, false)
            }
            // ニュートラル状態を送信
            sendHidReport()
        }
    }

    private fun updateButtonEditVisual(button: View) {
        if (button is MaterialButton) {
            val isResize = isResizeModeMap[button.id] ?: false
            if (isResize) {
                button.strokeWidth = 8
                button.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.YELLOW)
            } else {
                button.strokeWidth = 4
                button.strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.CYAN)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragListener(): View.OnTouchListener {
        var lastRawX = 0f
        var lastRawY = 0f
        return View.OnTouchListener { v, event ->
            // 編集モード中、既に他のボタンを操作している場合は無視
            if (activeEditViewId != View.NO_ID && activeEditViewId != v.id) {
                return@OnTouchListener false
            }

            // マルチタッチ（2本目以降の指）を検知した場合は操作を中断
            if (event.pointerCount > 1) {
                if (activeEditViewId == v.id) {
                    activeEditViewId = View.NO_ID
                    saveButtonPosition(v)
                }
                return@OnTouchListener true
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeEditViewId = v.id
                    val currentTime = System.currentTimeMillis()
                    val lastTime = lastClickTimeMap[v.id] ?: 0L
                    if (currentTime - lastTime < 300) {
                        // ダブルタップ検知: モード切り替え
                        isResizeModeMap[v.id] = !(isResizeModeMap[v.id] ?: false)
                        updateButtonEditVisual(v)
                        Toast.makeText(this, if (isResizeModeMap[v.id] == true) "Resize Mode" else "Move Mode", Toast.LENGTH_SHORT).show()
                    }
                    lastClickTimeMap[v.id] = currentTime
                    
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeEditViewId == v.id) {
                        val deltaX = event.rawX - lastRawX
                        val deltaY = event.rawY - lastRawY
                        
                        if (isResizeModeMap[v.id] == true) {
                            // サイズ変更モード: Y軸の移動量で拡大縮小
                            val scaleFactor = 1.0f + (deltaY / 500f)
                            v.scaleX *= scaleFactor
                            v.scaleY *= scaleFactor
                            // 最小/最大サイズ制限
                            v.scaleX = v.scaleX.coerceIn(0.5f, 3.0f)
                            v.scaleY = v.scaleY.coerceIn(0.5f, 3.0f)
                        } else {
                            // 配置変更モード
                            v.translationX += deltaX
                            v.translationY += deltaY
                        }
                        
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeEditViewId == v.id) {
                        activeEditViewId = View.NO_ID
                        saveButtonPosition(v)
                    }
                }
            }
            true
        }
    }

    private fun resetLayout() {
        buttons.forEach { button ->
            button.translationX = 0f
            button.translationY = 0f
            button.scaleX = 1.0f
            button.scaleY = 1.0f
            saveButtonPosition(button)
        }
        Toast.makeText(this, "Layout Reset", Toast.LENGTH_SHORT).show()
    }

    private fun saveButtonPosition(view: View) {
        val prefs = getSharedPreferences("button_prefs", Context.MODE_PRIVATE)
        val buttonName = resources.getResourceEntryName(view.id)
        prefs.edit().apply {
            putFloat("${buttonName}_tx", view.translationX)
            putFloat("${buttonName}_ty", view.translationY)
            putFloat("${buttonName}_sx", view.scaleX)
            putFloat("${buttonName}_sy", view.scaleY)
            apply()
        }
    }

    private fun loadButtonPositions() {
        val prefs = getSharedPreferences("button_prefs", Context.MODE_PRIVATE)
        buttons.forEach { button ->
            val buttonName = resources.getResourceEntryName(button.id)
            button.translationX = prefs.getFloat("${buttonName}_tx", 0f)
            button.translationY = prefs.getFloat("${buttonName}_ty", 0f)
            button.scaleX = prefs.getFloat("${buttonName}_sx", 1.0f)
            button.scaleY = prefs.getFloat("${buttonName}_sy", 1.0f)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED) {
            val usbIntent = Intent(this, UsbAoaService::class.java).apply {
                putExtras(intent)
            }
            startService(usbIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        if (isUsbBound) {
            unbindService(usbConnection)
            isUsbBound = false
        }
    }

    /**
     * ペアリング済みのホスト(PC)へ接続を要求する
     */
    @RequiresApi(Build.VERSION_CODES.P)
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
        if (isTouchpadMode) {
            reportBuffer[0] = 0x01 // Touchpad Mode
            // Touchpadの座標とボタンは別の場所でセットして送信
            if (usbAoaService?.isAccessoryOpened() == true) {
                usbAoaService?.sendData(reportBuffer)
            }
            return
        }

        reportBuffer[0] = 0x00 // Gamepad Mode
        var buttonsByte = 0
        if (buttonStates[R.id.btn_atk1] == true) buttonsByte = buttonsByte or 0x01
        if (buttonStates[R.id.btn_atk2] == true) buttonsByte = buttonsByte or 0x02
        if (buttonStates[R.id.btn_atk3] == true) buttonsByte = buttonsByte or 0x04
        if (buttonStates[R.id.btn_atk4] == true) buttonsByte = buttonsByte or 0x08
        if (buttonStates[R.id.btn_atk5] == true) buttonsByte = buttonsByte or 0x10
        if (buttonStates[R.id.btn_atk6] == true) buttonsByte = buttonsByte or 0x20
        if (buttonStates[R.id.btn_atk7] == true) buttonsByte = buttonsByte or 0x40
        if (buttonStates[R.id.btn_atk8] == true) buttonsByte = buttonsByte or 0x80
        reportBuffer[1] = buttonsByte.toByte()

        var xAxis = 0
        if (buttonStates[R.id.btn_left] == true) xAxis = -127
        if (buttonStates[R.id.btn_right] == true) xAxis = 127
        reportBuffer[2] = xAxis.toByte()

        var yAxis = 0
        if (buttonStates[R.id.btn_up] == true) yAxis = -127
        if (buttonStates[R.id.btn_down] == true) yAxis = 127
        reportBuffer[3] = yAxis.toByte()

        // Bluetooth (HID) 送信
        if (hidService?.isReady() == true && hidService?.getConnectionState() == BluetoothProfile.STATE_CONNECTED) {
            hidService?.sendReport(1, reportBuffer)
        }

        // USB (AOA) 送信
        if (usbAoaService?.isAccessoryOpened() == true) {
            usbAoaService?.sendData(reportBuffer)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpad() {
        val touchpad = findViewById<View>(R.id.touchpad_area)
        touchpad.setOnTouchListener { _, event ->
            if (!isTouchpadMode) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentTime = System.currentTimeMillis()
                    // 送信頻度を制限（約60fps相当）
                    if (currentTime - lastSendTime < 16) return@setOnTouchListener true

                    val dx = ((event.x - lastTouchX) * 1.5f).toInt().coerceIn(-127, 127)
                    val dy = ((event.y - lastTouchY) * 1.5f).toInt().coerceIn(-127, 127)
                    
                    if (dx != 0 || dy != 0) {
                        sendTouchpadData(dx.toByte(), dy.toByte(), 0)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        lastSendTime = currentTime
                    }
                }
            }
            true
        }

        findViewById<MaterialButton>(R.id.btn_left_click).setOnTouchListener { _, event ->
            handleMouseClick(event, 0x01)
            true
        }
        findViewById<MaterialButton>(R.id.btn_right_click).setOnTouchListener { _, event ->
            handleMouseClick(event, 0x02)
            true
        }
    }

    private fun handleMouseClick(event: MotionEvent, bit: Int) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendTouchpadData(0, 0, bit)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendTouchpadData(0, 0, 0)
        }
    }

    private fun sendTouchpadData(dx: Byte, dy: Byte, buttons: Int) {
        reportBuffer[0] = 0x01 // Touchpad Mode
        reportBuffer[1] = buttons.toByte()
        reportBuffer[2] = dx
        reportBuffer[3] = dy

        // USB AOA 送信
        if (usbAoaService?.isAccessoryOpened() == true) {
            usbAoaService?.sendData(reportBuffer)
        }

        // Bluetooth (HID) 送信 - マウス用 Report ID 2
        if (hidService?.isReady() == true && hidService?.getConnectionState() == BluetoothProfile.STATE_CONNECTED) {
            val mouseBuffer = byteArrayOf(buttons.toByte(), dx, dy)
            hidService?.sendReport(2, mouseBuffer)
        }
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
        // スケールを考慮した当たり判定
        val w = view.width * view.scaleX
        val h = view.height * view.scaleY
        val centerX = view.left + view.translationX + view.width / 2f
        val centerY = view.top + view.translationY + view.height / 2f
        
        val left = centerX - w / 2f
        val right = centerX + w / 2f
        val top = centerY - h / 2f
        val bottom = centerY + h / 2f

        return x >= left && x <= right && y >= top && y <= bottom
    }

    private fun updateButtonFeedback(button: MaterialButton, isPressed: Boolean) {
        button.isPressed = isPressed
        button.alpha = if (isPressed) 0.5f else 1.0f
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }
}
