package com.example.joytouch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationBarView
import org.json.JSONObject
import java.io.IOException

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

    // ナビゲーションバー（横画面: TopNav, 縦画面: SideNav）
    private lateinit var topNav: NavigationBarView
    private lateinit var sideNav: NavigationBarView

    // 各ボタンの編集サブモード（true: サイズ変更, false: 位置移動）
    private val isResizeModeMap = mutableMapOf<Int, Boolean>()
    // ダブルタップ判定用
    private val lastClickTimeMap = mutableMapOf<Int, Long>()

    // JSON レイアウトのエクスポート・インポート用ランチャー
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { exportLayoutToJson(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { importLayoutFromJson(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 画面の向きを復元（手動トグルによる向き設定を優先）
        val savedOrientation = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("screen_orientation", "landscape")
        requestedOrientation = if (savedOrientation == "portrait") {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

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

        // ナビゲーションメニューの設定（横画面: topNav, 縦画面: sideNav）
        topNav = findViewById(R.id.top_navigation)
        sideNav = findViewById(R.id.side_navigation)

        val navListener = NavigationBarView.OnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_mode_toggle -> {
                    if (isEditMode) {
                        Toast.makeText(this, "Cannot switch mode in Edit Mode", Toast.LENGTH_SHORT).show()
                        return@OnItemSelectedListener false
                    }
                    isTouchpadMode = !isTouchpadMode
                    findViewById<View>(R.id.touchpad_area).visibility = if (isTouchpadMode) View.VISIBLE else View.GONE
                    sendHidReport()
                    updateNavTitlesAndIcons()
                    val modeText = if (isTouchpadMode) getString(R.string.mode_touchpad) else getString(R.string.mode_gamepad)
                    Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
                    false
                }
                R.id.nav_orientation -> {
                    val newOrientation = if (isLandscape()) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    requestedOrientation = newOrientation
                    getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
                        .putString("screen_orientation", if (newOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) "portrait" else "landscape")
                        .apply()
                    val orientationText = if (newOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                        getString(R.string.orientation_portrait)
                    } else {
                        getString(R.string.orientation_landscape)
                    }
                    Toast.makeText(this, orientationText, Toast.LENGTH_SHORT).show()
                    false
                }
                R.id.nav_edit -> {
                    isEditMode = !isEditMode
                    setupMode()
                    updateNavTitlesAndIcons()
                    false
                }
                R.id.nav_connect -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        connectToHost()
                    } else {
                        Toast.makeText(this, "Bluetooth HID is not supported on this Android version", Toast.LENGTH_LONG).show()
                    }
                    false // 選択状態にはしない
                }
                R.id.nav_more -> {
                    val isLandscape = isLandscape()
                    val activeBar = if (isLandscape) topNav else sideNav
                    val anchor = activeBar.findViewById<View>(R.id.nav_more) ?: activeBar
                    showOverflowMenu(anchor)
                    false // 選択状態にはしない
                }
                else -> false
            }
        }

        topNav.setOnItemSelectedListener(navListener)
        sideNav.setOnItemSelectedListener(navListener)
        topNav.setOnItemReselectedListener { item -> navListener.onNavigationItemSelected(item) }
        sideNav.setOnItemReselectedListener { item -> navListener.onNavigationItemSelected(item) }

        // 既存の横画面設定があれば移行
        migrateOldPrefsIfNeeded()

        updateOrientation(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

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

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.overflow_menu, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_layout -> {
                    showLayoutDialog()
                    true
                }
                R.id.menu_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun updateOrientation(isLandscape: Boolean) {
        val mainLayout = findViewById<View>(R.id.main_layout)
        if (isLandscape) {
            topNav.visibility = View.VISIBLE
            sideNav.visibility = View.GONE
            mainLayout.setPadding(dpToPx(48), 0, dpToPx(48), 0)
        } else {
            topNav.visibility = View.GONE
            sideNav.visibility = View.VISIBLE
            mainLayout.setPadding(0, 0, 0, 0)
        }
        updateNavTitlesAndIcons()
    }

    private fun updateNavTitlesAndIcons() {
        val isLand = isLandscape()

        val modeTitle = if (isTouchpadMode) getString(R.string.mode_touchpad) else getString(R.string.mode_gamepad)
        val modeIcon = if (isTouchpadMode) android.R.drawable.ic_menu_mylocation else android.R.drawable.ic_menu_compass

        val orientationTitle = if (isLand) getString(R.string.orientation_landscape) else getString(R.string.orientation_portrait)

        val editTitle = if (isEditMode) getString(R.string.mode_play) else getString(R.string.mode_edit)
        val editIcon = if (isEditMode) android.R.drawable.ic_media_play else android.R.drawable.ic_menu_edit

        listOf(topNav, sideNav).forEach { nav ->
            nav.menu.findItem(R.id.nav_mode_toggle)?.apply {
                title = modeTitle
                setIcon(modeIcon)
            }
            nav.menu.findItem(R.id.nav_orientation)?.apply {
                title = orientationTitle
                setIcon(R.drawable.ic_screen_rotation)
            }
            nav.menu.findItem(R.id.nav_edit)?.apply {
                title = editTitle
                setIcon(editIcon)
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientation(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        loadButtonPositions()
        hideSystemUI()
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun getPrefsName(isLand: Boolean = isLandscape()): String =
        if (isLand) "button_prefs_land" else "button_prefs_port"

    private fun getPrefs(): android.content.SharedPreferences =
        getSharedPreferences(getPrefsName(), Context.MODE_PRIVATE)

    private fun migrateOldPrefsIfNeeded() {
        val oldPrefs = getSharedPreferences("button_prefs", Context.MODE_PRIVATE)
        val landPrefs = getSharedPreferences("button_prefs_land", Context.MODE_PRIVATE)
        if (landPrefs.all.isEmpty() && oldPrefs.all.isNotEmpty()) {
            landPrefs.edit().apply {
                oldPrefs.all.forEach { (key, value) ->
                    if (value is Float) putFloat(key, value)
                }
                apply()
            }
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
        val mode = if (isLandscape()) getString(R.string.orientation_landscape) else getString(R.string.orientation_portrait)
        Toast.makeText(this, getString(R.string.msg_reset_done, mode), Toast.LENGTH_SHORT).show()
    }

    private fun exportPrefsToJson(prefsName: String): JSONObject {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val isCurrent = (prefsName == "button_prefs_land" && isLandscape()) ||
                        (prefsName == "button_prefs_port" && !isLandscape())
        val obj = JSONObject()
        buttons.forEach { button ->
            val name = resources.getResourceEntryName(button.id)
            val tx = if (isCurrent) button.translationX else prefs.getFloat("${name}_tx", 0f)
            val ty = if (isCurrent) button.translationY else prefs.getFloat("${name}_ty", 0f)
            val sx = if (isCurrent) button.scaleX else prefs.getFloat("${name}_sx", 1.0f)
            val sy = if (isCurrent) button.scaleY else prefs.getFloat("${name}_sy", 1.0f)

            val btnObj = JSONObject().apply {
                put("tx", tx.toDouble())
                put("ty", ty.toDouble())
                put("sx", sx.toDouble())
                put("sy", sy.toDouble())
            }
            obj.put(name, btnObj)
        }
        return obj
    }

    private fun exportLayoutToJson(uri: Uri) {
        try {
            val rootJson = JSONObject().apply {
                put("app", "joytouch")
                put("version", 2)
                put("landscape", exportPrefsToJson("button_prefs_land"))
                put("portrait", exportPrefsToJson("button_prefs_port"))
            }

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, getString(R.string.msg_export_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to export layout", e)
            Toast.makeText(this, "${getString(R.string.msg_export_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importJsonToPrefs(buttonsObj: JSONObject, prefsName: String) {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        prefs.edit().apply {
            buttons.forEach { button ->
                val name = resources.getResourceEntryName(button.id)
                val btnData = buttonsObj.optJSONObject(name)
                if (btnData != null) {
                    val tx = btnData.optDouble("tx", 0.0).toFloat()
                    val ty = btnData.optDouble("ty", 0.0).toFloat()
                    val sx = btnData.optDouble("sx", 1.0).toFloat()
                    val sy = btnData.optDouble("sy", 1.0).toFloat()

                    putFloat("${name}_tx", tx)
                    putFloat("${name}_ty", ty)
                    putFloat("${name}_sx", sx)
                    putFloat("${name}_sy", sy)
                }
            }
            apply()
        }
    }

    private fun importLayoutFromJson(uri: Uri) {
        try {
            val jsonStr = contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader(Charsets.UTF_8).readText()
            } ?: throw IOException("Could not read file")

            val rootJson = JSONObject(jsonStr)

            val landObj = rootJson.optJSONObject("landscape")
            val portObj = rootJson.optJSONObject("portrait")
            val legacyButtonsObj = rootJson.optJSONObject("buttons")

            if (landObj == null && portObj == null && legacyButtonsObj == null) {
                throw IllegalArgumentException("Missing buttons layout data")
            }

            if (landObj != null) {
                importJsonToPrefs(landObj, "button_prefs_land")
            }
            if (portObj != null) {
                importJsonToPrefs(portObj, "button_prefs_port")
            }
            if (legacyButtonsObj != null && landObj == null) {
                // v1 形式の後方互換性（横画面用として取り込む）
                importJsonToPrefs(legacyButtonsObj, "button_prefs_land")
            }

            // 現在の向きに合わせて画面上のボタン位置を即座に再読み込み
            loadButtonPositions()

            Toast.makeText(this, getString(R.string.msg_import_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to import layout", e)
            Toast.makeText(this, "${getString(R.string.msg_import_failed)}: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveButtonPosition(view: View) {
        val prefs = getPrefs()
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
        val prefs = getPrefs()
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

    private fun showLayoutDialog() {
        val options = arrayOf(
            getString(R.string.btn_export),
            getString(R.string.btn_import),
            getString(R.string.btn_reset)
        )
        val mode = if (isLandscape()) getString(R.string.orientation_landscape) else getString(R.string.orientation_portrait)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.layout_menu_title_with_mode, mode))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportLauncher.launch("joytouch_layout.json")
                    1 -> importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.btn_reset)
                            .setMessage(getString(R.string.msg_reset_confirm, mode))
                            .setPositiveButton(R.string.about_ok) { _, _ -> resetLayout() }
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .show()
                    }
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
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
