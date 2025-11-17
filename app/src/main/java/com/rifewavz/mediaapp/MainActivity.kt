package com.rifewavz.mediaapp

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rifewavz.mediaapp.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Method
import java.util.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

class MainActivity : AppCompatActivity() {

    // --------------------------------------------------------------------- //
    //  Bluetooth
    // --------------------------------------------------------------------- //
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // Audio service UUIDs
    private val AUDIO_SERVICE_UUIDS = listOf(
        UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB"),
        UUID.fromString("00001108-0000-1000-8000-00805F9B34FB")
    )

    // Scanning state
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private val scannedAudioDevices = mutableMapOf<String, BluetoothDevice>()
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var classicReceiver: BroadcastReceiver? = null
    private var retryCount = 0
    private val maxRetries = 3

    // --------------------------------------------------------------------- //
    //  Storage
    // --------------------------------------------------------------------- //
    private val sdCardPath = "/storage/1234-5678"
    private val testDir = File(sdCardPath, "test_media_app")

    // --------------------------------------------------------------------- //
    //  Brightness
    // --------------------------------------------------------------------- //
    private lateinit var brightnessSeekBar: SeekBar
    private val MIN_BRIGHTNESS = 0.1f  // 10%
    private val MAX_BRIGHTNESS = 1.0f  // 100%
    private val PREFS_NAME = "MediaAppPrefs"
    private val KEY_BRIGHTNESS = "screen_brightness"
    private val sharedPrefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // --------------------------------------------------------------------- //
    //  Permissions
    // --------------------------------------------------------------------- //
    private val otherPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        Toast.makeText(
            this,
            if (granted) "All normal permissions granted" else "Some normal permissions denied",
            Toast.LENGTH_SHORT
        ).show()
        if (granted) createTestDirectory()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { checkAllFilesAccess() }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth enable cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestWriteSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.System.canWrite(this)) {
            Toast.makeText(this, "System settings permission granted", Toast.LENGTH_SHORT).show()
            restoreBrightness()
        } else {
            Toast.makeText(this, "System settings permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------------------- //
    //  Lifecycle
    // --------------------------------------------------------------------- //
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enableKioskMode()
        checkAllFilesAccess()
        requestNormalPermissions()
        setupButtons()
        setupBrightnessSlider()
        requestSystemBrightnessPermission()
    }

    // --------------------------------------------------------------------- //
    //  Kiosk
    // --------------------------------------------------------------------- //
    private fun enableKioskMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startLockTask()
        }
    }

    // --------------------------------------------------------------------- //
    //  Permission: MANAGE_EXTERNAL_STORAGE
    // --------------------------------------------------------------------- //
    private fun checkAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All-files access granted", Toast.LENGTH_SHORT).show()
                createTestDirectory()
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
                Toast.makeText(
                    this,
                    "Please enable 'All files access' in the next screen",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            createTestDirectory()
        }
    }

    // --------------------------------------------------------------------- //
    //  Permission: normal runtime permissions
    // --------------------------------------------------------------------- //
    private fun requestNormalPermissions() {
        val toRequest = mutableListOf<String>()
        otherPermissions.forEach { perm ->
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(perm)
            }
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(toRequest.toTypedArray())
        } else {
            Toast.makeText(this, "Normal permissions already granted", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------------------- //
    //  Directory creation test
    // --------------------------------------------------------------------- //
    private fun createTestDirectory() {
        try {
            if (!testDir.exists()) {
                val ok = testDir.mkdirs()
                Toast.makeText(
                    this,
                    if (ok) "Directory created: ${testDir.absolutePath}"
                    else "Failed to create directory",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "Directory already exists", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MediaApp", "Directory error", e)
            Toast.makeText(this, "Exception: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --------------------------------------------------------------------- //
    //  UI Button Setup
    // --------------------------------------------------------------------- //
    private fun setupButtons() {
        findViewById<Button>(R.id.btn_toggle_bluetooth).setOnClickListener { showToggleBluetoothDialog() }
        findViewById<Button>(R.id.btn_pair_device).setOnClickListener { showPairDeviceDialog() }
        findViewById<Button>(R.id.btn_forget_device).setOnClickListener { showForgetDeviceDialog() }
        findViewById<Button>(R.id.btn_set_time).setOnClickListener { showSetDateTimeDialog() }
        findViewById<Button>(R.id.btn_set_timezone).setOnClickListener { showSetTimezoneDialog() }

        // Read File first
        findViewById<Button>(R.id.btn_read_audio).setOnClickListener {
            showReadFileDialog("File", arrayOf(".txt"))
        }
        // Write File second
        findViewById<Button>(R.id.btn_write_audio).setOnClickListener {
            showWriteFileDialog("File", ".txt")
        }
    }

    // --------------------------------------------------------------------- //
    //  Brightness Slider
    // --------------------------------------------------------------------- //
    private fun setupBrightnessSlider() {
        brightnessSeekBar = findViewById(R.id.brightness_seekbar)
        brightnessSeekBar.max = 100
        brightnessSeekBar.progress = 100 // default full

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val brightness = MIN_BRIGHTNESS + (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * (progress / 100f)
                    setScreenBrightness(brightness)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun requestSystemBrightnessPermission() {
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            requestWriteSettingsLauncher.launch(intent)
        } else {
            restoreBrightness()
        }
    }

    private fun setScreenBrightness(brightness: Float) {
        // Apply to current window
        val layoutParams = window.attributes
        layoutParams.screenBrightness = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        window.attributes = layoutParams

        // Save to preferences
        sharedPrefs.edit().putFloat(KEY_BRIGHTNESS, brightness).apply()

        // Write to system brightness (persists across reboots)
        if (Settings.System.canWrite(this)) {
            try {
                val systemValue = (brightness * 255).toInt().coerceIn(25, 255)
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, systemValue)
                Log.d("Brightness", "System brightness set to $systemValue")
            } catch (e: Exception) {
                Log.e("Brightness", "Failed to write system brightness", e)
            }
        }
    }

    private fun restoreBrightness() {
        val saved = sharedPrefs.getFloat(KEY_BRIGHTNESS, 1.0f)
        setScreenBrightness(saved)
        brightnessSeekBar.progress = ((saved - MIN_BRIGHTNESS) / (MAX_BRIGHTNESS - MIN_BRIGHTNESS) * 100).toInt()
    }

    // --------------------------------------------------------------------- //
    //  Bluetooth dialogs
    // --------------------------------------------------------------------- //
    private fun showToggleBluetoothDialog() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device has no Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }
        val isEnabled = bluetoothAdapter.isEnabled
        AlertDialog.Builder(this)
            .setTitle("Bluetooth")
            .setMessage("Bluetooth is ${if (isEnabled) "ON" else "OFF"}")
            .setPositiveButton(if (isEnabled) "Turn OFF" else "Turn ON") { _, _ ->
                if (isEnabled) bluetoothAdapter.disable()
                else enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPairDeviceDialog() {
        if (bluetoothAdapter?.isEnabled != true) {
            Toast.makeText(this, "Enable Bluetooth first", Toast.LENGTH_SHORT).show()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth + Location permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        val scanningDialog = AlertDialog.Builder(this)
            .setTitle("Scanning for Audio Devices")
            .setMessage("Starting scan... (Press Stop when done)")
            .setView(ProgressBar(this))
            .setPositiveButton("Stop") { _, _ -> stopScanningAndShowResults() }
            .setCancelable(false)
            .create()

        scanningDialog.setOnShowListener { startForcedScan() }
        scanningDialog.show()
    }

    private fun startForcedScan() {
        if (isScanning) return
        isScanning = true
        scannedAudioDevices.clear()
        retryCount = 0

        Log.d("BluetoothScan", "Starting forced hybrid scan...")

        startClassicDiscoveryWithRetry()
        startBleScanWithRetry()
    }

    private fun startClassicDiscoveryWithRetry(): Boolean {
        if (retryCount >= maxRetries) {
            Log.e("BluetoothScan", "Classic discovery failed after $maxRetries attempts")
            return false
        }

        val success = bluetoothAdapter?.startDiscovery() == true
        if (success) {
            Log.d("BluetoothScan", "Classic discovery started (attempt ${retryCount + 1})")
            setupClassicReceiver()
        } else {
            Log.w("BluetoothScan", "Classic discovery failed (attempt ${retryCount + 1})")
            retryCount++
            scanHandler.postDelayed({ startClassicDiscoveryWithRetry() }, 3000)
        }
        return success
    }

    private fun setupClassicReceiver() {
        classicReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return@let
                            val name = it.name ?: "Unknown"
                            if (isAudioDeviceClassic(it) || isAudioName(name)) {
                                addDeviceIfNew(it, "Classic: $name")
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d("BluetoothScan", "Classic discovery finished")
                        if (isScanning && scannedAudioDevices.isEmpty() && retryCount < maxRetries) {
                            retryCount++
                            scanHandler.postDelayed({ startClassicDiscoveryWithRetry() }, 2000)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(classicReceiver, filter)
    }

    private fun startBleScanWithRetry() {
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bleScanner == null) {
            Log.w("BluetoothScan", "BLE scanner not available")
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
                val name = device.name ?: "Unknown"
                if (isAudioDeviceBle(result) || isAudioName(name)) {
                    addDeviceIfNew(device, "BLE: $name")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BluetoothScan", "BLE scan failed: $errorCode")
                if (retryCount < maxRetries) {
                    retryCount++
                    scanHandler.postDelayed({ startBleScanWithRetry() }, 5000)
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        bleScanner?.startScan(null, settings, scanCallback)
            ?: Log.w("BluetoothScan", "BLE startScan failed")
    }

    private fun isAudioDeviceClassic(device: BluetoothDevice): Boolean {
        val btClass = device.bluetoothClass ?: return false
        return when (btClass.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> true
            else -> false
        }
    }

    private fun isAudioDeviceBle(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return false
        val uuids = record.serviceUuids ?: return false
        return uuids.any { AUDIO_SERVICE_UUIDS.contains(it.uuid) }
    }

    private fun isAudioName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("speaker") || lower.contains("headset") || lower.contains("earphone") || lower.contains("audio")
    }

    private fun addDeviceIfNew(device: BluetoothDevice, source: String) {
        val addr = device.address
        if (scannedAudioDevices[addr] == null) {
            scannedAudioDevices[addr] = device
            Toast.makeText(this, "Found $source: ${device.name ?: "Unknown"} ($addr)", Toast.LENGTH_SHORT).show()
            Log.d("BluetoothScan", "NEW DEVICE: $source -> ${device.name} ($addr)")
        }
    }

    private fun stopScanningAndShowResults() {
        stopScanning()
        showScannedDevicesDialog()
    }

    private fun stopScanning() {
        if (!isScanning) return
        isScanning = false

        scanCallback?.let { bleScanner?.stopScan(it) }
        classicReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            classicReceiver = null
        }
        bluetoothAdapter?.cancelDiscovery()
        scanHandler.removeCallbacksAndMessages(null)
        Log.d("BluetoothScan", "Scan stopped. Found ${scannedAudioDevices.size} devices")
    }

    private fun showScannedDevicesDialog() {
        if (scannedAudioDevices.isEmpty()) {
            Toast.makeText(this, "No audio devices found. Ensure location is enabled.", Toast.LENGTH_LONG).show()
            return
        }

        val devicesList = scannedAudioDevices.values.toList()
        val deviceNames = devicesList.map { it.name ?: "Unknown (${it.address})" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Found Audio Devices (${devicesList.size})")
            .setItems(deviceNames) { _, which ->
                val device = devicesList[which]
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Connect permission missing", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                pairWithDevice(device)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pairWithDevice(device: BluetoothDevice) {
        try {
            device.createBond()
            Toast.makeText(this, "Pairing with ${device.name}...", Toast.LENGTH_SHORT).show()
            Log.d("BluetoothPair", "Pairing initiated with ${device.name}")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to pair: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("BluetoothPair", "Pair error", e)
        }
    }

    private fun showForgetDeviceDialog() {
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        if (bonded.isEmpty()) {
            Toast.makeText(this, "No bonded devices", Toast.LENGTH_SHORT).show()
            return
        }
        val names = bonded.map { it.name ?: "Unknown" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Forget device")
            .setItems(names) { _, which ->
                val dev = bonded.elementAt(which)
                try {
                    val method: Method = dev.javaClass.getMethod("removeBond")
                    method.invoke(dev)
                    Toast.makeText(this, "Forgot ${dev.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to forget device", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    // --------------------------------------------------------------------- //
    //  Date / Time / Timezone dialogs
    // --------------------------------------------------------------------- //
    private fun showSetDateTimeDialog() {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val cal = Calendar.getInstance().apply {
                    set(y, m, d, h, min, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                try {
                    alarm.setTime(cal.timeInMillis)
                    Toast.makeText(this, "Time set", Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Toast.makeText(this, "SET_TIME permission missing", Toast.LENGTH_SHORT).show()
                }
            }, c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], true).show()
        }, c[Calendar.YEAR], c[Calendar.MONTH], c[Calendar.DAY_OF_MONTH]).show()
    }

    private fun showSetTimezoneDialog() {
        val zones = TimeZone.getAvailableIDs()
        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, zones)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val current = TimeZone.getDefault().id
        val idx = zones.indexOf(current).coerceAtLeast(0)
        spinner.setSelection(idx)

        AlertDialog.Builder(this)
            .setTitle("Select Timezone")
            .setView(spinner)
            .setPositiveButton("Set") { _, _ ->
                val tz = spinner.selectedItem as String
                setSystemTimezone(tz)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setSystemTimezone(tzId: String) {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarm.setTimeZone(tzId)
            Toast.makeText(this, "Timezone to $tzId", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------------------- //
    //  File read / write helpers (.txt only)
    // --------------------------------------------------------------------- //
    private fun showReadFileDialog(type: String, extensions: Array<String>) {
        val files = testDir.listFiles { _, name -> extensions.any { name.endsWith(it, ignoreCase = true) } } ?: emptyArray()
        if (files.isEmpty()) {
            Toast.makeText(this, "No .txt files found", Toast.LENGTH_SHORT).show()
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select File to Read")
            .setItems(names) { _, which -> readFile(files[which]) }
            .show()
    }

    private fun readFile(file: File) {
        try {
            val content = FileInputStream(file).use { it.bufferedReader().readText() }
            Toast.makeText(this, "Read ${file.name}\n$content", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to read ${file.name}: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showWriteFileDialog(type: String, extension: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Enter filename (e.g. note.txt)"
        }
        AlertDialog.Builder(this)
            .setTitle("Write File")
            .setView(input)
            .setPositiveButton("Write") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val file = if (name.endsWith(".txt", ignoreCase = true)) File(testDir, name) else File(testDir, "$name.txt")
                    writeFile(file)
                } else {
                    Toast.makeText(this, "Filename required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun writeFile(file: File) {
        try {
            // Ensure directory exists
            if (!file.parentFile?.exists()!!) {
                file.parentFile?.mkdirs()
            }
            FileOutputStream(file).use { it.write("Test content written on ${System.currentTimeMillis()}".toByteArray()) }
            Toast.makeText(this, "Wrote to ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to write ${file.name}: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // --------------------------------------------------------------------- //
    //  Clean-up
    // --------------------------------------------------------------------- //
    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        stopLockTask()
    }
}
