package ru.liferych.bms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

private const val FRAME_START: Byte = 0xA5.toByte()
private const val REQUEST_ADDRESS: Byte = 0x40
private const val DATA_LEN: Byte = 0x08
private const val BLE_LOG_TAG = "LiferychBmsBle"

private const val DEFAULT_ADMIN_SERVER_BASE_URL = "http://192.168.70.142:3000"
private const val LOCAL_UPLOAD_PATH = "/api/upload.php"
private const val LOCAL_CONFIG_UPLOAD_PATH = "/api/config_upload.php"
private const val LOCAL_SERVICE_REPORT_PATH = "/api/v1/service-report"
private const val SERVER_WARRANTY_URL = "http://dimond44.xsph.ru/api/warranty_submit.php"
private const val SERVER_WARRANTY_LIST_URL = "http://dimond44.xsph.ru/api/warranty_list.php"
private const val SERVER_WARRANTY_UPDATE_URL = "http://dimond44.xsph.ru/api/warranty_update.php"
private const val SERVER_API_KEY = "change_me_api_key_2026"
private const val APP_VERSION = "0.2.29"
private const val REMOTE_WRITE_POLL_MS = 8000L
private const val UPLOAD_INTERVAL_MS = 15000L
private const val CONFIG_UPLOAD_INTERVAL_MS = 300000L
private const val CONFIG_PREFS_NAME = "bms_config_cache"
private const val CONFIG_AUTO_READ_DELAY_MS = 12000L
private const val TEST_AUTO_WRITE_SOC_ON_CONNECT = false
private const val TEST_SOC_PERCENT_ON_CONNECT = 90.0
private const val TEST_SOC_REGISTER_ADDR = 0x0116
private const val HARDWARE_FAMILY_STANDARD = "standard"
private const val HARDWARE_FAMILY_DL_RED = "dl_red"
private const val HARDWARE_FAMILY_R10K = "r10k"
private val BMS_VERSION_TOKENS = listOf("R24TK", "R24TH", "R10K")
private const val DALY_BATTERY_CODE_CMD = 0x57
private const val DALY_BATTERY_CODE_FRAMES = 5
private const val DALY_HW_VERSION_CMD = 0x63
private const val DALY_HW_VERSION_FRAMES = 5
private val DEBUG_A5_SKIP_CMDS = setOf(
    0x10, 0x11, 0x12,
    0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56
)
private const val DALY_SN_CODE_START = 0x0057
private const val DALY_SN_CODE_END = 0x005D
private const val DALY_SN_CODE_COUNT = DALY_SN_CODE_END - DALY_SN_CODE_START + 1
private const val DALY_NOMINAL_CAPACITY_HI_REG = 0x0109
private const val DALY_NOMINAL_CAPACITY_LO_REG = 0x010A
private const val DALY_REMAINING_CAPACITY_HI_REG = 0x010B
private const val DALY_REMAINING_CAPACITY_LO_REG = 0x010C
private const val DALY_PASSWORD_REG_0 = 0x0126
private const val SERVICE_SETTINGS_PASSWORD = "113355"
private val R10K_SKIPPED_TEMPLATE_KEYS = setOf(
    "soc_calibration_0",
    "soc_calibration_100",
    "balance_start_voltage",
    "balance_stop_voltage",
    "balance_delta"
)
private const val TEST_BATTERY_ADDRESS = "TEST_BATTERY_12V_105AH"

private val DALY_CHAR_UUID_CANDIDATES = setOf(
    UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"),
    UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
)

private val CLIENT_CHARACTERISTIC_CONFIG =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class DalyData(
    var voltage: Double? = null,
    var current: Double? = null,
    var soc: Double? = null,
    var remainingAh: Double? = null,
    var estimatedFullAh: Double? = null,
    var maxCellV: Double? = null,
    var maxCellNo: Int? = null,
    var minCellV: Double? = null,
    var minCellNo: Int? = null,
    var cellDiffV: Double? = null,
    var maxTemp: Int? = null,
    var minTemp: Int? = null,
    var chargeMos: Boolean? = null,
    var dischargeMos: Boolean? = null,
    var cellCount: Int? = null,
    var tempCount: Int? = null,
    var chargerConnected: Boolean? = null,
    var loadConnected: Boolean? = null,
    var cycles: Int? = null,
    var lastUpdatedAt: Long = 0L,
    val cells: MutableMap<Int, Double> = sortedMapOf(),
    val temps: MutableMap<Int, Int> = sortedMapOf(),
    val balancingCells: MutableSet<Int> = sortedSetOf(),
    val raw: MutableMap<String, String> = linkedMapOf(),
    val errors: MutableList<String> = mutableListOf()
)

data class ConfigReadRequest(
    val name: String,
    val slave: Int,
    val start: Int,
    val count: Int
)

data class RemoteWriteCommand(
    val id: Int,
    val key: String,
    val label: String,
    val register: Int,
    val rawValue: Int,
    val value: Double,
    val scale: Double,
    val offset: Double,
    val unit: String,
    val localOnly: Boolean = false,
    val displayValue: Double? = null,
    val writeFrames: List<ByteArray>? = null
)

data class ServiceWriteResult(
    val key: String,
    val label: String,
    val expected: Double?,
    val actual: Double?,
    val unit: String,
    val ok: Boolean,
    val error: String? = null
)

data class BmsConfigTemplate(
    val id: String,
    val version: Int,
    val chemistry: String,
    val supportedSeries: Set<Int>,
    val parameters: List<BmsTemplateParameter>
)

data class BmsTemplateParameter(
    val key: String,
    val label: String,
    val register: Int,
    val scale: Double,
    val offset: Double,
    val unit: String,
    val tolerance: Double,
    val enforcement: String,
    val expected: Double?,
    val expectedBySeries: Map<Int, Double>,
    val reason: String?,
    val skipFor: Set<String> = emptySet()
)

data class TemplateCheckItem(
    val key: String,
    val label: String,
    val expected: Double?,
    val actual: Double?,
    val unit: String,
    val tolerance: Double,
    val reason: String? = null
)

data class TemplateCheckResult(
    val templateId: String,
    val templateVersion: Int,
    val status: String,
    val checkedAt: Long,
    val seriesCount: Int?,
    val mismatches: List<TemplateCheckItem> = emptyList(),
    val missing: List<TemplateCheckItem> = emptyList(),
    val unverified: List<TemplateCheckItem> = emptyList(),
    val hardwareFamily: String = HARDWARE_FAMILY_STANDARD
)

data class SavedBattery(
    val address: String,
    val bluetoothName: String,
    val customName: String,
    val soc: Double?,
    val capacityAh: Double?,
    val lastSeenAt: Long
)

private class SectionSwipeScrollView(
    context: Context,
    private val onSectionSwipe: (direction: Int) -> Unit
) : ScrollView(context) {
    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                val threshold = 72f * resources.displayMetrics.density
                if (kotlin.math.abs(dx) >= threshold &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f
                ) {
                    onSectionSwipe(if (dx < 0f) 1 else -1)
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

private class BatteryIconView(
    context: Context,
    private val iconColor: Int,
    private val iconSizeDp: Float = 23f
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width.toFloat(), height.toFloat(), iconSizeDp * resources.displayMetrics.density) / 24f
        val offsetX = (width - 24f * scale) / 2f
        val offsetY = (height - 24f * scale) / 2f
        fun x(value: Float) = offsetX + value * scale
        fun y(value: Float) = offsetY + value * scale
        stroke.strokeWidth = 1.8f * scale

        canvas.drawRoundRect(
            x(3.5f),
            y(7f),
            x(18f),
            y(17f),
            2f * scale,
            2f * scale,
            stroke
        )
        canvas.drawLine(x(19.5f), y(10f), x(19.5f), y(14f), stroke)
        canvas.drawLine(x(9f), y(9.8f), x(9f), y(14.2f), stroke)
        canvas.drawLine(x(6.8f), y(12f), x(11.2f), y(12f), stroke)
    }
}

private class BluetoothIconView(
    context: Context,
    private val iconColor: Int
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        val offsetX = (width - 24f * scale) / 2f
        val offsetY = (height - 24f * scale) / 2f
        // Canvas scaling also scales the stroke, so keep it in SVG coordinates.
        stroke.strokeWidth = 1.8f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawPath(Path().apply {
            moveTo(12f, 3f)
            lineTo(12f, 21f)
            lineTo(18f, 15f)
            lineTo(12f, 12f)
            lineTo(18f, 9f)
            close()
            moveTo(6f, 7f)
            lineTo(12f, 12f)
            lineTo(6f, 17f)
        }, stroke)
        canvas.restore()
    }
}

private class QrIconView(
    context: Context,
    private val iconColor: Int
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        val offsetX = (width - 24f * scale) / 2f
        val offsetY = (height - 24f * scale) / 2f
        // Canvas scaling also scales the stroke, so keep it in SVG coordinates.
        stroke.strokeWidth = 1.8f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawRect(4f, 4f, 10f, 10f, stroke)
        canvas.drawRect(14f, 4f, 20f, 10f, stroke)
        canvas.drawRect(4f, 14f, 10f, 20f, stroke)
        listOf(
            floatArrayOf(16f, 14f, 17.5f, 14f),
            floatArrayOf(14f, 16f, 16f, 16f),
            floatArrayOf(18f, 16f, 20f, 16f),
            floatArrayOf(14f, 18f, 15.5f, 18f),
            floatArrayOf(17.5f, 18f, 20f, 18f),
            floatArrayOf(18f, 14f, 18f, 15.5f),
            floatArrayOf(20f, 18f, 20f, 20f)
        ).forEach { canvas.drawLine(it[0], it[1], it[2], it[3], stroke) }
        canvas.restore()
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalGetImage::class])
class MainActivity : ComponentActivity() {
    private lateinit var configPrefs: SharedPreferences
    private val batteryPrefs by lazy {
        getSharedPreferences("saved_batteries", MODE_PRIVATE)
    }
    private val serverPrefs by lazy {
        getSharedPreferences("admin_server_settings", MODE_PRIVATE)
    }
    private val servicePrefs by lazy {
        getSharedPreferences("service_profile", MODE_PRIVATE)
    }
    private var bleDebugText: String = ""
    private val WARRANTY_MEDIA_REQUEST_CODE = 4501
    private val PROFILE_AVATAR_REQUEST_CODE = 4502
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002
    private val warrantyMediaUris: MutableList<Uri> = mutableListOf()
    private var qrCodeValue: String = ""
    private var qrProcessing = false
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor by lazy { Executors.newSingleThreadExecutor() }
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_DATA_MATRIX
                )
                .build()
        )
    }
    private var warrantyNameEdit: EditText? = null
    private var warrantyPhoneEdit: EditText? = null
    private var warrantyModelEdit: EditText? = null
    private var warrantyProblemEdit: EditText? = null
    private var warrantyMediaText: TextView? = null
    private var warrantyStatusText: TextView? = null
    private var warrantyListLayout: LinearLayout? = null
    private var supportPrefs: SharedPreferences? = null
    private var editingWarrantyLocalId: String? = null
    private var supportMode: String = "new"
    private var currentTab: String = "main"
    private var manageSection: String = "general"
    private var screenState: String = "splash"
    private val templateChecksByBms: MutableMap<String, TemplateCheckResult> = mutableMapOf()
    private var latestTemplateCheck: TemplateCheckResult? = null
    private var testSocWriteDoneForConnection: Boolean = false
    private val red = Color.rgb(255, 196, 0)
    private val redDark = Color.rgb(255, 183, 0)
    private val green = Color.rgb(28, 160, 55)
    private val orange = Color.rgb(235, 103, 20)
    private val bg = Color.WHITE
    private val interBase by lazy {
        Typeface.createFromAsset(assets, "fonts/InterVariable.ttf")
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private val scanRssi = linkedMapOf<String, Int>()
    private val scanNames = linkedMapOf<String, String>()
    private var searchIdQuery: String = ""
    private val deviceStatusViews = linkedMapOf<String, TextView>()
    private val deviceActionViews = linkedMapOf<String, TextView>()
    private var returnToBatteriesAfterConnect = false
    private val rxBuffer = ArrayList<Byte>()
    private val configModbusBuffer = ArrayList<Byte>()
    private val data = DalyData()
    private val configRegisters: MutableMap<Int, Int> = sortedMapOf()
    private val configRaw: MutableMap<String, String> = linkedMapOf()
    private val configReadQueue = java.util.ArrayDeque<ConfigReadRequest>()
    private var activeConfigRead: ConfigReadRequest? = null
    private var configReadInProgress: Boolean = false
    private var configAutoReadStartedForConnection: Boolean = false
    private var configLoadedFromCache: Boolean = false
    private var configLastSavedAt: Long = 0L
    private var lastConfigUploadAt: Long = 0L
    private var configUploading: Boolean = false
    private var lastConfigStatus: String = "not_read"
    private var remoteWriteInProgress: Boolean = false
    private var remoteWriteAwaitingVerify: Boolean = false
    private var pendingRemoteWrite: RemoteWriteCommand? = null
    private var lastRemoteWritePollAt: Long = 0L
    private var serviceTemplateKey: String = "12v"
    private var serviceCapacityText: String = ""
    private var serviceWriteQueue = java.util.ArrayDeque<RemoteWriteCommand>()
    private val serviceWriteResults: MutableList<ServiceWriteResult> = mutableListOf()
    private var serviceWriteActive: Boolean = false
    private var serviceWriteCapacityAh: Double? = null
    private var pendingServiceWriteFinalVerify: Boolean = false
    private var serviceVerifyNeedsReadAfterCurrent: Boolean = false
    private var bluetoothIdValue: TextView? = null
    private var bmsSnValue: TextView? = null
    private var bmsVersionValue: TextView? = null
    private var serviceVersionDebugView: TextView? = null
    private var debugDumpView: TextView? = null
    private var serviceUploadStatusText: TextView? = null
    private var dashboardUploadStatusText: TextView? = null
    private var pendingFirstTelemetryUpload = false
    private val remoteWritePollRunnable = object : Runnable {
        override fun run() {
            if (bluetoothGatt == null || writeCharacteristic == null || !polling) return
            fetchAndApplyRemoteWrites()
            mainHandler.postDelayed(this, REMOTE_WRITE_POLL_MS)
        }
    }
    private var lastKnownSoc: Double? = null
    private var lastKnownChargeMosTextText: String? = null
    private var lastKnownDischargeMosTextText: String? = null
    private var polling = false
    private var pollLoopToken = 0
    private var runtimeCommands: List<Int> = emptyList()
    private var runtimeCommandIndex = 0
    private var pendingRuntimeCommand: Int? = null
    private var runtimeExpectedFrames = 1
    private val runtimeReceivedGroups: MutableSet<Int> = mutableSetOf()
    private val batteryCodeFrames: MutableMap<Int, String> = sortedMapOf()
    private val batteryCodeFrameHex: MutableMap<Int, String> = sortedMapOf()
    private val hwVersionFrames: MutableMap<Int, String> = sortedMapOf()
    private val hwVersionFrameHex: MutableMap<Int, String> = sortedMapOf()
    private val debugA5Frames: MutableMap<Int, MutableList<String>> = sortedMapOf()
    private var debugA5ScanActive = false
    private var debugA5ScanToken = 0
    private var debugA5ScanIndex = 0
    private var debugA5ScanCommands: List<Int> = emptyList()
    private var debugA5ScanStatus: String = ""
    private var negotiatedMtu = 23
    private var servicesDiscoveryStarted = false
    private var selectedAddress: String? = null
    private var selectedDeviceName: String = ""
    private var lastBatterySnapshotSavedAt: Long = 0L
    private var lastUploadAt: Long = 0L
    private var uploading: Boolean = false
    private var lastUploadStatus: String = "Данные еще не отправлялись"
    private var lastLogId: Int? = null
    private var lastErrorSignature: String = ""
    private var lastChargeMos: Boolean? = null
    private var lastDischargeMos: Boolean? = null
    private val localEvents: MutableList<String> = mutableListOf()
    private val localEventDetails: MutableList<String> = mutableListOf()
    private val historicalRawResponses: MutableList<String> = mutableListOf()
    private val dalyHistoricalEvents: MutableList<String> = mutableListOf()
    private var historicalReadInProgress: Boolean = false
    private var historicalLastStatus: String = "Еще не читали"

    private lateinit var statusText: TextView
    private lateinit var deviceListLayout: LinearLayout

    private lateinit var socGauge: SocGaugeView
    private lateinit var socProgress: ProgressBar
    private lateinit var voltageValue: TextView
    private lateinit var currentValue: TextView
    private lateinit var remainingValue: TextView
    private lateinit var chargeMosValue: TextView
    private lateinit var dischargeMosValue: TextView
    private lateinit var balanceValue: TextView
    private lateinit var heatValue: TextView
    private lateinit var batteryInfoText: TextView
    private lateinit var deviceNameValue: TextView
    private lateinit var cycleCountValue: TextView
    private lateinit var t1Text: TextView
    private lateinit var t2Text: TextView
    private lateinit var cellsLayout: LinearLayout
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var connectionText: TextView
    private lateinit var manageContentLayout: LinearLayout
    private lateinit var qtcContentLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                getSharedPreferences("crash_log", MODE_PRIVATE)
                    .edit()
                    .putString("last_crash", e.stackTraceToString())
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        super.onCreate(savedInstanceState)
        configPrefs = getSharedPreferences(CONFIG_PREFS_NAME, MODE_PRIVATE)
        supportPrefs = getSharedPreferences("warranty_requests_cache", MODE_PRIVATE)
        ensureTestBattery()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        requestBlePermissions()
        showSplashScreen()
        val debugAddress = intent.getStringExtra("debug_connect_address")
        val isDebuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable && !debugAddress.isNullOrBlank() && hasBlePermissions()) {
            val saved = loadSavedBatteries().firstOrNull { it.address == debugAddress }
                ?: SavedBattery(
                    address = debugAddress,
                    bluetoothName = "Daly BMS",
                    customName = "Daly BMS",
                    soc = null,
                    capacityAh = null,
                    lastSeenAt = 0L
                )
            val displayName = saved.customName.ifBlank {
                saved.bluetoothName.ifBlank { saved.address }
            }
            mainHandler.postDelayed({ connectSavedBattery(saved, displayName) }, 400)
        }
    }

    private fun showSplashScreen() {
        screenState = "splash"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), 0, dp(20), dp(18))
            setBackgroundColor(Color.WHITE)
        }

        val launchTop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(52), 0, 0)
        }
        root.addView(
            launchTop,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.liferych_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = dp(2).toFloat()
        }
        launchTop.addView(logo, LinearLayout.LayoutParams(dp(272), dp(258)))

        val tagLine = TextView(this).apply {
            text = "ДЕРЖИТ ЗАРЯД"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            textSize = 18f
            letterSpacing = 0.12f
            typeface = interFont(580)
        }
        launchTop.addView(
            tagLine,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        )
        if (isServiceApp()) {
            launchTop.addView(
                TextView(this).apply {
                    text = "СЕРВИС"
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(16, 17, 20))
                    textSize = 14f
                    letterSpacing = 0.18f
                    typeface = interFont(760)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            )
        }

        val connect = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(255, 214, 81), Color.rgb(255, 196, 0))
            ).apply { cornerRadius = dp(14).toFloat() }
            elevation = dp(5).toFloat()
            setOnClickListener {
                showLoadingScreen()
                startScan()
            }
        }
        connect.addView(
            BluetoothIconView(this, Color.rgb(16, 17, 20)),
            LinearLayout.LayoutParams(dp(34), dp(42))
        )
        connect.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Подключить батарею"
                textSize = 17f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(720)
            })
            addView(TextView(this@MainActivity).apply {
                text = "по Bluetooth"
                textSize = 11f
                setTextColor(Color.rgb(70, 70, 70))
                typeface = interFont(600)
            })
        })
        launchTop.addView(connect, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(62)
        ).apply {
            setMargins(dp(14), dp(26), dp(14), 0)
        })

        val qrButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = round(Color.rgb(251, 251, 252), dp(16), Color.rgb(202, 211, 220), 1)
            elevation = dp(3).toFloat()
            addView(
                QrIconView(this@MainActivity, Color.rgb(16, 17, 20)),
                LinearLayout.LayoutParams(dp(34), dp(40))
            )
            addView(TextView(this@MainActivity).apply {
                text = "Проверить ячейку по QR-коду"
                textSize = 15f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(760)
                gravity = Gravity.CENTER
            })
            setOnClickListener { showQrInputScreen() }
        }
        launchTop.addView(qrButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ).apply {
            setMargins(dp(14), dp(12), dp(14), 0)
        })

        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        val launchBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(launchBottom, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        launchBottom.addView(TextView(this).apply {
            text = "LiFePO4 батареи Лиферыч"
            textSize = 16f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(640)
            gravity = Gravity.CENTER
        })
        launchBottom.addView(TextView(this).apply {
            text = "+7 (932) 078-10-11"
            textSize = 17f
            setTextColor(redDark)
            typeface = interFont(760)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Позвонить в Лиферыч"
            setOnClickListener { dialPhone("+79320781011") }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val socialPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = round(Color.rgb(251, 251, 252), dp(18), Color.rgb(202, 211, 220), 1)
        }
        listOf(
            Triple("TG", "Telegram", "https://t.me/liferych"),
            Triple(
                "MAX",
                "MAX",
                "https://max.ru/u/f9LHodD0cOIwPdSddb5TLuiLTMRCIkIpTzgUzr_f2iEj89DXpt_Mh2zXvcc"
            ),
            Triple("VK", "ВКонтакте", "https://vk.ru/liferych")
        ).forEach { (label, messengerName, url) ->
            val slot = FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                contentDescription = "Открыть $messengerName"
                setOnClickListener { openExternalUrl(url) }
            }
            slot.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = if (label == "MAX") 13f else 14f
                setTextColor(Color.rgb(124, 128, 136))
                typeface = interFont(800)
                background = round(Color.rgb(246, 247, 249), dp(29), Color.rgb(223, 229, 235), 1)
            }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
            socialPanel.addView(slot, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        launchBottom.addView(socialPanel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(90)
        ).apply { topMargin = dp(14) })

        launchBottom.addView(TextView(this).apply {
            text = "Версия 1.0.0"
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(600)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        setContentView(root)
    }

    private fun showQrInputScreen() {
        stopQrCamera()
        screenState = "qr_input"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header("Проверка QR-кода", "", ""))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "Проверка ячейки по QR-коду"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(750)
        }, marginLp(-1, -2, 0, 4, 0, 14))
        content.addView(TextView(this).apply {
            text = "▦   Проверка по коду ячейки\nНажмите кнопку сканера или введите код вручную."
            textSize = 13f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.rgb(246, 247, 249), dp(18), Color.rgb(223, 229, 235), 1)
        })

        content.addView(TextView(this).apply {
            text = "QR-код / код ячейки"
            textSize = 12f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(700)
        }, marginLp(-1, -2, 0, 14, 0, 6))

        val entry = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entry.addView(TextView(this).apply {
            text = "▦"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(redDark)
            background = round(Color.rgb(255, 248, 218), dp(18), redDark, 1)
            setOnClickListener { showQrScannerScreen() }
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        val input = EditText(this).apply {
            setText(qrCodeValue)
            hint = "Введите код"
            textSize = 14f
            setSingleLine(false)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
        }
        entry.addView(input, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
            leftMargin = dp(12)
        })
        content.addView(entry)
        content.addView(TextView(this).apply {
            text = "Допускается многострочный код — служебные пробелы будут удалены."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
        }, marginLp(-1, -2, 0, 10, 0, 0))
        content.addView(TextView(this).apply {
            text = "ДЕКОДИРОВАТЬ"
            textSize = 15f
            typeface = interFont(780)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                qrCodeValue = input.text.toString().replace(Regex("\\s+"), "")
                if (qrCodeValue.isBlank()) {
                    toast("Введите или отсканируйте код")
                } else {
                    showQrResultScreen(qrCodeValue)
                }
            }
        }, marginLp(-1, dp(52), 0, 16, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav(""), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun showQrScannerScreen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            return
        }
        screenState = "qr_scan"
        qrProcessing = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header("Сканер QR-кода", "", ""))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "Сканирование кода"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 14))

        val scannerFrame = FrameLayout(this).apply {
            background = round(Color.rgb(246, 247, 249), dp(24), Color.rgb(223, 229, 235), 1)
            clipToOutline = true
        }
        val previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        scannerFrame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        scannerFrame.addView(TextView(this).apply {
            text = "⌜                 ⌝\n\n\n\n\n⌞                 ⌟"
            textSize = 30f
            setTextColor(red)
            gravity = Gravity.CENTER
            typeface = interFont(700)
        }, FrameLayout.LayoutParams(dp(236), dp(236), Gravity.CENTER))
        scannerFrame.addView(TextView(this).apply {
            text = "Наведите камеру на QR-код или Data Matrix"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.argb(230, 255, 255, 255), dp(16), Color.rgb(223, 229, 235), 1)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(22), 0, dp(22), dp(22))
        })
        content.addView(scannerFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(this).apply {
            text = "◉  Сканирование запущено автоматически"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = round(Color.rgb(255, 248, 218), dp(16), redDark, 1)
        }, marginLp(-1, -2, 0, 12, 0, 0))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav(""), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
        startQrCamera(previewView)
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun startQrCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy ->
                if (qrProcessing) {
                    proxy.close()
                    return@setAnalyzer
                }
                val mediaImage = proxy.image
                if (mediaImage == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                qrProcessing = true
                val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                barcodeScanner.process(image)
                    .addOnSuccessListener { codes ->
                        val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                        if (value != null) {
                            qrCodeValue = value
                            runOnUiThread {
                                stopQrCamera()
                                showQrInputScreen()
                                toast("Код найден")
                            }
                        }
                    }
                    .addOnFailureListener {
                        runOnUiThread { toast("Не удалось распознать код") }
                    }
                    .addOnCompleteListener {
                        if (screenState == "qr_scan") qrProcessing = false
                        proxy.close()
                    }
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    private fun stopQrCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        qrProcessing = false
    }

    private fun showQrResultScreen(code: String) {
        stopQrCamera()
        screenState = "qr_result"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header("Результат QR-кода", "", ""))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "Результат проверки"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 14))
        content.addView(TextView(this).apply {
            text = "✓  Код успешно декодирован"
            textSize = 14f
            typeface = interFont(760)
            setTextColor(Color.rgb(31, 179, 90))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.rgb(237, 250, 242), dp(16), Color.rgb(31, 179, 90), 1)
        })
        content.addView(TextView(this).apply {
            text = code
            textSize = 14f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.rgb(246, 247, 249), dp(14), Color.rgb(223, 229, 235), 1)
        }, marginLp(-1, -2, 0, 12, 0, 12))

        val rows = listOf(
            "Производитель" to "Пока неизвестно",
            "Тип изделия" to "Ячейка",
            "Тип батареи" to "Неизвестно",
            "Ёмкость" to "Пока неизвестно",
            "Напряжение" to "Пока неизвестно",
            "Дата выпуска" to "Пока неизвестно",
            "Сайт" to "liferych.ru"
        )
        val report = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
        }
        rows.forEachIndexed { index, (label, value) ->
            report.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                if (index % 2 == 0) setBackgroundColor(Color.rgb(246, 247, 249))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 13f
                    typeface = interFont(700)
                    setTextColor(Color.rgb(111, 119, 129))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 13f
                    typeface = interFont(700)
                    setTextColor(Color.rgb(16, 17, 20))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        content.addView(report)
        content.addView(TextView(this).apply {
            text = "ДЕКОДИРОВАТЬ ЕЩЁ"
            textSize = 15f
            typeface = interFont(780)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                qrCodeValue = ""
                showQrInputScreen()
            }
        }, marginLp(-1, dp(52), 0, 14, 0, 0))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav(""), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showLoadingScreen(label: String = "Поиск устройств...") {
        screenState = "loading"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), dp(82))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.liferych_logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = dp(2).toFloat()
        }, LinearLayout.LayoutParams(dp(188), dp(179)))

        root.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateDrawable.setColorFilter(red, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
            topMargin = dp(30)
            bottomMargin = dp(14)
        })

        root.addView(TextView(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(650)
            gravity = Gravity.CENTER
        })

        setContentView(root)
    }

    private fun showBatteriesScreen() {
        screenState = "batteries"
        currentTab = "main"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundColor(Color.WHITE)
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.liferych_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(this@MainActivity).apply {
                text = "ЛИФЕРЫЧ"
                textSize = 22f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(800)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(10) })
        }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(56), 1f))
        top.addView(BatteryIconView(this, Color.rgb(16, 17, 20)).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Добавить батарею"
            background = round(Color.rgb(246, 247, 249), dp(19), Color.rgb(223, 229, 235), 1)
            setOnClickListener {
                disconnectGatt()
                showSearchScreen()
                startScan()
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(74)))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "Мои батареи"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(750)
        }, marginLp(-1, -2, 0, 4, 0, 14))
        content.addView(TextView(this).apply {
            text = "Добавленные устройства"
            textSize = 13f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(760)
        }, marginLp(-1, -2, 0, 0, 0, 8))

        val saved = loadSavedBatteries()
        saved.forEach { battery ->
            content.addView(savedBatteryCard(battery), marginLp(-1, -2, 0, 0, 0, 10))
        }

        if (saved.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "Сохранённых батарей пока нет"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(111, 119, 129))
                background = round(Color.rgb(246, 247, 249), dp(16), Color.rgb(223, 229, 235), 1)
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }, marginLp(-1, -2, 0, 0, 0, 10))
        }

        content.addView(TextView(this).apply {
            text = "Нажмите на батарею, чтобы открыть её параметры. Удерживайте карточку, чтобы задать имя. Если батарей ещё нет, нажмите кнопку ниже."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.rgb(251, 251, 252), dp(14), Color.rgb(202, 211, 220), 1)
        }, marginLp(-1, -2, 0, 2, 0, 10))

        val addButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(255, 217, 87), Color.rgb(255, 196, 0))
            ).apply { cornerRadius = dp(15).toFloat() }
            elevation = dp(4).toFloat()
            addView(BatteryIconView(
                this@MainActivity,
                Color.rgb(16, 17, 20)
            ).apply {
                background = round(Color.argb(120, 255, 255, 255), dp(11), Color.rgb(210, 180, 60), 1)
            }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(TextView(this@MainActivity).apply {
                text = "ДОБАВИТЬ БАТАРЕЮ"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(780)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(dp(36), 1))
            setOnClickListener {
                disconnectGatt()
                showSearchScreen()
                startScan()
            }
        }
        content.addView(addButton, LinearLayout.LayoutParams(-1, dp(56)).apply {
            topMargin = dp(4)
        })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun savedBatteryCard(battery: SavedBattery): View {
        val isTest = battery.address == TEST_BATTERY_ADDRESS
        val connected = !isTest && polling && selectedAddress == battery.address
        val nearby = scanNames.containsKey(battery.address)
        val soc = battery.soc
        val levelColor = when {
            soc == null -> Color.rgb(111, 119, 129)
            soc < 25.0 -> Color.rgb(239, 83, 80)
            soc < 70.0 -> Color.rgb(140, 106, 0)
            else -> Color.rgb(31, 179, 90)
        }
        val levelBg = when {
            soc == null -> Color.rgb(246, 247, 249)
            soc < 25.0 -> Color.rgb(255, 240, 240)
            soc < 70.0 -> Color.rgb(255, 248, 218)
            else -> Color.rgb(237, 250, 242)
        }
        val displayName = battery.customName.ifBlank {
            battery.bluetoothName.ifBlank { battery.address }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(88)
            background = round(
                Color.WHITE,
                dp(18),
                if (connected) redDark else Color.rgb(223, 229, 235),
                1
            )
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true

            val socBox = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = round(levelBg, dp(16), levelColor, 1)
                addView(TextView(this@MainActivity).apply {
                    text = soc?.let { "${it.toInt()}%" } ?: "--"
                    textSize = 16f
                    setTextColor(levelColor)
                    typeface = interFont(800)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "SOC"
                    textSize = 10f
                    letterSpacing = 0.06f
                    setTextColor(Color.rgb(111, 119, 129))
                    typeface = interFont(700)
                    gravity = Gravity.CENTER
                })
            }
            addView(socBox, LinearLayout.LayoutParams(dp(58), dp(58)))

            val main = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val titleRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = displayName
                        textSize = 16f
                        setTextColor(Color.rgb(16, 17, 20))
                        typeface = interFont(780)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = battery.capacityAh?.let { "%.0f А·ч".format(it) } ?: "-- А·ч"
                        textSize = 12f
                        setTextColor(Color.rgb(111, 119, 129))
                        typeface = interFont(700)
                    })
                }
                addView(titleRow)
                addView(TextView(this@MainActivity).apply {
                    text = when {
                        isTest -> "12 В · тестовые данные"
                        connected -> "${battery.bluetoothName} · Bluetooth подключен"
                        nearby -> "${battery.bluetoothName} · доступна рядом"
                        else -> "${battery.bluetoothName} · связь потеряна"
                    }
                    textSize = 12f
                    setTextColor(Color.rgb(111, 119, 129))
                    typeface = interFont(650)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                addView(TextView(this@MainActivity).apply {
                    text = when {
                        isTest -> "✓  Тестовый режим"
                        connected -> "✓  Батарея подключена"
                        soc != null && soc < 25.0 -> "!  Низкий заряд"
                        nearby -> "•  Доступна для подключения"
                        else -> "−  Не в сети"
                    }
                    textSize = 11f
                    setTextColor(
                        when {
                            isTest -> redDark
                            connected -> Color.rgb(31, 179, 90)
                            soc != null && soc < 25.0 -> Color.rgb(239, 83, 80)
                            nearby -> redDark
                            else -> Color.rgb(111, 119, 129)
                        }
                    )
                    typeface = interFont(760)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = round(levelBg, dp(13), Color.TRANSPARENT, 0)
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            }
            addView(main, LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = dp(12)
            })

            val linkColor = when {
                isTest -> redDark
                connected -> Color.rgb(31, 179, 90)
                else -> Color.rgb(111, 119, 129)
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(BatteryIconView(this@MainActivity, linkColor, 17f).apply {
                    background = round(
                        Color.rgb(246, 247, 249),
                        dp(10),
                        Color.rgb(223, 229, 235),
                        1
                    )
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(111, 119, 129))
                }, LinearLayout.LayoutParams(dp(30), dp(24)))
            }, LinearLayout.LayoutParams(dp(34), -1))

            setOnClickListener {
                if (isTest) {
                    openTestBattery(battery, displayName)
                } else if (connected) {
                    selectedDeviceName = displayName
                    showDashboardScreen()
                } else {
                    connectSavedBattery(battery, displayName)
                }
            }
            setOnLongClickListener {
                showRenameBatteryDialog(battery)
                true
            }
        }
    }

    private fun showRenameBatteryDialog(battery: SavedBattery) {
        val input = EditText(this).apply {
            setText(battery.customName.ifBlank { battery.bluetoothName })
            setSelection(text.length)
            hint = "Название батареи"
            setSingleLine(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Название батареи")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    saveBatteries(loadSavedBatteries().map {
                        if (it.address == battery.address) it.copy(customName = name) else it
                    })
                    if (selectedAddress == battery.address) selectedDeviceName = name
                    showBatteriesScreen()
                }
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить") { _, _ ->
                saveBatteries(loadSavedBatteries().filterNot {
                    it.address == battery.address
                })
                if (selectedAddress == battery.address) {
                    selectedAddress = null
                    selectedDeviceName = ""
                }
                showBatteriesScreen()
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectSavedBattery(battery: SavedBattery, displayName: String) {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            toast("Разрешите доступ к Bluetooth и повторите подключение")
            return
        }
        val device = try {
            bluetoothAdapter.getRemoteDevice(battery.address)
        } catch (_: Exception) {
            null
        }
        if (device == null) {
            toast("Не удалось открыть сохранённое устройство")
            return
        }
        devices[battery.address] = device
        selectedAddress = battery.address
        selectedDeviceName = displayName
        returnToBatteriesAfterConnect = false
        showLoadingScreen("Подключение к $displayName...")
        connectSelectedDevice()
    }

    private fun ensureTestBattery() {
        if (batteryPrefs.getBoolean("test_battery_seeded", false)) return
        val saved = loadSavedBatteries().toMutableList()
        if (saved.none { it.address == TEST_BATTERY_ADDRESS }) {
            saved += SavedBattery(
                address = TEST_BATTERY_ADDRESS,
                bluetoothName = "TEST_BMS_12V",
                customName = "Тестовая батарея 12 В",
                soc = 96.0,
                capacityAh = 105.0,
                lastSeenAt = System.currentTimeMillis()
            )
            saveBatteries(saved)
        }
        batteryPrefs.edit().putBoolean("test_battery_seeded", true).apply()
    }

    private fun openTestBattery(battery: SavedBattery, displayName: String) {
        disconnectGatt()
        selectedAddress = battery.address
        selectedDeviceName = displayName
        data.voltage = 13.3
        data.current = -0.4
        data.soc = battery.soc ?: 96.0
        data.remainingAh = 100.8
        data.estimatedFullAh = 105.0
        data.maxCellV = 3.327
        data.maxCellNo = 1
        data.minCellV = 3.323
        data.minCellNo = 4
        data.cellDiffV = 0.004
        data.maxTemp = 24
        data.minTemp = 23
        data.chargeMos = true
        data.dischargeMos = true
        data.cellCount = 4
        data.tempCount = 2
        data.chargerConnected = false
        data.loadConnected = true
        data.cells.clear()
        data.cells.putAll(
            mapOf(
                1 to 3.327,
                2 to 3.326,
                3 to 3.324,
                4 to 3.323
            )
        )
        data.temps.clear()
        data.temps[1] = 23
        data.temps[2] = 24
        data.errors.clear()
        showDashboardScreen()
    }

    private fun loadSavedBatteries(): List<SavedBattery> {
        val result = mutableListOf<SavedBattery>()
        val array = try {
            JSONArray(batteryPrefs.getString("items", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val address = item.optString("address").trim()
            if (address.isBlank()) continue
            result += SavedBattery(
                address = address,
                bluetoothName = sanitizeBleText(item.optString("bluetooth_name")).ifBlank {
                    item.optString("bluetooth_name")
                },
                customName = item.optString("custom_name"),
                soc = if (item.has("soc") && !item.isNull("soc")) item.optDouble("soc") else null,
                capacityAh = if (item.has("capacity_ah") && !item.isNull("capacity_ah")) {
                    item.optDouble("capacity_ah")
                } else null,
                lastSeenAt = item.optLong("last_seen_at")
            )
        }
        return result.sortedByDescending { it.lastSeenAt }
    }

    private fun saveBatteries(items: List<SavedBattery>) {
        val array = JSONArray()
        items.forEach { battery ->
            array.put(JSONObject().apply {
                put("address", battery.address)
                put("bluetooth_name", battery.bluetoothName)
                put("custom_name", battery.customName)
                put("soc", battery.soc ?: JSONObject.NULL)
                put("capacity_ah", battery.capacityAh ?: JSONObject.NULL)
                put("last_seen_at", battery.lastSeenAt)
            })
        }
        batteryPrefs.edit().putString("items", array.toString()).apply()
    }

    private fun saveCurrentBattery(force: Boolean = false) {
        val address = selectedAddress ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastBatterySnapshotSavedAt < 2000L) return
        lastBatterySnapshotSavedAt = now
        val current = loadSavedBatteries().toMutableList()
        val index = current.indexOfFirst { it.address == address }
        val previous = current.getOrNull(index)
        val bluetoothName = sanitizeBleText(
            scanNames[address] ?: previous?.bluetoothName ?: selectedDeviceName
        ).ifBlank { selectedAddress.orEmpty() }
        val updated = SavedBattery(
            address = address,
            bluetoothName = bluetoothName,
            customName = previous?.customName.orEmpty(),
            soc = data.soc ?: previous?.soc,
            capacityAh = data.estimatedFullAh ?: previous?.capacityAh,
            lastSeenAt = now
        )
        if (index >= 0) current[index] = updated else current += updated
        saveBatteries(current)
    }

    private fun showSearchScreen() {
        screenState = "search"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundColor(Color.WHITE)
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.liferych_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(this@MainActivity).apply {
                text = "ЛИФЕРЫЧ"
                textSize = 22f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(800)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(56), 1f))
        top.addView(TextView(this).apply {
            text = "ᛒ"
            textSize = 22f
            setTextColor(redDark)
            gravity = Gravity.CENTER
            setOnClickListener { startScan() }
            background = round(Color.rgb(246, 247, 249), dp(19), Color.rgb(223, 229, 235), 1)
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })
        top.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            background = round(Color.rgb(246, 247, 249), dp(19), Color.rgb(223, 229, 235), 1)
            setOnClickListener { toast("Поиск BLE-устройств") }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(74)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad(), 0, hPad(), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "Батарея"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(750)
        }, marginLp(-1, -2, 0, 4, 0, 14))
        content.addView(TextView(this).apply {
            text = "Доступные устройства"
            textSize = 13f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(760)
        }, marginLp(-1, -2, 0, 0, 0, 8))
        val searchEdit = EditText(this).apply {
            setText(searchIdQuery)
            hint = "Поиск по ID, например 3A2F"
            textSize = 15f
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchIdQuery = s?.toString().orEmpty()
                    rebuildSearchDeviceList()
                }
            })
        }
        content.addView(searchEdit, LinearLayout.LayoutParams(-1, dp(48)))
        content.addView(TextView(this).apply {
            text = "Можно ввести последние цифры Bluetooth ID"
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
        }, marginLp(-1, -2, 0, 6, 0, 10))

        val scroll = ScrollView(this)
        deviceListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(deviceListLayout)
        content.addView(TextView(this).apply {
            text = "Нажмите «Подключить» напротив нужной батареи. После успешного подключения вы вернётесь на экран «Мои батареи», где сможете открыть её параметры."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.rgb(251, 251, 252), dp(14), Color.rgb(202, 211, 220), 1)
        }, marginLp(-1, -2, 0, 12, 0, 0))
        scroll.addView(content)

        statusText = TextView(this).apply {
            visibility = View.GONE
        }

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showDashboardScreen() {
        screenState = "dashboard"
        currentTab = "main"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val connectionLabel = if (selectedAddress == TEST_BATTERY_ADDRESS) {
            "Тестовый\nрежим"
        } else {
            "Bluetooth\nподключен"
        }
        val h = header(
            appBrandTitle(),
            selectedDeviceName.ifBlank { selectedAddress ?: "" },
            connectionLabel
        )
        root.addView(h)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad(), 0, hPad(), dp(14))
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            elevation = dp(2).toFloat()
        }
        socGauge = SocGaugeView(this)
        hero.addView(socGauge, LinearLayout.LayoutParams(dp(118), dp(118)))
        val chargeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
            remainingValue = TextView(this@MainActivity).apply {
                text = "-- А·ч"
                textSize = 34f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(770)
            }
            addView(remainingValue)
            addView(TextView(this@MainActivity).apply {
                text = "из полной ёмкости батареи"
                textSize = 13f
                setTextColor(Color.rgb(111, 119, 129))
                typeface = interFont(650)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            socProgress = ProgressBar(
                this@MainActivity,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 100
                progress = data.soc?.toInt() ?: 0
                progressTintList = android.content.res.ColorStateList.valueOf(red)
                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.rgb(223, 229, 235))
            }
            addView(socProgress, LinearLayout.LayoutParams(-1, dp(8)).apply {
                topMargin = dp(12)
            })
        }
        hero.addView(chargeBlock, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(hero)

        fun metricBox(
            icon: String,
            initial: String,
            label: String,
            sub: String,
            valueSize: Float = 18f,
            truncate: Boolean = false
        ): Pair<LinearLayout, TextView> {
            val value = TextView(this).apply {
                text = initial
                textSize = valueSize
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(760)
                if (truncate) {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                minimumHeight = dp(82)
                background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                addView(TextView(this@MainActivity).apply {
                    text = if (icon.isBlank()) label else "$icon  $label"
                    textSize = 11f
                    setTextColor(Color.rgb(111, 119, 129))
                    typeface = interFont(650)
                })
                addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                addView(TextView(this@MainActivity).apply {
                    text = sub
                    textSize = 11f
                    setTextColor(Color.rgb(111, 119, 129))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            return box to value
        }

        val metricRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val currentMetric = metricBox("↯", "-- А", "Ток", "Ожидание данных")
        currentValue = currentMetric.second
        metricRow1.addView(currentMetric.first, marginLp(0, -2, 0, 0, 4, 0).apply { weight = 1f })
        val voltageMetric = metricBox("V", "-- В", "Напряжение", "Напряжение батареи")
        voltageValue = voltageMetric.second
        metricRow1.addView(voltageMetric.first, marginLp(0, -2, 4, 0, 0, 0).apply { weight = 1f })
        content.addView(metricRow1, marginLp(-1, -2, 0, 8, 0, 0))

        val metricRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tempMetric = metricBox("°", "-- °C", "Температура", "Датчик BMS")
        t1Text = tempMetric.second
        metricRow2.addView(tempMetric.first, marginLp(0, -2, 0, 0, 4, 0).apply { weight = 1f })
        val modeMetric = metricBox("◉", "Нормальный", "Режим", "Состояние BMS")
        balanceValue = modeMetric.second
        metricRow2.addView(modeMetric.first, marginLp(0, -2, 4, 0, 0, 0).apply { weight = 1f })
        content.addView(metricRow2, marginLp(-1, -2, 0, 8, 0, 0))

        val metricRow3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            isMeasureWithLargestChildEnabled = true
        }
        val nameMetric = metricBox("⌁", selectedDeviceName.ifBlank { "--" }, "Имя устройства", "Bluetooth", 14f, true)
        deviceNameValue = nameMetric.second
        metricRow3.addView(nameMetric.first, marginLp(0, -1, 0, 0, 4, 0).apply { weight = 1.4f })
        val cyclesMetric = metricBox("#", "--", "Циклы", "Заряд / разряд", 14f)
        cycleCountValue = cyclesMetric.second
        metricRow3.addView(cyclesMetric.first, marginLp(0, -1, 4, 0, 0, 0).apply { weight = 1f })
        content.addView(metricRow3, marginLp(-1, -2, 0, 8, 0, 0))
        equalizeRowChildHeights(metricRow3)

        if (isServiceApp()) {
            val idMetric = metricBox("ID", bluetoothId().ifBlank { "--" }, "Bluetooth ID", "MAC-адрес", 12f, true)
            bluetoothIdValue = idMetric.second.apply {
                maxLines = 2
                ellipsize = null
            }
            content.addView(idMetric.first, marginLp(-1, -2, 0, 8, 0, 0))
        } else {
            bluetoothIdValue = null
        }

        val snRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            isMeasureWithLargestChildEnabled = true
        }
        val snMetric = metricBox("", displayFactorySn().ifBlank { "--" }, "Заводской номер", "SN", 13f, true)
        bmsSnValue = snMetric.second
        snRow.addView(snMetric.first, marginLp(0, -1, 0, 0, 4, 0).apply { weight = 1.3f })
        val versionMetric = metricBox("", displayBmsVersion().ifBlank { "--" }, "Версия BMS", "A5 0x63", 12f, true)
        bmsVersionValue = versionMetric.second
        snRow.addView(versionMetric.first, marginLp(0, -1, 4, 0, 0, 0).apply { weight = 1f })
        content.addView(snRow, marginLp(-1, -2, 0, 8, 0, 0))
        equalizeRowChildHeights(snRow)

        if (isServiceApp()) {
            serviceVersionDebugView = TextView(this).apply {
                text = serviceVersionDebugDump()
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(50, 50, 50))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = round(Color.rgb(247, 248, 250), dp(14), Color.rgb(223, 229, 235), 1)
            }
            content.addView(serviceVersionDebugView, marginLp(-1, -2, 0, 8, 0, 0))
        } else {
            serviceVersionDebugView = null
        }

        if (isServiceApp()) {
            dashboardUploadStatusText = TextView(this).apply {
                text = currentUploadStatusText()
                textSize = 12f
                setTextColor(Color.rgb(90, 90, 90))
            }
            content.addView(dashboardUploadStatusText, marginLp(-1, -2, 0, 0, 0, 8))
            content.addView(TextView(this).apply {
                text = "ОТПРАВИТЬ НА СЕРВЕР"
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = interFont(760)
                setTextColor(Color.rgb(16, 17, 20))
                background = round(red, dp(14), Color.TRANSPARENT, 0)
                setOnClickListener { uploadCurrentData(force = true) }
            }, marginLp(-1, dp(48), 0, 0, 0, 8))
        } else {
            dashboardUploadStatusText = null
        }

        batteryInfoText = TextView(this).apply { visibility = View.GONE }

        chargeMosValue = TextView(this)
        dischargeMosValue = TextView(this)
        heatValue = TextView(this)
        t2Text = TextView(this)

        val cellsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
        }
        cellsCard.addView(TextView(this).apply {
            text = "Напряжение элементов"
            textSize = 14f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(760)
        })
        cellsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        cellsCard.addView(cellsLayout)
        content.addView(cellsCard, marginLp(-1, -2, 0, 8, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))

        setContentView(root)
        updateDashboardUi()
    }

    private fun header(title: String, sub: String, right: String): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundColor(Color.WHITE)
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.liferych_logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Логотип Лиферыч"
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(TextView(this@MainActivity).apply {
                text = "ЛИФЕРЫЧ"
                textSize = 22f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(800)
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        }
        root.addView(brand, LinearLayout.LayoutParams(0, dp(56), 1f))

        headerTitle = TextView(this).apply {
            text = title
            textSize = 1f
            setTextColor(Color.TRANSPARENT)
            typeface = interFont(700)
            visibility = View.GONE
        }
        headerSub = TextView(this).apply {
            text = sub
            textSize = 1f
            visibility = View.GONE
        }

        connectionText = TextView(this).apply {
            text = "ᛒ"
            textSize = 22f
            setTextColor(if (right.isNotBlank()) redDark else Color.rgb(111, 119, 129))
            gravity = Gravity.CENTER
            contentDescription = right.ifBlank { "Bluetooth" }
            background = round(
                Color.rgb(246, 247, 249),
                dp(19),
                Color.rgb(223, 229, 235),
                1
            )
        }
        root.addView(connectionText, LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            rightMargin = dp(8)
        })
        root.addView(TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            contentDescription = "Меню"
            background = round(
                Color.rgb(246, 247, 249),
                dp(19),
                Color.rgb(223, 229, 235),
                1
            )
            setOnClickListener { toast(title) }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))

        return root
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            elevation = dp(2).toFloat()
        }
    }

    private fun metricBlock(parent: LinearLayout, icon: String, value: String, label: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val line = TextView(this).apply {
            text = "$icon  $value"
            textSize = 18f
            setTextColor(Color.rgb(30, 30, 30))
            typeface = interFont(700)
            gravity = Gravity.CENTER
        }
        val lab = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.rgb(55, 55, 55))
            gravity = Gravity.CENTER
        }
        box.addView(line)
        box.addView(lab)
        parent.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        return line
    }

    private fun chip(parent: LinearLayout, label: String, value: String, color: Int): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = round(Color.rgb(247, 248, 250), dp(12), Color.TRANSPARENT, 0)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.rgb(45, 45, 45))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this).apply {
            text = value
            textSize = 13f
            typeface = interFont(700)
            setTextColor(color)
        }
        row.addView(v)
        parent.addView(row, marginLp(0, -2, 4, 4, 4, 4).apply { weight = 1f })
        return v
    }

    private fun sectionTitle(left: String, right: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = left
                textSize = 16f
                typeface = interFont(700)
                setTextColor(Color.rgb(50, 50, 50))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = right
                textSize = 12f
                setTextColor(Color.rgb(100, 100, 100))
            })
        }
    }

    private fun tempValue(parent: LinearLayout, initial: String): TextView {
        val t = TextView(this).apply {
            text = "🌡  $initial"
            textSize = 15f
            setTextColor(Color.rgb(40, 40, 40))
            gravity = Gravity.CENTER
        }
        parent.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return t
    }

    private fun navItem(parent: LinearLayout, text: String, selected: Boolean) {
        parent.addView(TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(if (selected) redDark else Color.rgb(111, 119, 129))
            typeface = interFont(if (selected) 700 else 650)
            setOnClickListener {
                if (text.contains("Главная")) {
                    showBatteriesScreen()
                } else if (text.contains("Сервис")) {
                    showServiceScreen()
                } else if (text.contains("Отладка")) {
                    showDebugScreen()
                } else if (text.contains("ОТК")) {
                    showQtcScreen()
                } else if (text.contains("Управление") || text.contains("Настройки")) {
                    showManageScreen()
                } else if (text.contains("Журнал")) {
                    showJournalScreen()
                } else if (text.contains("Поддержка") || text.contains("Техподдержка")) {
                    showSupportScreen()
                } else if (text.contains("Профиль")) {
                    if (isServiceApp()) {
                        showProfileScreen()
                    } else {
                        val loggedIn = getSharedPreferences("user_profile", MODE_PRIVATE)
                            .getBoolean("logged_in", false)
                        if (loggedIn) showProfileScreen() else showAuthScreen()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun fixedBottomNav(selectedTab: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(8))
            elevation = dp(8).toFloat()
            navItem(this, "⌂\nГлавная", selectedTab == "main")
            if (isServiceApp()) {
                navItem(this, "⚙\nСервис", selectedTab == "service")
                navItem(this, "✓\nОТК", selectedTab == "qtc")
                navItem(this, "☰\nОтладка", selectedTab == "debug")
                navItem(this, "●\nПрофиль", selectedTab == "profile")
            } else {
                navItem(this, "≡\nЖурнал", selectedTab == "journal")
                navItem(this, "⚙\nНастройки", selectedTab == "manage")
                navItem(this, "☎\nПоддержка", selectedTab == "support")
                navItem(this, "●\nПрофиль", selectedTab == "profile")
            }
        }
    }


    private fun showServiceScreen() {
        if (!isServiceApp()) {
            showBatteriesScreen()
            return
        }
        screenState = "service"
        currentTab = "service"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Сервис BMS",
                selectedDeviceName.ifBlank { selectedAddress ?: "нет подключения" },
                if (bluetoothGatt != null) "Bluetooth\nподключен" else "Нет BLE"
            )
        )

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        val assembler = assemblerName()
        content.addView(TextView(this).apply {
            text = if (assembler.isBlank()) {
                "Имя сборщика не указано. Телеметрия на сервер всё равно уходит. Имя нужно только для записи шаблона."
            } else {
                "Сборщик: $assembler"
            }
            textSize = 14f
            typeface = interFont(700)
            setTextColor(if (assembler.isBlank()) Color.rgb(140, 90, 20) else Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 0, 0, 10))

        val serverCard = card()
        serverCard.addView(sectionTitle("Сервер", adminServerBaseUrl()))
        serviceUploadStatusText = TextView(this).apply {
            text = currentUploadStatusText()
            textSize = 13f
            setTextColor(Color.rgb(50, 50, 50))
        }
        serverCard.addView(serviceUploadStatusText, marginLp(-1, -2, 0, 0, 0, 10))
        val serverRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        serverRow.addView(TextView(this).apply {
            text = "Проверить связь"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
            setOnClickListener { pingAdminServer() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        serverRow.addView(TextView(this).apply {
            text = "Отправить сейчас"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(12), Color.TRANSPARENT, 0)
            setOnClickListener { uploadCurrentData(force = true) }
        }, marginLp(0, dp(48), 8, 0, 0, 0).apply { weight = 1f })
        serverCard.addView(serverRow)
        content.addView(serverCard, marginLp(-1, -2, 0, 0, 0, 12))

        val idCard = card()
        idCard.addView(sectionTitle("Идентификация BMS", ""))
        idCard.addView(TextView(this).apply {
            text = "SN: ${displayFactorySn().ifBlank { "—" }}\nВерсия BMS: ${displayBmsVersion().ifBlank { "—" }}\nBluetooth ID: ${bluetoothId().ifBlank { "—" }}\n\n${serviceVersionDebugDump()}"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.rgb(50, 50, 50))
        })
        content.addView(idCard, marginLp(-1, -2, 0, 0, 0, 12))

        val templateCard = card()
        templateCard.addView(sectionTitle("Шаблон настроек", "12В или 24В"))
        val tplRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun templateButton(key: String, title: String): TextView {
            val selected = serviceTemplateKey == key
            return TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = interFont(740)
                setTextColor(if (selected) Color.rgb(16, 17, 20) else Color.rgb(90, 90, 90))
                background = round(if (selected) red else Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
                setOnClickListener {
                    serviceTemplateKey = key
                    showServiceScreen()
                }
            }
        }
        tplRow.addView(templateButton("12v", "12В · 4S"), LinearLayout.LayoutParams(0, dp(48), 1f))
        tplRow.addView(templateButton("24v", "24В · 8S"), marginLp(0, dp(48), 8, 0, 0, 0).apply { weight = 1f })
        templateCard.addView(tplRow, marginLp(-1, -2, 0, 0, 0, 10))
        templateCard.addView(TextView(this).apply {
            text = "Ёмкость, А·ч"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
        })
        val capacityEdit = EditText(this).apply {
            setText(serviceCapacityText)
            hint = "например 105"
            textSize = 16f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    serviceCapacityText = s?.toString().orEmpty()
                }
            })
        }
        templateCard.addView(capacityEdit, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(templateCard, marginLp(-1, -2, 0, 0, 0, 12))

        content.addView(TextView(this).apply {
            text = "ЗАПИСАТЬ ШАБЛОН"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(760)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            isEnabled = !serviceWriteActive
            alpha = if (serviceWriteActive) 0.6f else 1f
            setOnClickListener { startServiceTemplateWrite() }
        }, marginLp(-1, dp(54), 0, 0, 0, 10))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("service"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showDebugScreen() {
        if (!isServiceApp()) {
            showBatteriesScreen()
            return
        }
        if (configRegisters.isEmpty() && !configReadInProgress) loadCachedConfigForCurrentBms()
        if (bluetoothGatt != null && !configReadInProgress) {
            startConfigReadIfNeeded(force = true)
        }

        screenState = "debug"
        currentTab = "debug"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Отладка BMS",
                selectedDeviceName.ifBlank { selectedAddress ?: "нет подключения" },
                if (configReadInProgress) "Читаю регистры" else if (bluetoothGatt != null) "Bluetooth\nподключен" else "Нет BLE"
            )
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        actions.addView(TextView(this).apply {
            text = "Прочитать"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(12), Color.TRANSPARENT, 0)
            setOnClickListener {
                if (bluetoothGatt == null) {
                    toast("Нет подключения")
                    return@setOnClickListener
                }
                startConfigReadIfNeeded(force = true)
                startDebugA5Scan()
                debugDumpView?.text = bmsRegisterDump()
                toast("Читаю Modbus и сканирую A5")
            }
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(TextView(this).apply {
            text = "Копировать"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
            setOnClickListener {
                val dump = bmsRegisterDump()
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BMS registers", dump))
                toast("Дамп скопирован")
            }
        }, marginLp(0, dp(44), 8, 0, 0, 0).apply { weight = 1f })
        root.addView(actions)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        debugDumpView = TextView(this).apply {
            text = bmsRegisterDump()
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(Color.rgb(30, 30, 30))
        }
        content.addView(debugDumpView)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("debug"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showQtcScreen() {
        if (!isServiceApp()) {
            showBatteriesScreen()
            return
        }
        if (configRegisters.isEmpty() && !configReadInProgress) loadCachedConfigForCurrentBms()

        screenState = "qtc"
        currentTab = "qtc"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "ОТК",
                selectedDeviceName.ifBlank { selectedAddress ?: "нет подключения" },
                if (bluetoothGatt != null) "Bluetooth\nподключен" else "Нет BLE"
            )
        )

        val scroll = ScrollView(this)
        qtcContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        scroll.addView(qtcContentLayout)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("qtc"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
        renderQtcContent()
    }

    private fun renderQtcContent() {
        if (!::qtcContentLayout.isInitialized) return
        qtcContentLayout.removeAllViews()

        val result = if (configRegisters.isNotEmpty()) evaluateTemplateCheck() else currentTemplateCheck()
        qtcContentLayout.addView(qtcStatusCard(result), marginLp(-1, -2, 0, 0, 0, 12))

        val mismatches = result?.mismatches.orEmpty()
        val missing = result?.missing.orEmpty()
        if (result?.status == "mismatch" || mismatches.isNotEmpty() || missing.isNotEmpty()) {
            val rows = mutableListOf<Pair<String, String>>()
            mismatches.forEach {
                rows += it.label to "${qtcValueText(it.actual, it.unit)}  ≠  ${qtcValueText(it.expected, it.unit)}"
            }
            missing.forEach {
                rows += it.label to "нет данных, ожидалось ${qtcValueText(it.expected, it.unit)}"
            }
            if (rows.isNotEmpty()) {
                qtcContentLayout.addView(
                    manageSectionCard("Не совпадает с шаблоном", rows),
                    marginLp(-1, -2, 0, 0, 0, 12)
                )
            }
        }

        qtcContentLayout.addView(
            manageSectionCard("Параметры шаблона", qtcTemplateParameterRows()),
            marginLp(-1, -2, 0, 0, 0, 12)
        )

        val cellCount = data.cellCount
        val cellRows = if (cellCount != null) {
            (1..cellCount).map { no ->
                "Ячейка $no" to (data.cells[no]?.let { "%.3f В".format(it) } ?: "--")
            }
        } else {
            emptyList()
        }
        qtcContentLayout.addView(
            manageSectionCard(
                "Параметры АКБ",
                listOf(
                    "Серийный номер BMS" to displayFactorySn().ifBlank { "--" },
                    "Версия BMS" to displayBmsVersion().ifBlank { "--" },
                    "Напряжение" to (data.voltage?.let { "%.2f В".format(it) } ?: "--"),
                    "Температура 1" to qtcTempText(1),
                    "Температура 2" to qtcTempText(2),
                    "Разбег ячеек" to (data.cellDiffV?.let { "%.3f В".format(it) } ?: "--"),
                    "Ёмкость" to (data.remainingAh?.let { "%.1f А·ч".format(it) } ?: "--"),
                    "Номинальная ёмкость" to nominalCapacityText(),
                    "MOS зарядки" to mosText(data.chargeMos),
                    "MOS разрядки" to mosText(data.dischargeMos)
                ) + cellRows
            ),
            marginLp(-1, -2, 0, 0, 0, 12)
        )
    }

    private fun qtcStatusCard(result: TemplateCheckResult?): LinearLayout {
        val status = result?.status ?: "unknown"
        val color = when (status) {
            "ok" -> Color.rgb(28, 160, 55)
            "mismatch" -> Color.rgb(211, 47, 47)
            "incomplete" -> Color.rgb(224, 150, 0)
            else -> Color.rgb(110, 118, 128)
        }
        val series = result?.seriesCount?.let { "${it}S" } ?: data.cellCount?.let { "${it}S" } ?: "—"
        val title = when (status) {
            "checking" -> "Проверка конфигурации…"
            "ok" -> "Конфигурация соответствует шаблону"
            "mismatch" -> "Конфигурация не соответствует шаблону"
            "incomplete" -> "Проверка конфигурации неполная"
            else -> "Конфигурация ещё не проверена"
        }
        val detail = when (status) {
            "ok" -> "Серия $series. Все параметры шаблона совпадают."
            "mismatch" -> "Серия $series. Ниже указаны расхождения."
            "incomplete" -> "Серия $series. Не все параметры удалось сравнить."
            "checking" -> "Чтение настроек BMS ещё не закончено."
            else -> "Подключите BMS и дождитесь чтения конфигурации."
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)), dp(14), color, 1)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 16f
                typeface = interFont(720)
                setTextColor(color)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 13f
                setTextColor(Color.rgb(75, 79, 84))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun qtcTemplateParameterRows(): List<Pair<String, String>> {
        val series = data.cellCount?.takeIf { it == 4 || it == 8 }
        val template = try {
            loadBmsConfigTemplate()
        } catch (_: Exception) {
            return listOf("Шаблон" to "не загружен")
        }
        val family = currentHardwareFamily()
        val rows = mutableListOf<Pair<String, String>>()
        for (parameter in template.parameters) {
            if (parameter.enforcement == "informational") continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            val expected = parameter.expected ?: series?.let { parameter.expectedBySeries[it] }
            val actual = templateParameterActual(parameter)
            val actualText = qtcValueText(actual, parameter.unit)
            val expectedText = qtcValueText(expected, parameter.unit)
            val ok = actual != null && expected != null &&
                kotlin.math.abs(actual - expected) <= parameter.tolerance
            rows += parameter.label to if (ok) {
                actualText
            } else {
                "$actualText  ·  шаблон $expectedText"
            }
        }
        if (rows.isEmpty()) return listOf("Параметры" to "нет данных")
        return rows
    }

    private fun qtcValueText(value: Double?, unit: String): String {
        if (value == null) return "—"
        return "${formatTemplateNumber(value)} $unit".trim()
    }

    private fun qtcTempText(no: Int): String {
        if (hasTemperatureSensorError()) return "Нет датчика"
        val t = data.temps[no] ?: if (no == 1) data.minTemp else data.maxTemp
        return t?.let { "$it °C" } ?: "--"
    }

    private fun serviceWriteSummaryText(): String {
        val ok = serviceWriteResults.count { it.ok }
        return "Успешно $ok из ${serviceWriteResults.size}"
    }

    private fun startServiceTemplateWrite() {
        if (!isServiceApp() || serviceWriteActive) return
        if (assemblerName().isBlank()) {
            toast("Сначала укажите имя сборщика в профиле")
            return
        }
        val capacity = serviceCapacityText.replace(",", ".").trim().toDoubleOrNull()
        if (capacity == null || capacity < 1.0 || capacity > 2000.0) {
            toast("Введите ёмкость АКБ в А·ч")
            return
        }
        if (bluetoothGatt == null || writeCharacteristic == null) {
            toast("Подключите BMS по Bluetooth")
            return
        }
        val series = if (serviceTemplateKey == "24v") 8 else 4
        enqueueServiceTemplateWrites(capacity, series)
    }

    private fun enqueueServiceTemplateWrites(capacityAh: Double, series: Int) {
        val fileName = if (serviceTemplateKey == "24v") "service_template_24v.json" else "service_template_12v.json"
        val template = try {
            loadBmsConfigTemplate(fileName)
        } catch (e: Exception) {
            toast("Не удалось загрузить шаблон: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val family = currentHardwareFamily()
        val queue = java.util.ArrayDeque<RemoteWriteCommand>()
        var id = 1
        serviceWriteResults.clear()
        serviceWriteCapacityAh = capacityAh
        id = enqueueSeriesCountWrite(queue, series, id)
        for (parameter in template.parameters) {
            if (parameter.key == "series_cell_count") continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            val raw = Math.round((expected - parameter.offset) * parameter.scale).toInt()
            if (raw < 0 || raw > 0xFFFF) continue
            if (registerMatchesExpected(parameter.register, expected, parameter.scale, parameter.offset, parameter.tolerance)) {
                serviceWriteResults += ServiceWriteResult(
                    key = parameter.key,
                    label = parameter.label,
                    expected = expected,
                    actual = scaledRegisterValue(parameter.register, parameter.scale, parameter.offset),
                    unit = parameter.unit,
                    ok = true,
                    error = null
                )
                continue
            }
            queue.add(
                RemoteWriteCommand(
                    id = id++,
                    key = parameter.key,
                    label = parameter.label,
                    register = parameter.register,
                    rawValue = raw,
                    value = expected,
                    scale = parameter.scale,
                    offset = parameter.offset,
                    unit = parameter.unit,
                    localOnly = true
                )
            )
        }
        if (capacityAlreadyMatches(capacityAh)) {
            serviceWriteResults += ServiceWriteResult(
                key = "nominal_capacity",
                label = "Номинальная емкость",
                expected = capacityAh,
                actual = currentNominalCapacityAh(),
                unit = "Ah",
                ok = true,
                error = null
            )
        } else {
            val milliAh = Math.round(capacityAh * 1000.0).toInt().coerceIn(1, 0x7FFFFFFF)
            val capHi = (milliAh ushr 16) and 0xFFFF
            val capLo = milliAh and 0xFFFF
            queue.add(
                RemoteWriteCommand(
                    id = id,
                    key = "nominal_capacity",
                    label = "Номинальная емкость",
                    register = DALY_NOMINAL_CAPACITY_HI_REG,
                    rawValue = milliAh,
                    value = capacityAh,
                    scale = 1000.0,
                    offset = 0.0,
                    unit = "Ah",
                    localOnly = true,
                    displayValue = capacityAh,
                    writeFrames = listOf(
                        buildModbusWriteSingleRequest(0x81, DALY_REMAINING_CAPACITY_HI_REG, capHi),
                        buildModbusWriteSingleRequest(0x81, DALY_REMAINING_CAPACITY_LO_REG, capLo),
                        buildModbusWriteSingleRequest(0x81, DALY_NOMINAL_CAPACITY_HI_REG, capHi),
                        buildModbusWriteSingleRequest(0x81, DALY_NOMINAL_CAPACITY_LO_REG, capLo)
                    )
                )
            )
        }
        enqueueServicePasswordWrite(queue, id + 1)
        if (queue.isEmpty()) {
            serviceWriteActive = false
            uploadServiceReport()
            showServiceScreen()
            toast(
                if (serviceWriteResults.isEmpty()) "В шаблоне нет параметров для записи"
                else "Все параметры уже совпадают, запись не нужна"
            )
            return
        }
        serviceWriteQueue = queue
        serviceWriteActive = true
        val first = serviceWriteQueue.poll()
        if (first != null) startRemoteWrite(first)
    }

    private fun enqueueSeriesCountWrite(
        queue: java.util.ArrayDeque<RemoteWriteCommand>,
        series: Int,
        nextId: Int
    ): Int {
        if (data.cellCount == series) {
            serviceWriteResults += ServiceWriteResult(
                key = "series_cell_count",
                label = "Количество последовательно соединенных элементов",
                expected = series.toDouble(),
                actual = series.toDouble(),
                unit = "",
                ok = true,
                error = null
            )
            return nextId
        }
        queue.add(
            RemoteWriteCommand(
                id = nextId,
                key = "series_cell_count",
                label = "Количество последовательно соединенных элементов",
                register = 0x0094,
                rawValue = series,
                value = series.toDouble(),
                scale = 1.0,
                offset = 0.0,
                unit = "",
                localOnly = true,
                writeFrames = buildDalyCellCountWriteFrames(series)
            )
        )
        return nextId + 1
    }

    private fun templateParameterHasActual(parameter: BmsTemplateParameter): Boolean {
        return if (parameter.key == "series_cell_count") data.cellCount != null
        else configRegisters.containsKey(parameter.register)
    }

    private fun templateParameterActual(parameter: BmsTemplateParameter): Double? {
        if (parameter.key == "series_cell_count") return data.cellCount?.toDouble()
        val raw = configRegisters[parameter.register] ?: return null
        return (raw / parameter.scale) + parameter.offset
    }

    private fun enqueueServicePasswordWrite(queue: java.util.ArrayDeque<RemoteWriteCommand>, id: Int) {
        if (passwordFromBmsConfig() == SERVICE_SETTINGS_PASSWORD) {
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = "settings_password",
                    label = "Пароль настроек",
                    expected = 113355.0,
                    actual = 113355.0,
                    unit = "",
                    ok = true,
                    error = null
                )
            )
            return
        }
        val words = passwordToRegisterValues(SERVICE_SETTINGS_PASSWORD) ?: return
        queue.add(
            RemoteWriteCommand(
                id = id,
                key = "settings_password",
                label = "Пароль настроек",
                register = DALY_PASSWORD_REG_0,
                rawValue = words[0],
                value = 113355.0,
                scale = 1.0,
                offset = 0.0,
                unit = "",
                localOnly = true,
                writeFrames = listOf(
                    buildModbusWriteSingleRequest(0x81, DALY_PASSWORD_REG_0, words[0]),
                    buildModbusWriteSingleRequest(0x81, DALY_PASSWORD_REG_0 + 1, words[1]),
                    buildModbusWriteSingleRequest(0x81, DALY_PASSWORD_REG_0 + 2, words[2])
                )
            )
        )
    }

    private fun passwordToRegisterValues(password: String): IntArray? {
        if (password.length != 6 || !password.all { it.isDigit() }) return null
        return IntArray(3) { index ->
            val hi = password[index * 2].code
            val lo = password[index * 2 + 1].code
            (hi shl 8) or lo
        }
    }

    private fun completeServiceWriteStep(
        command: RemoteWriteCommand,
        ok: Boolean,
        actual: Double?,
        error: String?
    ) {
        if (command.key != "nominal_capacity" && command.key != "settings_password") {
            serviceWriteResults += ServiceWriteResult(
                key = command.key,
                label = command.label,
                expected = command.displayValue ?: command.value,
                actual = actual,
                unit = command.unit,
                ok = ok,
                error = if (ok) null else error
            )
        }
        val next = serviceWriteQueue.poll()
        if (next != null) {
            mainHandler.postDelayed({ startRemoteWrite(next) }, 120L)
        } else {
            serviceWriteActive = false
            pendingServiceWriteFinalVerify = true
            if (configReadInProgress) {
                serviceVerifyNeedsReadAfterCurrent = true
            } else {
                startConfigReadIfNeeded(force = true)
                if (!configReadInProgress) {
                    finishServiceBatchWrite()
                }
            }
        }
    }

    private fun finishServiceBatchWrite() {
        pendingServiceWriteFinalVerify = false
        finalizeServiceWriteResults()
        uploadServiceReport()
        val failed = serviceWriteResults.count { !it.ok }
        toast(
            when {
                serviceWriteResults.isEmpty() -> "Запись завершена"
                failed == 0 -> "Шаблон записан"
                else -> "Запись завершена с ошибками ($failed)"
            }
        )
        showServiceScreen()
    }

    private fun finalizeServiceWriteResults() {
        val target = serviceWriteCapacityAh
        if (target != null) {
            val actual = configCapacityAhFromHiLo(DALY_REMAINING_CAPACITY_HI_REG, DALY_REMAINING_CAPACITY_LO_REG)
                ?: configCapacityAhFromHiLo(DALY_NOMINAL_CAPACITY_HI_REG, DALY_NOMINAL_CAPACITY_LO_REG)
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = "nominal_capacity",
                    label = "Номинальная емкость",
                    expected = target,
                    actual = actual,
                    unit = "Ah",
                    ok = capacityMatchesTarget(target),
                    error = if (capacityMatchesTarget(target)) null else "not_confirmed"
                )
            )
        }
        val password = passwordFromBmsConfig()
        val passwordOk = password == SERVICE_SETTINGS_PASSWORD
        upsertServiceWriteResult(
            ServiceWriteResult(
                key = "settings_password",
                label = "Пароль настроек",
                expected = SERVICE_SETTINGS_PASSWORD.toDoubleOrNull(),
                actual = password?.toDoubleOrNull(),
                unit = "",
                ok = passwordOk,
                error = if (passwordOk) null else "not_confirmed"
            )
        )
        val expectedSeries = if (serviceTemplateKey == "24v") 8 else 4
        upsertServiceWriteResult(
            ServiceWriteResult(
                key = "series_cell_count",
                label = "Количество последовательно соединенных элементов",
                expected = expectedSeries.toDouble(),
                actual = data.cellCount?.toDouble(),
                unit = "",
                ok = data.cellCount == expectedSeries,
                error = if (data.cellCount == expectedSeries) null else "not_confirmed"
            )
        )
    }

    private fun upsertServiceWriteResult(result: ServiceWriteResult) {
        val existing = serviceWriteResults.indexOfFirst { it.key == result.key }
        if (existing >= 0) {
            serviceWriteResults[existing] = result
        } else {
            serviceWriteResults += result
        }
    }

    private fun uploadServiceReport() {
        val items = JSONArray()
        for (item in serviceWriteResults) {
            items.put(JSONObject().apply {
                put("key", item.key)
                put("label", item.label)
                if (item.expected != null && item.expected.isFinite()) put("expected", item.expected)
                if (item.actual != null && item.actual.isFinite()) put("actual", item.actual)
                put("unit", item.unit)
                put("ok", item.ok)
                if (!item.error.isNullOrBlank()) put("error", item.error)
            })
        }
        val failed = serviceWriteResults.count { !it.ok }
        val status = when {
            serviceWriteResults.isEmpty() || failed == serviceWriteResults.size -> "failed"
            failed == 0 -> "ok"
            else -> "partial"
        }
        val body = JSONObject().apply {
            put("api_key", SERVER_API_KEY)
            put("bms_uid", bmsUid())
            put("bluetooth_name", dalyBluetoothDeviceId())
            put("bluetooth_address", selectedAddress ?: "")
            put("bluetooth_id", bluetoothId())
            put("bms_sn", displayFactorySn())
            put("bms_battery_code", bmsBatteryCode())
            put("bms_hw_version", bmsHwVersionText())
            put("bms_version", bmsHardwareVersion())
            put("hardware_family", currentHardwareFamily())
            put("source", "service")
            put("assembler_name", assemblerName())
            put("template_id", if (serviceTemplateKey == "24v") "liferych-lfp-24v" else "liferych-lfp-12v")
            serviceWriteCapacityAh?.let { put("capacity_ah", it) }
            put("written_at", System.currentTimeMillis())
            put("status", status)
            put("items", items)
        }
        thread {
            adminJsonRequest("POST", LOCAL_SERVICE_REPORT_PATH, body)
        }
    }

    private fun showManageScreen() {
        if (configRegisters.isEmpty() && !configReadInProgress) loadCachedConfigForCurrentBms()

        screenState = "manage"
        currentTab = "manage"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(header("Управление BMS", selectedDeviceName.ifBlank { selectedAddress ?: "" }, "Bluetooth\nподключен"))

        val fixedTabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        fixedTabs.addView(manageTabs(), LinearLayout.LayoutParams(-1, dp(46)))
        root.addView(fixedTabs, LinearLayout.LayoutParams(-1, -2))

        val scroll = SectionSwipeScrollView(this) { direction ->
            switchManageSection(direction)
        }
        manageContentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        scroll.addView(manageContentLayout)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(fixedBottomNav("manage"), LinearLayout.LayoutParams(-1, dp(70)))

        setContentView(root)
        renderManageContent()
    }

    private fun switchManageSection(direction: Int) {
        val sections = listOf("general", "voltage_current", "temperature", "balancing")
        val currentIndex = sections.indexOf(manageSection).coerceAtLeast(0)
        val nextIndex = (currentIndex + direction).coerceIn(sections.indices)
        if (nextIndex == currentIndex) return
        manageSection = sections[nextIndex]
        showManageScreen()
    }

    private fun renderManageContent() {
        if (!::manageContentLayout.isInitialized) return
        manageContentLayout.removeAllViews()

        if (manageSection == "general") {
            manageContentLayout.addView(
                templateCheckCard(),
                marginLp(-1, -2, 0, 0, 0, 10)
            )
        }

        manageContentLayout.addView(TextView(this).apply {
            text = when (manageSection) {
                "general" -> "Основные параметры"
                "voltage_current" -> "Напряжение / ток"
                "temperature" -> "Температура"
                "balancing" -> "Защиты и балансировка"
                "cell" -> "Параметры элемента"
                else -> "Основные параметры"
            }
            textSize = 22f
            typeface = interFont(700)
            setTextColor(Color.rgb(30, 30, 30))
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })

        when (manageSection) {
            "general" -> renderManageGeneral()
            "voltage_current" -> renderManageVoltageCurrent()
            "temperature" -> renderManageTemperature()
            "balancing" -> {
                renderManageBalancing()
                renderManageCellParameters()
            }
            "cell" -> renderManageCellParameters()
            else -> renderManageGeneral()
        }
    }

    private fun manageTabs(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val tabs = listOf(
            "general" to "Основные",
            "voltage_current" to "Напряжение",
            "temperature" to "Температура",
            "balancing" to "Защиты"
        )
        for ((key, title) in tabs) {
            row.addView(manageTabButton(key, title), marginLp(-2, dp(42), 0, 0, 8, 0))
        }
        scroll.addView(row)
        return scroll
    }

    private fun manageTabButton(key: String, title: String): TextView {
        val selected = manageSection == key
        return TextView(this).apply {
            text = title
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = interFont(if (selected) 760 else 650)
            setTextColor(if (selected) redDark else Color.rgb(111, 119, 129))
            setPadding(dp(14), 0, dp(14), 0)
            background = if (selected) {
                round(Color.rgb(255, 248, 218), dp(12), red, 1)
            } else {
                round(Color.WHITE, dp(12), Color.TRANSPARENT, 0)
            }
            setOnClickListener {
                manageSection = key
                showManageScreen()
            }
        }
    }

    private fun renderManageGeneral() {
        addTk10NoticeIfNeeded(
            "Старая BMS R10K: калибровка SOC 0 и SOC 100 не настраивается и не проверяется по шаблону."
        )
        manageContentLayout.addView(manageSectionCard(
            "1. Основные параметры",
            listOf(
                "Тип батареи" to batteryTypeText(),
                "Номинальная емкость" to nominalCapacityText(),
                "Время ожидания сна" to regText(0x0115, 0.1, "S"),
                "Настройка SOC" to fmtPct(data.soc),
                "Калибр. SOC 0" to dlUnsupportedOr(regText(0x01C7, 1000.0, "V")),
                "Калибр. SOC 100" to dlUnsupportedOr(regText(0x0229, 1000.0, "V")),
                "Переключатель зарядки" to mosText(data.chargeMos),
                "Переключатель разрядки" to mosText(data.dischargeMos),
                "Изменить пароль настроек" to "только чтение",
                "Отправить данные в облако" to "OFF"
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))


        if (isServiceApp()) {
            val writeTestCard = card()
            writeTestCard.addView(sectionTitle("Тест записи в BMS", ""))
            writeTestCard.addView(TextView(this).apply {
                val fromBms = passwordFromBmsConfig() ?: "не прочитан"
                text = "Безопасная диагностика: формирует кадры SOC = 90% и показывает выбранный пароль. Команды НЕ отправляются в BMS, пока не найдена точная авторизация пароля.\n\nПароль в BMS: $fromBms\nВыбран для записи: ${selectedWritePassword()}"
                textSize = 13f
                setTextColor(Color.rgb(90,90,90))
                setPadding(0, 0, 0, dp(8))
            })

            val passRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            passRow.addView(passwordButton("123456"), LinearLayout.LayoutParams(0, dp(44), 1f))
            passRow.addView(passwordButton("113355"), marginLp(0, dp(44), 8, 0, 0, 0).apply { weight = 1f })
            writeTestCard.addView(passRow, marginLp(-1, -2, 0, 0, 0, 10))

            writeTestCard.addView(TextView(this).apply {
                text = "Сформировать кадры SOC 90%"
                textSize = 16f
                typeface = interFont(700)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(13), dp(10), dp(13))
                background = round(red, dp(14), Color.TRANSPARENT, 0)
                setOnClickListener { writeTestSoc90ToBms() }
            }, LinearLayout.LayoutParams(-1, -2))

            val stepRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            stepRow1.addView(socStepButton("1. Time", "time"), LinearLayout.LayoutParams(0, dp(46), 1f))
            stepRow1.addView(socStepButton("2. SOC", "soc"), marginLp(0, dp(46), 8, 0, 0, 0).apply { weight = 1f })
            writeTestCard.addView(stepRow1, marginLp(-1, -2, 0, 10, 0, 0))

            val stepRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            stepRow2.addView(socStepButton("3. Service", "service"), LinearLayout.LayoutParams(0, dp(46), 1f))
            stepRow2.addView(socStepButton("Все 3 по очереди", "all"), marginLp(0, dp(46), 8, 0, 0, 0).apply { weight = 1f })
            writeTestCard.addView(stepRow2, marginLp(-1, -2, 0, 8, 0, 0))

            manageContentLayout.addView(writeTestCard, marginLp(-1, -2, 0, 0, 0, 12))
        }


        val crashText = getSharedPreferences("crash_log", MODE_PRIVATE).getString("last_crash", "") ?: ""
        if (crashText.isNotBlank()) {
            manageContentLayout.addView(manageSectionCard(
                "Последний сбой приложения",
                listOf(
                    "Ошибка" to crashText.take(500)
                )
            ), marginLp(-1, -2, 0, 0, 0, 12))
        }

        // Технические кнопки, статусы и диагностика чтения скрыты от пользователя.
        // Конфигурация читается автоматически один раз после подключения и отправляется на сайт.
    }

    private fun renderManageVoltageCurrent() {
        manageContentLayout.addView(manageSectionCard(
            "2. Напряжение / ток",
            listOf(
                "Защита от повышенного напряжения элемента" to settingV(0x0131),
                "Защита от пониженного напряжения элемента" to settingV(0x0135),
                "Защита от повышенного общего напряжения" to settingV(0x0139, 10.0),
                "Защита от пониженного общего напряжения" to settingV(0x013D, 10.0),
                "Защита от перепада напряжения" to settingV(0x015B),
                "Защита от перетока зарядки" to settingA(0x0163),
                "Защита от перетока разрядки" to settingA(0x0164),
                "Ток короткого замыкания" to settingA(0x016E)
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))

        manageContentLayout.addView(manageSectionCard(
            "Текущие значения",
            listOf(
                "Общее напряжение" to fmtV(data.voltage, 3),
                "Ток" to fmtA(data.current),
                "Макс. ячейка" to cellText(data.maxCellNo, data.maxCellV),
                "Мин. ячейка" to cellText(data.minCellNo, data.minCellV),
                "Разбег ячеек" to fmtV(data.cellDiffV, 4),
                "Зарядное подключено" to yesNo(data.chargerConnected),
                "Нагрузка подключена" to yesNo(data.loadConnected)
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))

        val activeVoltageCurrentErrors = data.errors.filter {
            it.contains("напряж", ignoreCase = true) ||
            it.contains("ток", ignoreCase = true) ||
            it.contains("корот", ignoreCase = true) ||
            it.contains("SOC", ignoreCase = true)
        }
        manageContentLayout.addView(manageSectionCard(
            "Активные защиты напряжения / тока",
            if (activeVoltageCurrentErrors.isEmpty()) {
                listOf("Состояние" to "активных ошибок нет")
            } else {
                activeVoltageCurrentErrors.mapIndexed { index, value -> "Ошибка ${index + 1}" to value }
            }
        ), marginLp(-1, -2, 0, 0, 0, 12))
    }

    private fun renderManageTemperature() {
        manageContentLayout.addView(manageSectionCard(
            "3. Настройки температуры",
            listOf(
                "Защита от высокой температуры зарядки" to tempRegText(0x014B),
                "Защита от низкой температуры зарядки" to tempRegText(0x014F),
                "Защита от высокой температуры разрядки" to tempRegText(0x0153),
                "Защита от низкой температуры разрядки" to tempRegText(0x0157),
                "Защита от перепада температур" to tempRegText(0x015F, offset40 = false),
                "Температура включения вентилятора" to tempRegText(0x01F5),
                "Температура включения нагрева" to tempRegText(0x0170, offset40 = false),
                "Температура отключения нагрева" to tempRegText(0x0171, offset40 = false)
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))

        manageContentLayout.addView(manageSectionCard(
            "Текущие температуры",
            listOf(
                "Минимальная температура" to (data.minTemp?.let { "$it°C" } ?: "--"),
                "Максимальная температура" to (data.maxTemp?.let { "$it°C" } ?: "--"),
                "Количество датчиков" to (data.tempCount?.toString() ?: "--")
            ) + data.temps.map { (no, temp) -> "T$no" to "$temp°C" }
        ), marginLp(-1, -2, 0, 0, 0, 12))

        val activeTempErrors = data.errors.filter {
            it.contains("температур", ignoreCase = true) || it.contains("датчик температуры", ignoreCase = true)
        }
        manageContentLayout.addView(manageSectionCard(
            "Активные температурные защиты",
            if (activeTempErrors.isEmpty()) {
                listOf("Состояние" to "активных ошибок нет")
            } else {
                activeTempErrors.mapIndexed { index, value -> "Ошибка ${index + 1}" to value }
            }
        ), marginLp(-1, -2, 0, 0, 0, 12))
    }

    private fun renderManageBalancing() {
        addTk10NoticeIfNeeded(
            "У старой BMS R10K нет активного балансира. Параметры балансировки не проверяются."
        )
        manageContentLayout.addView(manageSectionCard(
            "4. Настройка балансировки",
            listOf(
                "Напряжение включения балансировки" to dlUnsupportedOr(regVoltageOneDecimal(0x011A)),
                "Напряжение отключения балансировки" to dlUnsupportedOr(regText(0x01FB, 1000.0, "V")),
                "Перепад напряжения при открытии балансировки" to dlUnsupportedOr(regText(0x011B, null, "mV")),
                "Ток включения балансировки" to dlUnsupportedOr(regCurrentOneDecimal(0x0151)),
                "Переключатель активной балансировки" to dlUnsupportedOr(balanceSwitchText(0x0220))
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))

        manageContentLayout.addView(manageSectionCard(
            "Текущий разбег ячеек",
            listOf(
                "Макс. ячейка" to cellText(data.maxCellNo, data.maxCellV),
                "Мин. ячейка" to cellText(data.minCellNo, data.minCellV),
                "Разбег" to fmtV(data.cellDiffV, 4)
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))
    }

    private fun renderManageCellParameters() {
        addTk10NoticeIfNeeded(
            "Старая BMS R10K: калибровка SOC 0 и SOC 100 не настраивается и не проверяется по шаблону."
        )
        manageContentLayout.addView(manageSectionCard(
            "5. Параметры элемента",
            listOf(
                "Тип батареи" to batteryTypeText(),
                "Номинальная емкость" to nominalCapacityText(),
                "Время ожидания сна" to regText(0x0115, 0.1, "S"),
                "Настройка SOC" to fmtPct(data.soc),
                "Калибр. SOC 0" to dlUnsupportedOr(regText(0x01C7, 1000.0, "V")),
                "Калибр. SOC 100" to dlUnsupportedOr(regText(0x0229, 1000.0, "V")),
                "Адрес подчиненной платы" to regText(0x020F, null, ""),
                "Тип инвертора" to "не проверяется",
                "Способ связи" to "не проверяется",
                "Протокол одной шины" to "не проверяется"
            )
        ), marginLp(-1, -2, 0, 0, 0, 12))

        if (isServiceApp()) {
            manageContentLayout.addView(manageSectionCard(
                "Диагностика служебных регистров",
                listOf(
                    "Тип инвертора, raw 0x024B" to regText(0x024B, null, ""),
                    "Способ связи, raw 0x024C" to regText(0x024C, null, ""),
                    "Протокол одной шины, raw 0x022A" to regText(0x022A, null, "")
                )
            ), marginLp(-1, -2, 0, 0, 0, 12))
        }

        val cellCount = data.cellCount
        val cellRows = if (cellCount != null) {
            (1..cellCount).map { no ->
                "Ячейка $no" to (data.cells[no]?.let { fmtV(it, 3) } ?: "--")
            }
        } else {
            emptyList()
        }
        manageContentLayout.addView(manageSectionCard(
            "Текущие элементы",
            listOf(
                "Количество ячеек" to (cellCount?.toString() ?: "--"),
                "SOC" to fmtPct(data.soc),
                "Оставшаяся емкость" to fmtAh(data.remainingAh),
                "Расчетная полная емкость" to fmtAh(data.estimatedFullAh)
            ) + cellRows
        ), marginLp(-1, -2, 0, 0, 0, 12))
    }

    private fun readOnlyUnavailable(): String = "не прочитано"

    private fun firstRead(vararg values: String): String {
        return values.firstOrNull { it != readOnlyUnavailable() && it != "--" } ?: readOnlyUnavailable()
    }

    private fun regText(addr: Int, scale: Double?, unit: String): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        val valueText = if (scale != null) {
            val v = raw / scale
            if (unit == "V") {
                "%.3f".format(v).trimTrailingZeros()
            } else if (unit == "A") {
                "%.2f".format(v).trimTrailingZeros()
            } else {
                "%.2f".format(v).trimTrailingZeros()
            }
        } else {
            raw.toString()
        }
        return if (unit.isBlank()) valueText else "$valueText $unit"
    }

    private fun tempRegText(addr: Int, offset40: Boolean = true): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        if (raw == 0xFFFF) return readOnlyUnavailable()
        val value = if (offset40) raw - 40 else raw
        return "$value °C"
    }

    private fun settingV(addr: Int, scale: Double = 1000.0): String = regText(addr, scale, "V")
    private fun settingTemp(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        return "${raw - 40} °C"
    }

    private fun settingTempNoOffset(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        return "$raw °C"
    }

    private fun settingA(addr: Int, scale: Double? = null): String = regText(addr, scale, "A")


    private fun regSwitchText(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        return when (raw) {
            0 -> "Закрыть"
            1 -> "Открыть"
            else -> raw.toString()
        }
    }

    private fun balanceSwitchText(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        return when (raw) {
            0 -> "OFF"
            1, 0xFFFF -> "ON"
            else -> "неизвестно ($raw)"
        }
    }

    private fun regVoltageOneDecimal(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        return "%.1f V".format(raw / 1000.0).replace(",", ".")
    }

    private fun regCurrentOneDecimal(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        return "%.1f A".format(raw / 1000.0).replace(",", ".")
    }

    private fun configNum(addr: Int, scale: Double): Double? {
        val raw = configRegisters[addr] ?: return null
        return raw / scale
    }

    private fun loadBmsConfigTemplate(fileName: String = "bms_config_template.json"): BmsConfigTemplate {
        val root = assets.open(fileName)
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val templateId = root.getString("id")
        val templateVersion = root.getInt("version")
        val chemistry = root.getString("chemistry")
        require(templateId.isNotBlank() && templateVersion > 0 && chemistry.isNotBlank())
        val parametersJson = root.getJSONArray("parameters")
        require(parametersJson.length() > 0)
        val parameters = mutableListOf<BmsTemplateParameter>()
        for (i in 0 until parametersJson.length()) {
            val item = parametersJson.getJSONObject(i)
            val registerText = item.getString("register")
            val register = registerText.removePrefix("0x").removePrefix("0X").toInt(16)
            val expectedBySeries = mutableMapOf<Int, Double>()
            item.optJSONObject("expected_by_series")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    expectedBySeries[key.toInt()] = values.getDouble(key)
                }
            }
            val enforcement = item.getString("enforcement")
            require(enforcement == "required" || enforcement == "informational")
            val scale = item.getDouble("scale")
            require(scale != 0.0)
            val tolerance = item.getDouble("tolerance")
            require(tolerance >= 0.0)
            val expected = if (item.has("expected") && !item.isNull("expected")) {
                item.getDouble("expected")
            } else {
                null
            }
            require(expected != null || expectedBySeries.isNotEmpty())
            val skipFor = buildSet {
                val skipJson = item.optJSONArray("skip_for") ?: return@buildSet
                for (j in 0 until skipJson.length()) {
                    val family = skipJson.optString(j)
                    if (family.isNotBlank()) add(family)
                }
            }
            parameters += BmsTemplateParameter(
                key = item.getString("key"),
                label = item.getString("label"),
                register = register,
                scale = scale,
                offset = item.optDouble("offset", 0.0),
                unit = item.optString("unit", ""),
                tolerance = tolerance,
                enforcement = enforcement,
                expected = expected,
                expectedBySeries = expectedBySeries,
                reason = item.optString("reason").takeIf { it.isNotBlank() },
                skipFor = skipFor
            )
        }
        val supported = root.getJSONArray("supported_series")
        require(supported.length() > 0)
        return BmsConfigTemplate(
            id = templateId,
            version = templateVersion,
            chemistry = chemistry,
            supportedSeries = (0 until supported.length()).map { supported.getInt(it) }.toSet(),
            parameters = parameters
        )
    }

    private fun evaluateTemplateCheck(): TemplateCheckResult {
        val checkedAt = System.currentTimeMillis()
        val observedSeries = data.cellCount
        val series = observedSeries?.takeIf { it == 4 || it == 8 }
        val hardwareFamily = currentHardwareFamily()
        return try {
            val template = loadBmsConfigTemplate()
            val mismatches = mutableListOf<TemplateCheckItem>()
            val missing = mutableListOf<TemplateCheckItem>()
            val unverified = mutableListOf<TemplateCheckItem>()

            for (parameter in template.parameters) {
                val expected = parameter.expected ?: series?.let { parameter.expectedBySeries[it] }
                val actual = templateParameterActual(parameter)
                val hasActual = templateParameterHasActual(parameter)

                if (parameter.enforcement == "informational") {
                    continue
                }

                if (shouldSkipTemplateParameter(parameter, hardwareFamily)) {
                    continue
                }

                if (parameter.expectedBySeries.isNotEmpty() && series == null) {
                    missing += TemplateCheckItem(
                        parameter.key,
                        parameter.label,
                        null,
                        null,
                        parameter.unit,
                        parameter.tolerance,
                        "unsupported_series"
                    )
                } else if (!hasActual) {
                    missing += TemplateCheckItem(
                        parameter.key,
                        parameter.label,
                        expected,
                        null,
                        parameter.unit,
                        parameter.tolerance,
                        "register_missing"
                    )
                } else if (expected == null) {
                    missing += TemplateCheckItem(
                        parameter.key,
                        parameter.label,
                        null,
                        null,
                        parameter.unit,
                        parameter.tolerance,
                        "expected_value_missing"
                    )
                } else if (kotlin.math.abs(actual!! - expected) > parameter.tolerance) {
                    mismatches += TemplateCheckItem(
                        parameter.key,
                        parameter.label,
                        expected,
                        actual,
                        parameter.unit,
                        parameter.tolerance
                    )
                }
            }

            val status = when {
                mismatches.isNotEmpty() -> "mismatch"
                missing.isNotEmpty() || series == null || series !in template.supportedSeries -> "incomplete"
                else -> "ok"
            }
            TemplateCheckResult(
                template.id,
                template.version,
                status,
                checkedAt,
                observedSeries,
                mismatches,
                missing,
                unverified,
                hardwareFamily
            )
        } catch (e: Exception) {
            TemplateCheckResult(
                templateId = "liferych-lfp-default",
                templateVersion = 1,
                status = "incomplete",
                checkedAt = checkedAt,
                seriesCount = observedSeries,
                missing = listOf(
                    TemplateCheckItem(
                        key = "template_load_error",
                        label = "Шаблон конфигурации",
                        expected = null,
                        actual = null,
                        unit = "",
                        tolerance = 0.0,
                        reason = "template_load_error: ${(e.message ?: e.javaClass.simpleName).take(160)}"
                    )
                ),
                hardwareFamily = hardwareFamily
            )
        }
    }

    private fun setTemplateCheckResult(result: TemplateCheckResult) {
        latestTemplateCheck = result
        templateChecksByBms[bmsUid()] = result
    }

    private fun setTemplateCheckChecking() {
        setTemplateCheckResult(
            TemplateCheckResult(
                templateId = "liferych-lfp-default",
                templateVersion = 1,
                status = "checking",
                checkedAt = 0L,
                seriesCount = data.cellCount,
                hardwareFamily = currentHardwareFamily()
            )
        )
    }

    private fun scheduleExactSocWriteTest() {
        if (!TEST_AUTO_WRITE_SOC_ON_CONNECT) return
        if (testSocWriteDoneForConnection) return
        testSocWriteDoneForConnection = true
        toast("Тест записи: SOC ${TEST_SOC_PERCENT_ON_CONNECT.toInt()}%")
        mainHandler.postDelayed({ writeExactSocTestToBms() }, 1200)
    }

    @SuppressLint("MissingPermission")
    private fun writeExactSocTestToBms() {
        val gatt = bluetoothGatt
        val ch = writeCharacteristic
        if (gatt == null || ch == null) {
            toast("SOC write: нет BLE write")
            return
        }

        val raw = kotlin.math.round(TEST_SOC_PERCENT_ON_CONNECT * 10.0).toInt()
        val timeFrame = buildDalyTimeFrame()
        val socFrame = buildModbusWriteSingleRequest(0x81, TEST_SOC_REGISTER_ADDR, raw)
        val openFrame = buildModbusWriteSingleRequest(0x81, 0x0174, 0x00A2)

        configRaw["test_soc_write_method"] = "official_hci_sequence: time_0123 -> write_0116 -> open_0174"
        configRaw["test_soc_write_target"] = "${TEST_SOC_PERCENT_ON_CONNECT} percent register=0x%04X raw=$raw".format(TEST_SOC_REGISTER_ADDR)
        configRaw["test_soc_write_time_frame"] = bytesToHex(timeFrame)
        configRaw["test_soc_write_soc_frame"] = bytesToHex(socFrame)
        configRaw["test_soc_write_open_frame"] = bytesToHex(openFrame)

        fun writeFrame(name: String, frame: ByteArray) {
            try {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = frame
                val ok = gatt.writeCharacteristic(ch)
                configRaw["test_soc_write_$name"] = if (ok) "ok" else "failed"
                refreshManageIfVisible()
            } catch (e: Exception) {
                configRaw["test_soc_write_${name}_error"] = e.message ?: e.toString()
            }
        }

        writeFrame("time_0123", timeFrame)
        mainHandler.postDelayed({ writeFrame("soc_0116", socFrame) }, 120)
        mainHandler.postDelayed({ writeFrame("open_0174", openFrame) }, 350)

        mainHandler.postDelayed({ pollOnce() }, 1100)
        mainHandler.postDelayed({
            val actual = data.soc
            val okAfter = actual != null && kotlin.math.abs(actual - TEST_SOC_PERCENT_ON_CONNECT) <= 1.0
            configRaw["test_soc_write_check"] = if (okAfter) {
                "OK actual_soc=$actual"
            } else {
                "NOT_CONFIRMED actual_soc=${actual ?: "null"}"
            }
            rememberCurrentBmsState()
            refreshManageIfVisible()
            toast(if (okAfter) "SOC write OK: ${fmtPct(actual)}" else "SOC write не подтвержден: ${fmtPct(actual)}")
        }, 3500)
    }


    private fun passwordPrefs(): SharedPreferences {
        return getSharedPreferences("bms_write_passwords", MODE_PRIVATE)
    }

    private fun writePasswordKey(): String {
        return "password_${bmsUid()}"
    }

    private fun selectedWritePassword(): String {
        val fromBms = passwordFromBmsConfig()
        val def = when (fromBms) {
            "123456", "113355" -> fromBms
            else -> "113355"
        }
        return passwordPrefs().getString(writePasswordKey(), def) ?: def
    }

    private fun saveSelectedWritePassword(password: String) {
        passwordPrefs().edit().putString(writePasswordKey(), password).apply()
    }

    private fun passwordButton(password: String): TextView {
        val selected = selectedWritePassword() == password
        return TextView(this).apply {
            text = password
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(if (selected) Color.WHITE else red)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = round(if (selected) red else Color.WHITE, dp(12), red, dp(1))
            setOnClickListener {
                saveSelectedWritePassword(password)
                toast("Выбран пароль BMS: $password")
                showManageScreen()
            }
        }
    }

    private fun passwordFromBmsConfig(): String? {
        val regs = listOf(
            configRegisters[0x0126],
            configRegisters[0x0127],
            configRegisters[0x0128]
        )
        if (regs.any { it == null }) return null

        val chars = StringBuilder()
        for (r in regs) {
            val v = r ?: return null
            val hi = (v shr 8) and 0xFF
            val lo = v and 0xFF
            if (hi != 0) chars.append(hi.toChar())
            if (lo != 0) chars.append(lo.toChar())
        }

        val s = chars.toString().trim()
        return if (s.matches(Regex("\\d{6}"))) s else null
    }

    private fun canUseSelectedPasswordForWrite(): Boolean {
        val selected = selectedWritePassword()
        val fromBms = passwordFromBmsConfig()
        configRaw["write_password_selected"] = selected
        configRaw["write_password_from_bms"] = fromBms ?: "not_read"

        // Если пароль из BMS прочитан — требуем совпадение.
        // Если не прочитан, разрешаем тест, но пишем это в диагностику.
        return fromBms == null || fromBms == selected
    }


    private fun socStepButton(title: String, step: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            typeface = interFont(700)
            setTextColor(red)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = round(Color.WHITE, dp(12), red, dp(1))
            setOnClickListener {
                confirmAndSendSocStep(step)
            }
        }
    }

    private fun confirmAndSendSocStep(step: String) {
        val selectedPassword = selectedWritePassword()
        val bmsPassword = passwordFromBmsConfig()
        val passNote = "Пароль выбран: $selectedPassword\\nПароль в BMS: ${bmsPassword ?: "не прочитан"}"
        val title = when (step) {
            "time" -> "Отправить Time Sync?"
            "soc" -> "Отправить запись SOC?"
            "service" -> "Отправить Service?"
            else -> "Отправить все 3 кадра?"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$passNote\\n\\nОтправка будет выполнена вручную. Если приложение снова упадёт, будет понятно, на каком шаге.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отправить") { _, _ ->
                sendSocWriteStep(step)
            }
            .show()
    }

    private fun socTestFrames(): Triple<ByteArray, ByteArray, ByteArray> {
        val raw = kotlin.math.round(TEST_SOC_PERCENT_ON_CONNECT * 10.0).toInt()
        val timeFrame = buildDalyTimeSyncFrame()
        val writeSocFrame = buildModbusWriteSingleRegister(0x81, TEST_SOC_REGISTER_ADDR, raw)
        val serviceFrame = buildModbusWriteSingleRegister(0x81, 0x0174, 0x00A2)
        configRaw["soc_step_target"] = "${TEST_SOC_PERCENT_ON_CONNECT} percent register=0x%04X raw=$raw".format(TEST_SOC_REGISTER_ADDR)
        configRaw["soc_step_time_sync"] = bytesToHex(timeFrame)
        configRaw["soc_step_soc_write"] = bytesToHex(writeSocFrame)
        configRaw["soc_step_service"] = bytesToHex(serviceFrame)
        return Triple(timeFrame, writeSocFrame, serviceFrame)
    }

    @SuppressLint("MissingPermission")
    private fun sendSocWriteStep(step: String) {
        if (!hasBlePermissions()) {
            toast("Нет разрешения Bluetooth")
            return
        }
        val gatt = bluetoothGatt
        val ch = writeCharacteristic
        if (gatt == null || ch == null) {
            toast("Нет BLE подключения/write characteristic")
            return
        }

        val (timeFrame, socFrame, serviceFrame) = socTestFrames()
        val queue = when (step) {
            "time" -> listOf("time" to timeFrame)
            "soc" -> listOf("soc" to socFrame)
            "service" -> listOf("service" to serviceFrame)
            else -> listOf("time" to timeFrame, "soc" to socFrame, "service" to serviceFrame)
        }

        configRaw["soc_step_mode"] = "REAL_BLE_STEP_$step"
        configRaw["soc_step_password_selected"] = selectedWritePassword()
        configRaw["soc_step_password_from_bms"] = passwordFromBmsConfig() ?: "not_read"
        refreshManageIfVisible()

        // На время ручной записи останавливаем обычный опрос, чтобы команды не перемешивались.
        val wasPolling = polling
        polling = false

        fun writeAt(index: Int) {
            if (index >= queue.size) {
                mainHandler.postDelayed({
                    polling = wasPolling
                    pollOnce()
                }, 600)

                mainHandler.postDelayed({
                    configRaw["soc_step_check_after_$step"] = "actual_soc=${data.soc ?: "null"}"
                    rememberCurrentBmsState()
                    refreshManageIfVisible()
                    toast("Шаг $step выполнен, SOC=${fmtPct(data.soc)}")
                }, 2500)
                return
            }

            val (label, frame) = queue[index]
            try {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = frame
                val ok = gatt.writeCharacteristic(ch)
                configRaw["soc_step_write_$label"] = if (ok) "write_started ${bytesToHex(frame)}" else "write_failed ${bytesToHex(frame)}"
                refreshManageIfVisible()
            } catch (e: Exception) {
                configRaw["soc_step_error_$label"] = e.stackTraceToString()
                polling = wasPolling
                refreshManageIfVisible()
                toast("Ошибка BLE $label: ${e.message}")
                return
            }

            mainHandler.postDelayed({ writeAt(index + 1) }, 900)
        }

        writeAt(0)
    }

    private fun writeTestSoc90ToBms() {
        try {
            val selectedPassword = selectedWritePassword()
            val bmsPassword = passwordFromBmsConfig()
            val raw = kotlin.math.round(TEST_SOC_PERCENT_ON_CONNECT * 10.0).toInt()
            val timeFrame = buildDalyTimeSyncFrame()
            val writeSocFrame = buildModbusWriteSingleRegister(0x81, TEST_SOC_REGISTER_ADDR, raw)
            val serviceFrame = buildModbusWriteSingleRegister(0x81, 0x0174, 0x00A2)

            configRaw["soc_write_mode"] = "SAFE_NO_BLE_WRITE"
            configRaw["soc_write_password_selected"] = selectedPassword
            configRaw["soc_write_password_from_bms"] = bmsPassword ?: "not_read"
            configRaw["soc_write_time_sync_frame"] = bytesToHex(timeFrame)
            configRaw["soc_write_frame"] = bytesToHex(writeSocFrame)
            configRaw["soc_write_service_frame"] = bytesToHex(serviceFrame)
            configRaw["soc_write_target"] = "${TEST_SOC_PERCENT_ON_CONNECT} percent register=0x%04X raw=$raw".format(TEST_SOC_REGISTER_ADDR)
            configRaw["soc_write_note"] = "Запись в BMS отключена после вылета. Нужен точный HCI-кадр авторизации пароля."

            refreshManageIfVisible()

            val msg = buildString {
                appendLine("SAFE_NO_BLE_WRITE")
                appendLine("BMS: ${bmsUid()}")
                appendLine("selected_password=$selectedPassword")
                appendLine("bms_password=${bmsPassword ?: "not_read"}")
                appendLine("target_soc=${TEST_SOC_PERCENT_ON_CONNECT} percent")
                appendLine("soc_register=0x%04X".format(TEST_SOC_REGISTER_ADDR))
                appendLine("time_sync=${bytesToHex(timeFrame)}")
                appendLine("soc_write=${bytesToHex(writeSocFrame)}")
                appendLine("service=${bytesToHex(serviceFrame)}")
            }

            AlertDialog.Builder(this)
                .setTitle("Кадры SOC подготовлены")
                .setMessage(
                    "Безопасный режим: команды НЕ отправлены в BMS.\\n\\n" +
                    "Пароль выбран: $selectedPassword\\n" +
                    "Пароль в BMS: ${bmsPassword ?: "не прочитан"}\\n\\n" +
                    "SOC write:\\n${bytesToHex(writeSocFrame)}"
                )
                .setNegativeButton("OK", null)
                .setPositiveButton("Скопировать") { _, _ ->
                    copyTextToClipboard("SOC write frames", msg)
                    toast("Кадры скопированы")
                }
                .show()
        } catch (e: Exception) {
            configRaw["soc_safe_prepare_error"] = e.stackTraceToString()
            refreshManageIfVisible()
            toast("Ошибка подготовки кадров: ${e.message}")
        }
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }






    private fun calcModbusCrc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun buildDalyTimeSyncFrame(): ByteArray {
        // Аналог служебной команды оригинального Daly:
        // 81 10 01 23 00 03 06 YY MM DD HH mm ss CRC
        val c = Calendar.getInstance()
        val payload = byteArrayOf(
            0x81.toByte(),
            0x10.toByte(),
            0x01.toByte(),
            0x23.toByte(),
            0x00.toByte(),
            0x03.toByte(),
            0x06.toByte(),
            ((c.get(Calendar.YEAR) - 2000) and 0xFF).toByte(),
            ((c.get(Calendar.MONTH) + 1) and 0xFF).toByte(),
            (c.get(Calendar.DAY_OF_MONTH) and 0xFF).toByte(),
            (c.get(Calendar.HOUR_OF_DAY) and 0xFF).toByte(),
            (c.get(Calendar.MINUTE) and 0xFF).toByte(),
            (c.get(Calendar.SECOND) and 0xFF).toByte()
        )
        val crc = calcModbusCrc16(payload)
        return payload + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }

    private fun buildModbusWriteSingleRegister(slave: Int, addr: Int, value: Int): ByteArray {
        val frame = byteArrayOf(
            (slave and 0xFF).toByte(),
            0x06.toByte(),
            ((addr shr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
            0x00,
            0x00
        )
        val crc = calcModbusCrc16(frame.copyOfRange(0, 6))
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    private fun currentTemplateCheck(): TemplateCheckResult? = templateChecksByBms[bmsUid()]

    private fun formatTemplateNumber(value: Double?): String {
        if (value == null) return "—"
        return "%.3f".format(value).trimTrailingZeros().replace(",", ".")
    }

    private fun templateCheckCard(): LinearLayout {
        val result = currentTemplateCheck()
        val status = result?.status ?: "unknown"
        val color = when (status) {
            "ok" -> Color.rgb(28, 160, 55)
            "mismatch" -> Color.rgb(211, 47, 47)
            "incomplete" -> Color.rgb(224, 150, 0)
            else -> Color.rgb(110, 118, 128)
        }
        val title = when (status) {
            "checking" -> "Проверка конфигурации…"
            "ok" -> "Конфигурация соответствует шаблону"
            "mismatch" -> "Есть отклонения конфигурации"
            "incomplete" -> "Проверка конфигурации неполная"
            else -> "Конфигурация ещё не проверена"
        }
        val checked = result?.checkedAt?.takeIf { it > 0L }?.let {
            java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        } ?: "—"
        val counts = result?.let {
            "Отклонений: ${it.mismatches.size}  •  Нет данных: ${it.missing.size}"
        } ?: "Ожидается чтение настроек BMS"

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = round(Color.argb(24, Color.red(color), Color.green(color), Color.blue(color)), dp(14), color, 1)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = interFont(720)
                setTextColor(color)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Проверено: $checked\n$counts"
                textSize = 12f
                setTextColor(Color.rgb(75, 79, 84))
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun String.trimTrailingZeros(): String {
        return this.replace(Regex("0+$"), "").replace(Regex("\\.$"), "")
    }

    private fun batteryTypeText(): String {
        val raw = configRegisters[0x0100] ?: configRegisters[0x0113]
        return when (raw) {
            null -> "Фосфат лития"
            0, 1 -> "Фосфат лития"
            2 -> "Литий-ион"
            3 -> "Титанат лития"
            else -> "Фосфат лития"
        }
    }

    private fun nominalCapacityAh(): Double? {
        // Номинальную/полную ёмкость для сайта берём из текущих данных BMS:
        // remainingAh / SOC * 100. Это то, что пользователь видит как полную ёмкость АКБ.
        val est = data.estimatedFullAh
        if (est != null && est > 0.0 && est < 2000.0) return est

        val rem = data.remainingAh
        val soc = data.soc
        if (rem != null && rem > 0.0 && soc != null && soc > 1.0 && soc <= 100.0) {
            return rem / (soc / 100.0)
        }

        return null
    }

    private fun nominalCapacityText(): String {
        val cap = nominalCapacityAh()
        if (cap != null) {
            val rounded = kotlin.math.round(cap)
            return if (kotlin.math.abs(cap - rounded) < 0.6) {
                "%.0fАч".format(rounded)
            } else {
                "%.1fАч".format(cap).replace(",", ".")
            }
        }
        return readOnlyUnavailable()
    }

    private fun nominalCapacitySource(): String {
        return when {
            data.estimatedFullAh != null -> "runtime_remaining_ah_div_soc"
            data.remainingAh != null && data.soc != null -> "calculated_from_remaining_ah_and_soc"
            else -> "not_available"
        }
    }

    private fun rawCapacityRegistersJson(): JSONObject {
        val obj = JSONObject()
        for (addr in listOf(0x0109, 0x010A, 0x010B, 0x010C, 0x010D)) {
            if (configRegisters.containsKey(addr)) {
                obj.put("0x%04X".format(addr), configRegisters[addr])
            }
        }
        return obj
    }

    private fun manageSectionCard(title: String, rows: List<Pair<String, String>>): LinearLayout {
        val c = card()
        c.addView(TextView(this).apply {
            text = title
            textSize = 18f
            typeface = interFont(700)
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(0, 0, 0, dp(8))
        })
        for ((label, value) in rows) {
            c.addView(manageRow(label, value))
        }
        return c
    }

    private fun manageRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = round(Color.rgb(247, 248, 250), dp(10), Color.TRANSPARENT, 0)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.rgb(55, 55, 55))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(25, 25, 25))
            gravity = Gravity.RIGHT
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(-1, -2))
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(-1, dp(6)))
        }
    }

    private fun fmtV(v: Double?, digits: Int = 3): String = v?.let { "%.${digits}f V".format(it) } ?: "--"
    private fun fmtA(v: Double?): String = v?.let { "%.3f A".format(it) } ?: "--"
    private fun fmtAh(v: Double?): String = v?.let { "%.1f Ah".format(it) } ?: "--"
    private fun fmtPct(v: Double?): String = v?.let { "%.1f %%".format(it) } ?: "--"
    private fun yesNo(v: Boolean?): String = when (v) { true -> "Да"; false -> "Нет"; null -> "--" }
    private fun mosText(v: Boolean?): String = when (v) { true -> "ON"; false -> "OFF"; null -> "--" }
    private fun cellText(no: Int?, v: Double?): String {
        val value = fmtV(v, 3)
        return if (no != null) "№$no — $value" else value
    }

    private fun refreshManageIfVisible() {
        runOnUiThread {
            if (screenState == "manage") {
                renderManageContent()
            }
            if (screenState == "qtc") {
                renderQtcContent()
            }
            if (screenState == "debug") {
                debugDumpView?.text = bmsRegisterDump()
            }
        }
    }

    private fun configRawSummaryRows(): List<Pair<String, String>> {
        if (configRaw.isEmpty()) {
            return listOf("Диагностика" to "пока нет команд")
        }

        val rows = mutableListOf<Pair<String, String>>()
        val entries: List<Map.Entry<String, String>> = configRaw.entries.toList()
        val startIndex = kotlin.math.max(0, entries.size - 12)

        for (i in startIndex until entries.size) {
            val entry = entries[i]
            val key = entry.key
            val value = entry.value
            val shortValue = if (value.length > 80) {
                value.substring(0, 80) + "..."
            } else {
                value
            }
            rows.add(Pair(key, shortValue))
        }

        return rows
    }

    private fun clearConfigButton(): TextView {
        return TextView(this).apply {
            text = "Очистить сохранённый конфиг"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(red)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = round(Color.WHITE, dp(14), red, dp(1))
            setOnClickListener {
                clearCachedConfigForCurrentBms()
                toast("Сохранённый конфиг очищен")
                renderManageContent()
            }
        }
    }

    private fun readConfigButton(): TextView {
        return TextView(this).apply {
            text = if (configReadInProgress) "Конфиг читается..." else "Обновить конфиг BMS"
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = interFont(700)
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(if (configReadInProgress) Color.rgb(120, 120, 120) else red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                if (!configReadInProgress) {
                    toast("Обновляю конфиг BMS")
                    startConfigReadIfNeeded(force = true)
                    renderManageContent()
                } else {
                    toast("Чтение уже идет")
                }
            }
        }
    }

    private fun safeBmsCacheSuffix(): String {
        return bmsUid().replace(Regex("[^A-Za-z0-9_\\-]"), "_")
    }

    private fun configCacheKey(): String {
        return "config_registers_" + safeBmsCacheSuffix()
    }

    private fun configCacheTimeKey(): String {
        return "config_saved_at_" + safeBmsCacheSuffix()
    }

    private fun loadCachedConfigForCurrentBms() {
        if (!::configPrefs.isInitialized) return
        if (configReadInProgress) return

        val saved = configPrefs.getString(configCacheKey(), null) ?: return

        try {
            val obj = JSONObject(saved)
            configRegisters.clear()

            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val addr = key.removePrefix("0x").toIntOrNull(16) ?: continue
                configRegisters[addr] = obj.optInt(key)
            }

            configLastSavedAt = configPrefs.getLong(configCacheTimeKey(), 0L)
            configLoadedFromCache = configRegisters.isNotEmpty()

            if (configLoadedFromCache) {
                lastConfigStatus = "loaded_from_cache"
            }
        } catch (_: Exception) {
            configLoadedFromCache = false
        }
    }

    private fun saveConfigCacheForCurrentBms() {
        if (!::configPrefs.isInitialized) return
        if (configRegisters.isEmpty()) return

        val obj = JSONObject()
        for ((addr, value) in configRegisters) {
            obj.put("0x%04X".format(addr), value)
        }

        configLastSavedAt = System.currentTimeMillis()
        configPrefs.edit()
            .putString(configCacheKey(), obj.toString())
            .putLong(configCacheTimeKey(), configLastSavedAt)
            .apply()

        configLoadedFromCache = true
    }

    private fun clearCachedConfigForCurrentBms() {
        if (!::configPrefs.isInitialized) return

        configPrefs.edit()
            .remove(configCacheKey())
            .remove(configCacheTimeKey())
            .apply()

        configRegisters.clear()
        configLoadedFromCache = false
        configLastSavedAt = 0L
        lastConfigStatus = "cache_cleared"
        refreshManageIfVisible()
    }

    private fun configSavedAtText(): String {
        if (configLastSavedAt <= 0L) return "--"
        return java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(configLastSavedAt))
    }


    private fun showSupportScreen() {
        screenState = "support"
        currentTab = "support"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(header("Техническая поддержка", selectedDeviceName.ifBlank { selectedAddress ?: "" }, ""))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        top.addView(TextView(this).apply {
            text = "Поддержка"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(750)
            setPadding(0, dp(4), 0, dp(10))
        })
        top.addView(supportSegmentButtons())
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }

        val contactCard = card()
        contactCard.addView(sectionTitle("Контакты техподдержки", ""))
        contactCard.addView(TextView(this).apply {
            text = "ЛИФЕРЫЧ\nТелефон: +7 (932) 078-10-11\nTelegram / MAX / VK\nГрафик: пн–пт 09:00–18:00"
            textSize = 15f
            setTextColor(Color.rgb(45,45,45))
            setLineSpacing(4f, 1f)
        })
        content.addView(contactCard, marginLp(-1, -2, 0, 0, 0, 10))

        when (supportMode) {
            "new" -> renderWarrantyForm(content, null)
            "edit" -> renderWarrantyForm(content, editingWarrantyLocalId)
            else -> renderWarrantyList(content)
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("support"), LinearLayout.LayoutParams(-1, dp(70)))

        setContentView(root)
        updateWarrantyMediaText()
        if (supportMode == "list") refreshWarrantyStatuses(false)
    }

    private fun showAuthScreen() {
        screenState = "auth"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header("Вход", "", ""))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "Вход в профиль"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 14))
        content.addView(TextView(this).apply {
            text = "☎   Вход по номеру телефона\nУкажите номер. В тестовом режиме используйте код 1234."
            textSize = 13f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = round(Color.rgb(246, 247, 249), dp(18), Color.rgb(223, 229, 235), 1)
        })
        val phone = EditText(this).apply {
            hint = "+7 (___) ___-__-__"
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
        }
        content.addView(TextView(this).apply {
            text = "Номер телефона"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 16, 0, 6))
        content.addView(phone, LinearLayout.LayoutParams(-1, dp(52)))

        val codeBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val code = EditText(this).apply {
            hint = "••••"
            textSize = 22f
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setSingleLine(true)
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
        }
        codeBlock.addView(TextView(this).apply {
            text = "Код подтверждения · демо-код 1234"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
        }, marginLp(-1, -2, 0, 14, 0, 6))
        codeBlock.addView(code, LinearLayout.LayoutParams(-1, dp(52)))
        codeBlock.addView(TextView(this).apply {
            text = "ВВЕСТИ КОД"
            textSize = 15f
            typeface = interFont(780)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                if (code.text.toString() != "1234") {
                    toast("Неверный демо-код")
                } else {
                    getSharedPreferences("user_profile", MODE_PRIVATE).edit()
                        .putBoolean("logged_in", true)
                        .putString("phone", phone.text.toString())
                        .apply()
                    showProfileScreen()
                }
            }
        }, marginLp(-1, dp(52), 0, 14, 0, 0))
        content.addView(codeBlock)

        content.addView(TextView(this).apply {
            text = "ПОЛУЧИТЬ ЗВОНОК"
            textSize = 15f
            typeface = interFont(780)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                if (phone.text.toString().filter(Char::isDigit).length < 10) {
                    toast("Введите номер телефона")
                } else {
                    codeBlock.visibility = View.VISIBLE
                    visibility = View.GONE
                    code.requestFocus()
                    toast("Демо-звонок отправлен. Код: 1234")
                }
            }
        }, marginLp(-1, dp(52), 0, 16, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun adminServerBaseUrl(): String {
        return normalizeAdminServerBaseUrl(
            serverPrefs.getString("base_url", DEFAULT_ADMIN_SERVER_BASE_URL).orEmpty()
        )
    }

    private fun saveAdminServerBaseUrl(input: String) {
        serverPrefs.edit()
            .putString("base_url", normalizeAdminServerBaseUrl(input))
            .apply()
    }

    private fun adminServerUrl(path: String): String {
        return adminServerBaseUrl().trimEnd('/') + path
    }

    private fun normalizeAdminServerBaseUrl(input: String): String {
        var value = input.trim()
        if (value.isBlank()) value = DEFAULT_ADMIN_SERVER_BASE_URL
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }
        return value.trimEnd('/')
    }

    private fun showProfileScreen() {
        if (isServiceApp()) {
            showServiceProfileScreen()
            return
        }
        screenState = "profile"
        currentTab = "profile"
        val prefs = getSharedPreferences("user_profile", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header("Профиль", "", ""))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "Профиль"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 14))

        val avatarWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = round(Color.rgb(246, 247, 249), dp(52), redDark, 2)
            clipToOutline = true
            val savedUri = prefs.getString("avatar_uri", "").orEmpty()
            if (savedUri.isNotBlank()) {
                try {
                    setImageURI(Uri.parse(savedUri))
                } catch (_: Exception) {
                    setImageResource(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        }
        avatarWrap.addView(avatar, LinearLayout.LayoutParams(dp(104), dp(104)))
        avatarWrap.addView(TextView(this).apply {
            text = "✎  Изменить фото"
            textSize = 12f
            typeface = interFont(760)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
            setOnClickListener { pickProfileAvatar() }
        }, marginLp(-2, dp(40), 0, 10, 0, 10))
        content.addView(avatarWrap)

        val profileCard = card()
        fun field(label: String, value: String, hint: String): EditText {
            profileCard.addView(TextView(this).apply {
                text = label
                textSize = 12f
                typeface = interFont(700)
                setTextColor(Color.rgb(111, 119, 129))
                setPadding(0, dp(10), 0, dp(5))
            })
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                textSize = 15f
                setSingleLine(true)
                setPadding(dp(12), 0, dp(12), 0)
                background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
                profileCard.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
            }
        }
        val name = field("ФИО", prefs.getString("name", "") ?: "", "Иванов Иван Иванович")
        val phone = field("Номер телефона", prefs.getString("phone", "") ?: "", "+7 (___) ___-__-__")
        val email = field("Email", prefs.getString("email", "") ?: "", "name@example.ru")
        val birth = field("Дата рождения", prefs.getString("birth", "") ?: "", "ДД.ММ.ГГГГ")
        val adminServerUrl = field(
            "Адрес локальной админки",
            adminServerBaseUrl(),
            "http://192.168.70.142:3000"
        )
        profileCard.addView(TextView(this).apply {
            text = "Телеметрия BMS будет отправляться на этот компьютер по адресу ${LOCAL_UPLOAD_PATH}. Если работаете с другого ПК, поменяйте IP и нажмите «Сохранить»."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(profileCard)
        content.addView(TextView(this).apply {
            text = "СОХРАНИТЬ"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                prefs.edit().putString("name", name.text.toString())
                    .putString("phone", phone.text.toString())
                    .putString("email", email.text.toString())
                    .putString("birth", birth.text.toString()).apply()
                saveAdminServerBaseUrl(adminServerUrl.text.toString())
                toast("Профиль и адрес админки сохранены")
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("profile"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showServiceProfileScreen() {
        screenState = "profile"
        currentTab = "profile"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header("Профиль сборщика", "", ""))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "Сервис"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 14))

        val profileCard = card()
        profileCard.addView(TextView(this).apply {
            text = "Имя сборщика"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, 0, 0, dp(5))
        })
        val name = EditText(this).apply {
            setText(assemblerName())
            hint = "Фамилия Имя"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
        }
        profileCard.addView(name, LinearLayout.LayoutParams(-1, dp(52)))
        profileCard.addView(TextView(this).apply {
            text = "Адрес локальной админки"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, dp(12), 0, dp(5))
        })
        val adminServerUrl = EditText(this).apply {
            setText(adminServerBaseUrl())
            hint = "http://192.168.70.142:3000"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
        }
        profileCard.addView(adminServerUrl, LinearLayout.LayoutParams(-1, dp(52)))
        profileCard.addView(TextView(this).apply {
            text = "Телеметрия уходит на этот компьютер даже без имени сборщика. Имя нужно только когда записываете шаблон 12В/24В."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(profileCard)
        content.addView(TextView(this).apply {
            text = "СОХРАНИТЬ"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                val assembler = name.text.toString().trim()
                servicePrefs.edit().putString("assembler_name", assembler).apply()
                saveAdminServerBaseUrl(adminServerUrl.text.toString())
                toast(
                    if (assembler.isBlank()) {
                        "Адрес сохранён. Имя сборщика нужно только для записи шаблона."
                    } else {
                        "Профиль сборщика сохранён"
                    }
                )
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("profile"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }


    private fun supportSegmentButtons(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun seg(title: String, mode: String): TextView {
            val selected = supportMode == mode || (mode == "list" && supportMode == "edit")
            return TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = interFont(700)
                setTextColor(if (selected) Color.WHITE else red)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = round(if (selected) red else Color.WHITE, dp(14), red, dp(1))
                setOnClickListener {
                    supportMode = mode
                    if (mode == "new") editingWarrantyLocalId = null
                    showSupportScreen()
                }
            }
        }
        row.addView(seg("Ваши обращения", "list"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(seg("Новое обращение", "new"), marginLp(0, -2, 8, 0, 0, 0).apply { weight = 1f })
        return row
    }

    private fun renderWarrantyList(content: LinearLayout) {
        val c = card()
        c.addView(sectionTitle("Ваши обращения", ""))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = "＋ Новое обращение"
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(11), dp(8), dp(11))
            background = round(red, dp(12), Color.TRANSPARENT, 0)
            setOnClickListener {
                supportMode = "new"
                editingWarrantyLocalId = null
                clearWarrantyFormState()
                showSupportScreen()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "↻ Обновить статусы"
            textSize = 15f
            typeface = interFont(700)
            setTextColor(red)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(11), dp(8), dp(11))
            background = round(Color.WHITE, dp(12), red, dp(1))
            setOnClickListener { refreshWarrantyStatuses(true) }
        }, marginLp(0, -2, 8, 0, 0, 0).apply { weight = 1f })
        c.addView(row, marginLp(-1, -2, 0, 0, 0, 12))

        warrantyListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(warrantyListLayout)
        content.addView(c, marginLp(-1, -2, 0, 0, 0, 10))
        renderWarrantyListItems()
    }

    private fun renderWarrantyListItems() {
        val layout = warrantyListLayout ?: return
        layout.removeAllViews()
        val arr = warrantyRequestsArray()
        val uid = bmsUid()

        val items = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val itemUid = item.optString("bms_uid")
            if (itemUid.isBlank() || itemUid == uid) items.add(item)
        }

        items.sortWith(compareByDescending<JSONObject> {
            it.optString("server_id").toIntOrNull() ?: 0
        }.thenByDescending {
            it.optString("created_at")
        })

        if (items.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "Обращений пока нет. Нажмите «Новое обращение», чтобы создать заявку по гарантии."
                textSize = 14f
                setTextColor(Color.rgb(100,100,100))
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }

        layout.addView(TextView(this).apply {
            text = "Всего обращений по этой BMS: ${items.size}"
            textSize = 13f
            typeface = interFont(700)
            setTextColor(Color.rgb(90,90,90))
            setPadding(0, 0, 0, dp(8))
        })

        for (item in items) {
            layout.addView(warrantyRequestCard(item), marginLp(-1, -2, 0, 0, 0, 10))
        }
    }

    private fun warrantyRequestCard(item: JSONObject): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.rgb(247, 248, 250), dp(12), Color.rgb(225, 228, 235), 1)
        }

        val serverId = item.optString("server_id").ifBlank { "не отправлено" }
        val status = item.optString("status").ifBlank { "draft" }
        val title = if (serverId == "не отправлено") "Локальный черновик" else "Обращение №$serverId"
        c.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = interFont(700)
            setTextColor(Color.rgb(35,35,35))
        })
        c.addView(TextView(this).apply {
            text = "Статус: ${warrantyStatusRu(status)}\nДата: ${item.optString("created_at")}\nBMS: ${item.optString("bms_uid")}\nМодель: ${item.optString("model")}\nПроблема: ${item.optString("problem").take(120)}"
            textSize = 13f
            setTextColor(Color.rgb(80,80,80))
            setPadding(0, dp(6), 0, dp(8))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = "Открыть / редактировать"
            textSize = 14f
            typeface = interFont(700)
            gravity = Gravity.CENTER
            setTextColor(red)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = round(Color.WHITE, dp(12), red, 1)
            setOnClickListener {
                editingWarrantyLocalId = item.optString("local_id")
                supportMode = "edit"
                showSupportScreen()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(row)
        return c
    }

    private fun renderWarrantyForm(content: LinearLayout, localId: String?) {
        val existing = localId?.let { warrantyRequestByLocalId(it) }

        val formCard = card()
        formCard.addView(sectionTitle(if (existing == null) "Новое гарантийное обращение" else "Обращение №${existing.optString("server_id").ifBlank { "черновик" }}", ""))

        if (existing != null) {
            formCard.addView(TextView(this).apply {
                text = "Статус: ${warrantyStatusRu(existing.optString("status"))}"
                textSize = 15f
                typeface = interFont(700)
                setTextColor(if (existing.optString("status") == "done") green else red)
                setPadding(0, 0, 0, dp(8))
            })
        }

        formCard.addView(TextView(this).apply {
            text = "При отправке приложение приложит текущие логи, ошибки и параметры АКБ, если BMS подключена."
            textSize = 13f
            setTextColor(Color.rgb(90,90,90))
            setPadding(0, 0, 0, dp(8))
        })

        warrantyNameEdit = supportInput("ФИО клиента", "Иванов Иван")
        warrantyNameEdit?.setText(existing?.optString("fio") ?: "")
        formCard.addView(warrantyNameEdit)

        warrantyPhoneEdit = supportInput("Телефон", "+7...")
        warrantyPhoneEdit?.setText(existing?.optString("phone") ?: "")
        formCard.addView(warrantyPhoneEdit, marginLp(-1, dp(52), 0, 8, 0, 0))

        warrantyModelEdit = supportInput("Модель АКБ", supportModelDefault())
        warrantyModelEdit?.setText(existing?.optString("model")?.ifBlank { supportModelDefault() } ?: supportModelDefault())
        formCard.addView(warrantyModelEdit, marginLp(-1, dp(52), 0, 8, 0, 0))

        warrantyProblemEdit = supportInput("Описание проблемы", "Что произошло, когда проявилась проблема, как заряжалась/эксплуатировалась АКБ")
        warrantyProblemEdit?.setText(existing?.optString("problem") ?: "")
        warrantyProblemEdit?.minLines = 4
        warrantyProblemEdit?.gravity = Gravity.TOP or Gravity.START
        formCard.addView(warrantyProblemEdit, marginLp(-1, dp(120), 0, 8, 0, 0))

        val mediaButton = TextView(this).apply {
            text = "＋ Прикрепить фото (необязательно)"
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = round(Color.WHITE, dp(12), Color.rgb(202, 211, 220), 1)
            setOnClickListener { pickWarrantyMedia() }
        }
        formCard.addView(mediaButton, marginLp(-1, -2, 0, 10, 0, 0))

        warrantyMediaText = TextView(this).apply {
            text = "Файлы не выбраны"
            textSize = 13f
            setTextColor(Color.rgb(100,100,100))
            setPadding(0, dp(6), 0, dp(6))
        }
        formCard.addView(warrantyMediaText)

        val consent = CheckBox(this).apply {
            text = "Согласен на обработку персональных данных"
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            buttonTintList = android.content.res.ColorStateList.valueOf(redDark)
        }
        formCard.addView(consent, marginLp(-1, -2, 0, 8, 0, 0))

        val sendButton = TextView(this).apply {
            text = "ОТПРАВИТЬ"
            textSize = 16f
            typeface = interFont(780)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(13), dp(10), dp(13))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                if (!consent.isChecked) {
                    toast("Подтвердите согласие на обработку данных")
                } else {
                    submitWarrantyRequest()
                }
            }
        }
        formCard.addView(sendButton, marginLp(-1, -2, 0, 12, 0, 0))

        formCard.addView(TextView(this).apply {
            text = "ДИАГНОСТИКА"
            textSize = 15f
            typeface = interFont(760)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = round(Color.WHITE, dp(14), red, 1)
            setOnClickListener { showSupportDiagnostics() }
        }, marginLp(-1, -2, 0, 10, 0, 0))

        warrantyStatusText = TextView(this).apply {
            text = existing?.let { "Текущий статус: ${warrantyStatusRu(it.optString("status"))}" } ?: ""
            textSize = 13f
            setTextColor(Color.rgb(90,90,90))
        }
        formCard.addView(warrantyStatusText)

        content.addView(formCard, marginLp(-1, -2, 0, 0, 0, 10))
    }

    private fun showSupportDiagnostics() {
        val text = buildString {
            appendLine("Устройство: ${selectedDeviceName.ifBlank { selectedAddress ?: "не выбрано" }}")
            appendLine("Напряжение: ${data.voltage?.let { "%.2f В".format(it) } ?: "--"}")
            appendLine("Ток: ${data.current?.let { "%.2f А".format(it) } ?: "--"}")
            appendLine("SOC: ${data.soc?.let { "%.1f%%".format(it) } ?: "--"}")
            appendLine("Ячеек: ${data.cellCount ?: "--"}")
            appendLine("Температура: ${data.maxTemp?.let { "$it °C" } ?: "--"}")
            append("Ошибки: ${if (data.errors.isEmpty()) "не обнаружены" else data.errors.joinToString()}")
        }
        AlertDialog.Builder(this)
            .setTitle("Диагностика BMS")
            .setMessage(text)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun clearWarrantyFormState() {
        warrantyMediaUris.clear()
        warrantyNameEdit = null
        warrantyPhoneEdit = null
        warrantyModelEdit = null
        warrantyProblemEdit = null
        warrantyMediaText = null
        warrantyStatusText = null
    }

    private fun warrantyStatusRu(status: String): String {
        return when (status) {
            "new" -> "Новое"
            "in_work" -> "В работе"
            "done" -> "Закрыто"
            "sent" -> "Отправлено"
            "draft" -> "Черновик"
            "failed" -> "Ошибка отправки"
            else -> status.ifBlank { "Неизвестно" }
        }
    }

    private fun warrantyRequestsArray(): JSONArray {
        val raw = supportPrefs?.getString("requests", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveWarrantyRequestsArray(arr: JSONArray) {
        supportPrefs?.edit()?.putString("requests", arr.toString())?.apply()
    }

    private fun warrantyRequestByLocalId(localId: String): JSONObject? {
        val arr = warrantyRequestsArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("local_id") == localId) return item
        }
        return null
    }

    private fun upsertWarrantyLocal(item: JSONObject) {
        val arr = warrantyRequestsArray()
        val localId = item.optString("local_id")
        var updated = false
        for (i in 0 until arr.length()) {
            val old = arr.optJSONObject(i) ?: continue
            if (old.optString("local_id") == localId) {
                arr.put(i, item)
                updated = true
                break
            }
        }
        if (!updated) arr.put(item)
        saveWarrantyRequestsArray(arr)
    }

    private fun refreshWarrantyStatuses(showToast: Boolean) {
        val uid = bmsUid()
        if (uid.isBlank() || uid == "unknown_bms") {
            if (showToast) toast("BMS не определена")
            return
        }

        thread {
            try {
                val body = JSONObject().apply {
                    put("api_key", SERVER_API_KEY)
                    put("bms_uid", uid)
                    put("all_by_bms", true)
                }.toString()

                val conn = (URL(SERVER_WARRANTY_LIST_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val response = if (conn.responseCode in 200..299) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } else {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                }
                conn.disconnect()

                val obj = JSONObject(response)
                if (obj.optBoolean("ok")) {
                    val remoteList = obj.optJSONArray("requests") ?: JSONArray()
                    val local = warrantyRequestsArray()

                    for (i in 0 until remoteList.length()) {
                        val remote = remoteList.optJSONObject(i) ?: continue
                        val serverId = remote.optString("id")
                        if (serverId.isBlank()) continue

                        var foundIndex = -1
                        for (j in 0 until local.length()) {
                            val item = local.optJSONObject(j) ?: continue
                            if (item.optString("server_id") == serverId) {
                                foundIndex = j
                                break
                            }
                        }

                        val item = if (foundIndex >= 0) {
                            local.optJSONObject(foundIndex) ?: JSONObject()
                        } else {
                            JSONObject().apply {
                                put("local_id", "server_$serverId")
                                put("server_id", serverId)
                            }
                        }

                        item.put("server_id", serverId)
                        item.put("status", remote.optString("status", item.optString("status", "new")))
                        item.put("admin_comment", remote.optString("admin_comment", item.optString("admin_comment")))
                        item.put("updated_at", remote.optString("updated_at", item.optString("updated_at")))
                        item.put("created_at", remote.optString("created_at", item.optString("created_at")))
                        item.put("bms_uid", remote.optString("bms_uid", uid))
                        item.put("model", remote.optString("battery_model", item.optString("model")))
                        item.put("problem", remote.optString("problem_text", item.optString("problem")))
                        item.put("fio", remote.optString("client_fio", item.optString("fio")))
                        item.put("phone", remote.optString("client_phone", item.optString("phone")))

                        if (foundIndex >= 0) local.put(foundIndex, item) else local.put(item)
                    }

                    saveWarrantyRequestsArray(local)
                    runOnUiThread {
                        renderWarrantyListItems()
                        if (showToast) toast("Список обращений обновлён")
                    }
                } else {
                    runOnUiThread {
                        if (showToast) toast("Ошибка обновления: ${obj.optString("error", response.take(120))}")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (showToast) toast("Ошибка статусов: ${e.message}")
                }
            }
        }
    }

    private fun supportInput(hintText: String, placeholder: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 15f
            setSingleLine(false)
            setTextColor(Color.rgb(35,35,35))
            setHintTextColor(Color.rgb(140,140,140))
            background = round(Color.rgb(247, 248, 250), dp(12), Color.rgb(225, 228, 235), 1)
            setPadding(dp(12), 0, dp(12), 0)
            if (placeholder.isNotBlank()) setHint(hintText + " — " + placeholder)
        }
    }

    private fun supportModelDefault(): String {
        val name = selectedDeviceName.ifBlank { bmsUid() }
        val cells = data.cellCount?.let { "${it}S" } ?: ""
        val capacity = data.estimatedFullAh?.let { "${fmtOne(it)} Ah" } ?: data.remainingAh?.let { "${fmtOne(it)} Ah" } ?: ""
        return listOf(name, cells, capacity).filter { it.isNotBlank() && it != "unknown_bms" }.joinToString(" / ").ifBlank { "LiFePO4 АКБ" }
    }

    private fun fmtOne(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun pickWarrantyMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, WARRANTY_MEDIA_REQUEST_CODE)
    }

    private fun pickProfileAvatar() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, PROFILE_AVATAR_REQUEST_CODE)
    }

    private fun updateWarrantyMediaText() {
        warrantyMediaText?.text = if (warrantyMediaUris.isEmpty()) {
            "Файлы не выбраны"
        } else {
            "Выбрано файлов: ${warrantyMediaUris.size}"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK || resultData == null) return
        if (requestCode == PROFILE_AVATAR_REQUEST_CODE) {
            val uri = resultData.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            getSharedPreferences("user_profile", MODE_PRIVATE).edit()
                .putString("avatar_uri", uri.toString())
                .apply()
            showProfileScreen()
            return
        }
        if (requestCode != WARRANTY_MEDIA_REQUEST_CODE) return

        val clip = resultData.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                if (warrantyMediaUris.size < 5) warrantyMediaUris.add(uri)
            }
        } else {
            resultData.data?.let { if (warrantyMediaUris.size < 5) warrantyMediaUris.add(it) }
        }
        updateWarrantyMediaText()
        toast("Файлы добавлены: ${warrantyMediaUris.size}")
    }

    private fun submitWarrantyRequest() {
        val fio = warrantyNameEdit?.text?.toString()?.trim().orEmpty()
        val phone = warrantyPhoneEdit?.text?.toString()?.trim().orEmpty()
        val model = warrantyModelEdit?.text?.toString()?.trim().orEmpty().ifBlank { supportModelDefault() }
        val problem = warrantyProblemEdit?.text?.toString()?.trim().orEmpty()

        if (fio.isBlank() || phone.isBlank() || problem.isBlank()) {
            toast("Заполните ФИО, телефон и описание проблемы")
            return
        }

        val localId = editingWarrantyLocalId ?: "local_${System.currentTimeMillis()}"
        val existing = warrantyRequestByLocalId(localId)

        val payload = JSONObject()
        payload.put("api_key", SERVER_API_KEY)
        payload.put("client_fio", fio)
        payload.put("client_phone", phone)
        payload.put("battery_model", model)
        payload.put("problem_text", problem)
        payload.put("bms_uid", bmsUid())
        payload.put("bluetooth_name", dalyBluetoothDeviceId())
        payload.put("bluetooth_address", selectedAddress ?: "")
        payload.put("app_version", "v57-step-soc-write")
        payload.put("battery_snapshot", buildUploadJson())
        if (configRegisters.isNotEmpty()) payload.put("config_snapshot", buildConfigUploadJson())

        existing?.optString("server_id")?.takeIf { it.isNotBlank() }?.let {
            payload.put("request_id", it)
        }

        val localItem = JSONObject().apply {
            put("local_id", localId)
            put("server_id", existing?.optString("server_id") ?: "")
            put("status", existing?.optString("status")?.ifBlank { "draft" } ?: "draft")
            put("created_at", existing?.optString("created_at")?.ifBlank { nowText() } ?: nowText())
            put("updated_at", nowText())
            put("fio", fio)
            put("phone", phone)
            put("model", model)
            put("problem", problem)
            put("bms_uid", bmsUid())
        }
        upsertWarrantyLocal(localItem)
        editingWarrantyLocalId = localId

        warrantyStatusText?.text = "Отправка обращения..."
        thread {
            val result = sendWarrantyMultipart(payload, warrantyMediaUris.toList())
            runOnUiThread {
                val id = Regex("№(\\d+)").find(result)?.groupValues?.getOrNull(1)
                val updated = warrantyRequestByLocalId(localId) ?: localItem
                if (result.startsWith("Обращение отправлено")) {
                    if (!id.isNullOrBlank()) updated.put("server_id", id)
                    updated.put("status", "sent")
                    updated.put("updated_at", nowText())
                    upsertWarrantyLocal(updated)
                    warrantyStatusText?.text = "Обращение отправлено. Статус: ${warrantyStatusRu(updated.optString("status"))}"
                    toast(result)
                    supportMode = "list"
                    editingWarrantyLocalId = null
                    showSupportScreen()
                } else {
                    updated.put("status", "failed")
                    updated.put("updated_at", nowText())
                    upsertWarrantyLocal(updated)
                    warrantyStatusText?.text = result
                    toast(result)
                }
            }
        }
    }

    private fun nowText(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d-%02d %02d:%02d:%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND)
        )
    }

    private fun sendWarrantyMultipart(payload: JSONObject, files: List<Uri>): String {
        val boundary = "----LiferychWarranty${System.currentTimeMillis()}"
        return try {
            val conn = (URL(SERVER_WARRANTY_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }

            conn.outputStream.use { os ->
                fun writeText(s: String) { os.write(s.toByteArray(Charsets.UTF_8)) }
                writeText("--$boundary\r\n")
                writeText("Content-Disposition: form-data; name=\"payload_json\"\r\n")
                writeText("Content-Type: application/json; charset=utf-8\r\n\r\n")
                writeText(payload.toString())
                writeText("\r\n")

                for ((idx, uri) in files.take(5).withIndex()) {
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                    val fileName = warrantyFileName(uri, idx)
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"media[]\"; filename=\"$fileName\"\r\n")
                    writeText("Content-Type: $mime\r\n\r\n")
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            os.write(buffer, 0, read)
                        }
                    }
                    writeText("\r\n")
                }
                writeText("--$boundary--\r\n")
            }

            val code = conn.responseCode
            val response = try {
                if (code in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } catch (_: Exception) { "" }
            conn.disconnect()

            if (code in 200..299 && response.contains("\"ok\":true")) {
                val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.getOrNull(1)
                "Обращение отправлено${id?.let { " №$it" } ?: ""}"
            } else {
                "Ошибка отправки HTTP $code: ${response.take(180)}"
            }
        } catch (e: Exception) {
            "Ошибка отправки: ${e.message ?: e.toString()}"
        }
    }

    private fun warrantyFileName(uri: Uri, idx: Int): String {
        val mime = contentResolver.getType(uri) ?: ""
        val ext = when {
            mime.contains("jpeg") -> "jpg"
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("mp4") -> "mp4"
            mime.contains("quicktime") -> "mov"
            else -> "bin"
        }
        return "warranty_${System.currentTimeMillis()}_${idx + 1}.$ext"
    }

    private fun showJournalScreen() {
        screenState = "journal"
        currentTab = "journal"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(header("Журнал BMS", selectedDeviceName.ifBlank { selectedAddress ?: "" }, ""))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }

        content.addView(TextView(this).apply {
            text = "Журнал BMS"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
            setPadding(0, dp(4), 0, dp(14))
        }, LinearLayout.LayoutParams(-1, -2))

        if (localEvents.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Событий BMS пока нет"
                textSize = 17f
                setTextColor(Color.rgb(100,100,100))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(50), dp(16), dp(50))
                setBackgroundColor(Color.WHITE)
            }
            content.addView(empty, LinearLayout.LayoutParams(-1, -2))
        } else {
            val events = localEvents.takeLast(100).asReversed()
            val details = localEventDetails.takeLast(100).asReversed()
            for ((idx, e) in events.withIndex()) {
                content.addView(
                    journalRow(idx + 1, e, details.getOrNull(idx) ?: e),
                    marginLp(-1, -2, 0, 0, 0, 8)
                )
            }
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(fixedBottomNav("journal"), LinearLayout.LayoutParams(-1, dp(70)))

        setContentView(root)
    }

    private fun journalRow(number: Int, textLine: String, detailText: String = textLine): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(10), dp(14))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            elevation = dp(1).toFloat()
            setOnClickListener { showEventDetails(number, textLine, detailText) }
        }

        row.addView(TextView(this).apply {
            text = number.toString()
            textSize = 18f
            typeface = interFont(700)
            setTextColor(Color.rgb(45,45,45))
            gravity = Gravity.TOP
        }, LinearLayout.LayoutParams(dp(44), -1))

        val parts = textLine.split(" — ", limit = 2)
        val time = parts.getOrNull(0) ?: ""
        val eventText = parts.getOrNull(1) ?: textLine

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(TextView(this).apply {
            text = eventText
            textSize = 17f
            typeface = interFont(700)
            setTextColor(Color.rgb(40,40,40))
        })
        col.addView(TextView(this).apply {
            text = time
            textSize = 14f
            setTextColor(Color.rgb(105,105,105))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        row.addView(TextView(this).apply {
            text = "›"
            textSize = 32f
            setTextColor(Color.rgb(35,35,35))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), -1))

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(230,230,230)) }, LinearLayout.LayoutParams(-1, 1))
        }
    }

    private fun showEventDetails(number: Int, titleLine: String, detailText: String) {
        val parts = titleLine.split(" — ", limit = 2)
        val time = parts.getOrNull(0) ?: ""
        val title = parts.getOrNull(1) ?: titleLine
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("№ $number\nВремя: $time\n\n$detailText")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun renderCells() {
        if (!::cellsLayout.isInitialized) return
        cellsLayout.removeAllViews()
        val count = (data.cellCount ?: 4).coerceIn(1, 24)
        val columns = if (count <= 4) 2 else 4
        var row: LinearLayout? = null
        for (i in 1..count) {
            if ((i - 1) % columns == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                cellsLayout.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val v = data.cells[i]
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(12))
                minimumHeight = if (count <= 8) dp(78) else dp(72)
                background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            top.addView(TextView(this).apply {
                text = i.toString()
                textSize = if (count <= 8) 22f else 18f
                typeface = interFont(760)
                setTextColor(Color.rgb(16, 17, 20))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(this).apply {
                text = v?.let { "%.3f В".format(it) } ?: "--"
                textSize = if (count <= 8) 13f else 11f
                typeface = interFont(700)
                setTextColor(Color.rgb(16, 17, 20))
            })
            box.addView(top)
            box.addView(ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 3650
                progress = ((v ?: 0.0) * 1000).toInt()
                progressTintList =
                    android.content.res.ColorStateList.valueOf(Color.rgb(31, 179, 90))
                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.rgb(223, 229, 235))
            }, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(14) })
            row?.addView(
                box,
                marginLp(0, -2, 3, 3, 3, 3).apply { weight = 1f }
            )
        }
        val remainder = count % columns
        if (remainder != 0) {
            repeat(columns - remainder) {
                row?.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }

        devices.clear()
        scanRssi.clear()
        scanNames.clear()
        deviceStatusViews.clear()
        deviceActionViews.clear()
        selectedAddress = null
        if (screenState == "search" && ::deviceListLayout.isInitialized) {
            deviceListLayout.removeAllViews()
        }
        if (screenState == "search" && ::statusText.isInitialized) {
            statusText.text = "Поиск устройств...\nСканирование BLE устройств поблизости"
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            if (screenState == "loading") showBatteriesScreen()
            if (::statusText.isInitialized) {
                statusText.text = "BLE scanner недоступен. Проверь Bluetooth."
            }
            return
        }

        scanner.startScan(scanCallback)

        mainHandler.postDelayed({
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
            if (screenState == "loading") showBatteriesScreen()
            if (::statusText.isInitialized) {
                statusText.text = "Поиск завершен. Найдено: ${devices.size}"
            }
        }, 8000)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = normalBleName(result) ?: return
            if (!devices.containsKey(address)) {
                devices[address] = device
                scanRssi[address] = result.rssi
                scanNames[address] = name
                if (screenState == "search") {
                    runOnUiThread { addDeviceRow(device, result.rssi, name) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun normalBleName(result: ScanResult): String? {
        val name = result.scanRecord?.deviceName ?: result.device?.name
        val cleaned = sanitizeBleText(name)
        if (cleaned.isBlank()) return null
        val bad = cleaned.equals("unknown", true) ||
            cleaned.equals("n/a", true) ||
            cleaned.equals("null", true) ||
            cleaned.equals("unnamed", true) ||
            cleaned.equals("без имени", true)
        if (bad) return null
        return cleaned
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceRow(device: BluetoothDevice, rssi: Int, displayName: String? = null) {
        if (!::deviceListLayout.isInitialized) return
        val address = device.address
        val name = displayName ?: device.name ?: "Unknown"
        if (!deviceMatchesSearch(address, name)) return

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minimumHeight = dp(74)
            background = round(
                Color.rgb(246, 247, 249),
                dp(16),
                Color.rgb(223, 229, 235),
                1
            )
            elevation = dp(2).toFloat()
            setOnClickListener {
                selectedAddress = address
                selectedDeviceName = name
                refreshDeviceRowsSelection()
            }
            tag = address
        }

        row.addView(TextView(this).apply {
            text = "ᛒ"
            textSize = 21f
            setTextColor(redDark)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), dp(40)))

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = name
            textSize = 15f
            typeface = interFont(760)
            setTextColor(Color.rgb(16, 17, 20))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = address
            textSize = 12f
            typeface = interFont(650)
            setTextColor(Color.rgb(16, 17, 20))
            maxLines = 2
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        info.addView(TextView(this).apply {
            text = "RSSI $rssi"
            textSize = 12f
            typeface = interFont(600)
            setTextColor(Color.rgb(111, 119, 129))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val status = TextView(this).apply {
            textSize = 11f
            typeface = interFont(700)
            visibility = View.GONE
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        info.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        deviceStatusViews[address] = status
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f).apply {
            leftMargin = dp(10)
            rightMargin = dp(10)
        })

        row.addView(TextView(this).apply {
            text = when {
                rssi >= -55 -> "▂▅▇█"
                rssi >= -65 -> "▂▅▇"
                rssi >= -75 -> "▂▅"
                else -> "▂"
            }
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(if (rssi > -60) redDark else Color.rgb(111, 119, 129))
        }, LinearLayout.LayoutParams(dp(32), dp(32)))

        val action = TextView(this).apply {
            text = "⌁  Подкл."
            textSize = 12f
            typeface = interFont(780)
            gravity = Gravity.CENTER
            setTextColor(redDark)
            background = round(
                Color.rgb(255, 250, 232),
                dp(12),
                Color.rgb(235, 183, 0),
                1
            )
            setOnClickListener {
                selectedAddress = address
                selectedDeviceName = name
                returnToBatteriesAfterConnect = true
                refreshDeviceRowsSelection()
                status.apply {
                    text = "Подключение к BMS..."
                    setTextColor(redDark)
                    background = round(Color.rgb(255, 248, 218), dp(11), Color.TRANSPARENT, 0)
                    visibility = View.VISIBLE
                }
                text = "•••"
                connectSelectedDevice()
            }
        }
        deviceActionViews[address] = action
        row.addView(action, LinearLayout.LayoutParams(dp(92), dp(40)).apply {
            leftMargin = dp(10)
        })

        deviceListLayout.addView(row, marginLp(-1, -2, 0, 0, 0, 10))
        if (selectedAddress == null) {
            selectedAddress = address
            selectedDeviceName = name
            refreshDeviceRowsSelection()
        }
    }

    private fun deviceMatchesSearch(address: String, name: String): Boolean {
        val query = searchIdQuery.trim()
        if (query.isBlank()) return true
        val compactAddress = address.replace(":", "").replace("-", "")
        val compactQuery = query.replace(":", "").replace("-", "").replace(" ", "")
        if (compactQuery.isBlank()) return true
        return compactAddress.contains(compactQuery, ignoreCase = true) ||
            name.contains(query, ignoreCase = true)
    }

    private fun rebuildSearchDeviceList() {
        if (screenState != "search" || !::deviceListLayout.isInitialized) return
        deviceListLayout.removeAllViews()
        deviceStatusViews.clear()
        deviceActionViews.clear()
        for ((address, device) in devices) {
            val name = scanNames[address] ?: continue
            addDeviceRow(device, scanRssi[address] ?: 0, name)
        }
    }

    private fun refreshDeviceRowsSelection() {
        if (!::deviceListLayout.isInitialized) return
        for (i in 0 until deviceListLayout.childCount) {
            val row = deviceListLayout.getChildAt(i)
            val selected = row.tag == selectedAddress
            row.background = round(
                Color.rgb(246, 247, 249),
                dp(16),
                if (selected) redDark else Color.rgb(223, 229, 235),
                if (selected) 2 else 1
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedDevice() {
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        val address = selectedAddress
        if (address.isNullOrBlank()) {
            toast("Выбери устройство")
            return
        }
        val device = devices[address]
        if (device == null) {
            toast("Устройство не найдено")
            return
        }

        polling = false
        bluetoothGatt?.close()
        if (screenState == "search" && ::statusText.isInitialized) {
            statusText.text = "Подключение к $address..."
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        polling = false
        pollLoopToken++
        debugA5ScanActive = false
        debugA5ScanToken++
        resetRemoteWriteState()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(
                BLE_LOG_TAG,
                "connection address=${gatt.device.address} status=$status state=$newState"
            )
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                pendingFirstTelemetryUpload = true
                configAutoReadStartedForConnection = false
                setTemplateCheckChecking()
                testSocWriteDoneForConnection = false
                servicesDiscoveryStarted = false
                negotiatedMtu = 23
                data.cells.clear()
                data.cellCount = null
                batteryCodeFrames.clear()
                batteryCodeFrameHex.clear()
                hwVersionFrames.clear()
                hwVersionFrameHex.clear()
                debugA5Frames.clear()
                debugA5ScanActive = false
                debugA5ScanToken++
                debugA5ScanStatus = ""
                runOnUiThread {
                    if (::statusText.isInitialized) statusText.text = "Подключено. Читаю сервисы..."
                    selectedAddress?.let { address ->
                        deviceStatusViews[address]?.apply {
                            text = "Успешное подключение к BMS"
                            setTextColor(Color.rgb(31, 179, 90))
                            background = round(
                                Color.rgb(237, 250, 242),
                                dp(11),
                                Color.TRANSPARENT,
                                0
                            )
                            visibility = View.VISIBLE
                        }
                        deviceActionViews[address]?.apply {
                            text = "✓  Готово"
                            setTextColor(Color.rgb(31, 179, 90))
                            background = round(
                                Color.rgb(237, 250, 242),
                                dp(12),
                                Color.rgb(31, 179, 90),
                                1
                            )
                        }
                    }
                }
                val mtuRequested =
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                        gatt.requestMtu(247)
                Log.i(BLE_LOG_TAG, "request MTU 247 started=$mtuRequested")
                if (!mtuRequested) {
                    discoverGattServicesOnce(gatt)
                } else {
                    mainHandler.postDelayed({ discoverGattServicesOnce(gatt) }, 1500)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pendingFirstTelemetryUpload = false
                polling = false
                pollLoopToken++
                resetRemoteWriteState()
                configAutoReadStartedForConnection = false
                latestTemplateCheck = null
                runOnUiThread {
                    selectedAddress?.let { address ->
                        deviceStatusViews[address]?.apply {
                            text = "Не удалось подключиться к BMS"
                            setTextColor(Color.rgb(239, 83, 80))
                            background = round(
                                Color.rgb(255, 240, 240),
                                dp(11),
                                Color.TRANSPARENT,
                                0
                            )
                            visibility = View.VISIBLE
                        }
                        deviceActionViews[address]?.apply {
                            text = "↻  Ошибка"
                            setTextColor(Color.rgb(239, 83, 80))
                            background = round(
                                Color.rgb(255, 240, 240),
                                dp(12),
                                Color.rgb(239, 83, 80),
                                1
                            )
                        }
                    }
                    toast("Отключено от BMS")
                    if (screenState != "search" && screenState != "loading") {
                        showBatteriesScreen()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(BLE_LOG_TAG, "MTU changed mtu=$mtu status=$status")
            discoverGattServicesOnce(gatt)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(BLE_LOG_TAG, "services discovered status=$status count=${gatt.services.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    toast("Не удалось прочитать BLE-сервисы: $status")
                    showBatteriesScreen()
                }
                gatt.disconnect()
                return
            }
            detectCharacteristics(gatt)

            if (writeCharacteristic == null || notifyCharacteristic == null) {
                runOnUiThread {
                    if (::statusText.isInitialized) statusText.text = "Не найдены BLE характеристики write/notify"
                    selectedAddress?.let { address ->
                        deviceStatusViews[address]?.apply {
                            text = "BMS не поддерживается"
                            setTextColor(Color.rgb(239, 83, 80))
                            visibility = View.VISIBLE
                        }
                        deviceActionViews[address]?.apply {
                            text = "↻  Ошибка"
                            setTextColor(Color.rgb(239, 83, 80))
                        }
                    }
                }
                return
            }

            runOnUiThread {
                saveCurrentBattery(force = true)
                if (returnToBatteriesAfterConnect) {
                    returnToBatteriesAfterConnect = false
                    showBatteriesScreen()
                } else {
                    showDashboardScreen()
                }
            }
            enableNotifications(gatt, notifyCharacteristic!!)
            polling = true
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(BLE_LOG_TAG, "CCCD write uuid=${descriptor.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                polling = false
                runOnUiThread { toast("BMS не разрешила получение данных: $status") }
                return
            }
            if (polling) {
                if (!configAutoReadStartedForConnection) {
                    configAutoReadStartedForConnection = true
                    // Конфигурацию читаем автоматически, но с задержкой.
                    // Так текущие данные 0x90-0x98 появляются сразу, а длинное чтение конфига
                    // не блокирует первые опросы BMS после подключения.
                    mainHandler.postDelayed({
                        if (polling && !configReadInProgress && !remoteWriteInProgress) {
                            startConfigReadIfNeeded(force = true)
                        }
                    }, if (isServiceApp()) 400L else CONFIG_AUTO_READ_DELAY_MS)
                }
                startRemoteWritePolling()
                mainHandler.postDelayed({ pollOnce() }, 300)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: return
            Log.d(BLE_LOG_TAG, "notify ${characteristic.uuid}: ${bytesToHex(bytes)}")
            handleIncoming(bytes)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            Log.d(BLE_LOG_TAG, "notify ${characteristic.uuid}: ${bytesToHex(value)}")
            handleIncoming(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverGattServicesOnce(gatt: BluetoothGatt) {
        if (servicesDiscoveryStarted || bluetoothGatt !== gatt) return
        servicesDiscoveryStarted = true
        val started = gatt.discoverServices()
        Log.i(BLE_LOG_TAG, "discover services started=$started mtu=$negotiatedMtu")
    }

    private fun detectCharacteristics(gatt: BluetoothGatt) {
        var notifyCandidate: BluetoothGattCharacteristic? = null
        var writeCandidate: BluetoothGattCharacteristic? = null

        val preferredNotify = listOf(
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
        )
        val preferredWrite = listOf(
            UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        )

        fun canNotify(ch: BluetoothGattCharacteristic): Boolean {
            val props = ch.properties
            return (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }

        fun canWrite(ch: BluetoothGattCharacteristic): Boolean {
            val props = ch.properties
            return (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

        val allChars = mutableListOf<BluetoothGattCharacteristic>()
        for (service in gatt.services) {
            for (ch in service.characteristics) {
                allChars.add(ch)
            }
        }

        for (service in gatt.services) {
            val chars = service.characteristics
            val sameServiceNotify = preferredNotify.firstNotNullOfOrNull { uuid ->
                chars.firstOrNull { it.uuid == uuid && canNotify(it) }
            } ?: chars.firstOrNull { canNotify(it) }
            val sameServiceWrite = preferredWrite.firstNotNullOfOrNull { uuid ->
                chars.firstOrNull { it.uuid == uuid && canWrite(it) }
            } ?: chars.firstOrNull { canWrite(it) }
            if (sameServiceNotify != null && sameServiceWrite != null) {
                notifyCandidate = sameServiceNotify
                writeCandidate = sameServiceWrite
                break
            }
        }

        if (notifyCandidate == null) {
            for (uuid in preferredNotify) {
                notifyCandidate = allChars.firstOrNull { it.uuid == uuid && canNotify(it) }
                if (notifyCandidate != null) break
            }
        }
        if (notifyCandidate == null) notifyCandidate = allChars.firstOrNull { canNotify(it) }

        if (writeCandidate == null) {
            for (uuid in preferredWrite) {
                writeCandidate = allChars.firstOrNull { it.uuid == uuid && canWrite(it) }
                if (writeCandidate != null) break
            }
        }
        if (writeCandidate == null) writeCandidate = allChars.firstOrNull { canWrite(it) }

        notifyCharacteristic = notifyCandidate
        writeCharacteristic = writeCandidate

        val sb = StringBuilder()
        sb.appendLine("Selected notify: ${notifyCharacteristic?.uuid}")
        sb.appendLine("Selected write:  ${writeCharacteristic?.uuid}")
        sb.appendLine()
        for (service in gatt.services) {
            sb.appendLine("Service: ${service.uuid}")
            for (ch in service.characteristics) {
                sb.appendLine("  Char: ${ch.uuid} props=${ch.properties}")
            }
        }
        bleDebugText = sb.toString()
        Log.i(BLE_LOG_TAG, bleDebugText)
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val localEnabled = gatt.setCharacteristicNotification(ch, true)
        Log.i(BLE_LOG_TAG, "enable notifications uuid=${ch.uuid} local=$localEnabled")
        val descriptor = ch.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor != null) {
            descriptor.value = if (
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0
            ) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            gatt.writeDescriptor(descriptor)
        } else {
            Log.w(BLE_LOG_TAG, "CCCD descriptor is missing for ${ch.uuid}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollOnce() {
        val token = ++pollLoopToken
        mainHandler.post { pollOnce(token) }
    }

    @SuppressLint("MissingPermission")
    private fun pollOnce(token: Int) {
        if (token != pollLoopToken) return
        if (!polling) return
        if (configReadInProgress || remoteWriteInProgress) {
            mainHandler.postDelayed({ pollOnce(token) }, 1000)
            return
        }
        if (debugA5ScanActive) {
            mainHandler.postDelayed({ pollOnce(token) }, 800)
            return
        }
        val gatt = bluetoothGatt ?: return
        val ch = writeCharacteristic ?: return
        runtimeCommands = buildList {
            if (isServiceApp() || parseBmsHardwareVersion(liveHwVersion()).isBlank()) {
                add(DALY_HW_VERSION_CMD)
            }
            if (isServiceApp() || parseBmsHardwareVersion(liveBatteryCode()).isBlank()) {
                add(DALY_BATTERY_CODE_CMD)
            }
            addAll(listOf(0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98))
        }
        runtimeCommandIndex = 0
        pendingRuntimeCommand = null
        sendCurrentRuntimeCommand(gatt, ch, token)
    }

    @SuppressLint("MissingPermission")
    private fun sendCurrentRuntimeCommand(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        token: Int
    ) {
        if (token != pollLoopToken) return
        if (!polling) return
        if (configReadInProgress || remoteWriteInProgress) {
            mainHandler.postDelayed({ pollOnce(token) }, 1000)
            return
        }
        if (runtimeCommandIndex >= runtimeCommands.size) {
            mainHandler.postDelayed({ pollOnce(token) }, 4000)
            return
        }
        val command = runtimeCommands[runtimeCommandIndex]
        pendingRuntimeCommand = command
        runtimeReceivedGroups.clear()
        runtimeExpectedFrames = when (command) {
            DALY_HW_VERSION_CMD -> DALY_HW_VERSION_FRAMES
            DALY_BATTERY_CODE_CMD -> DALY_BATTERY_CODE_FRAMES
            0x95 -> ((data.cellCount ?: 1) + 2) / 3
            0x96 -> ((data.tempCount ?: 1) + 6) / 7
            else -> 1
        }.coerceAtLeast(1)
        val frame = buildRequest(command)
        val props = ch.properties
        ch.writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.value = frame
        val started = gatt.writeCharacteristic(ch)
        Log.d(
            BLE_LOG_TAG,
            "request cmd=0x%02X write=$started uuid=${ch.uuid}: ${bytesToHex(frame)}"
                .format(command)
        )
        if (!started) {
            pendingRuntimeCommand = null
            mainHandler.postDelayed({ sendCurrentRuntimeCommand(gatt, ch, token) }, 250)
            return
        }
        mainHandler.postDelayed({
            if (
                token == pollLoopToken &&
                pendingRuntimeCommand == command &&
                runtimeCommandIndex < runtimeCommands.size
            ) {
                Log.w(BLE_LOG_TAG, "timeout waiting response cmd=0x%02X".format(command))
                pendingRuntimeCommand = null
                runtimeCommandIndex++
                sendCurrentRuntimeCommand(gatt, ch, token)
            }
        }, 1800)
    }

    @SuppressLint("MissingPermission")
    private fun completeRuntimeCommand(cmd: Int, payload: ByteArray) {
        if (debugA5ScanActive) return
        if (pendingRuntimeCommand != cmd) return
        if (cmd == 0x95 || cmd == 0x96 || cmd == DALY_BATTERY_CODE_CMD || cmd == DALY_HW_VERSION_CMD) {
            runtimeReceivedGroups.add(payload[0].toInt() and 0xFF)
            if (runtimeReceivedGroups.size < runtimeExpectedFrames) return
        }
        pendingRuntimeCommand = null
        runtimeCommandIndex++
        val token = pollLoopToken
        val gatt = bluetoothGatt ?: return
        val ch = writeCharacteristic ?: return
        mainHandler.postDelayed({ sendCurrentRuntimeCommand(gatt, ch, token) }, 120)
    }

    private fun handleIncoming(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val startsWithDalyFrame = bytes[0] == FRAME_START
        val hasPendingConfigFrame =
            synchronized(configModbusBuffer) { configModbusBuffer.isNotEmpty() }
        val startsWithModbusFrame =
            bytes.size >= 2 &&
                (bytes[1].toInt() and 0xFF) in setOf(0x03, 0x06, 0x10, 0x83, 0x86, 0x90)
        // Runtime A5 frames can still arrive while a Modbus settings request is active.
        // They must stay in the normal parser instead of poisoning the Modbus buffer.
        if (
            (configReadInProgress || activeConfigRead != null) &&
            !startsWithDalyFrame &&
            (startsWithModbusFrame || hasPendingConfigFrame)
        ) {
            handleConfigModbusIncoming(bytes)
            runOnUiThread { updateDashboardUi() }
            return
        }

        handleDalyIncoming(bytes)
        runOnUiThread { updateDashboardUi() }
    }

    private fun handleDalyIncoming(bytes: ByteArray) {
        synchronized(rxBuffer) {
            for (b in bytes) rxBuffer.add(b)
            while (rxBuffer.size >= 13) {
                val start = rxBuffer.indexOf(FRAME_START)
                if (start < 0) {
                    rxBuffer.clear()
                    break
                }
                repeat(start) { rxBuffer.removeAt(0) }
                if (rxBuffer.size < 13) break
                val frame = ByteArray(13) { index -> rxBuffer[index] }
                if (isValidFrame(frame)) {
                    repeat(13) { rxBuffer.removeAt(0) }
                    parseFrame(frame)
                } else {
                    Log.w(BLE_LOG_TAG, "invalid Daly frame: ${bytesToHex(frame)}")
                    rxBuffer.removeAt(0)
                }
            }
        }
    }

    private fun parseFrame(frame: ByteArray) {
        val cmd = frame[2].toInt() and 0xFF
        val p = frame.copyOfRange(4, 12)
        data.raw["0x%02X".format(cmd)] = hex(frame)
        recordDebugA5Frame(cmd, frame, p)

        when (cmd) {
            0x90 -> {
                data.voltage = u16(p, 0) / 10.0
                data.current = (u16(p, 4) - 30000) / 10.0
                data.soc = u16(p, 6) / 10.0
            }
            0x91 -> {
                data.maxCellV = u16(p, 0) / 1000.0
                data.maxCellNo = p[2].toInt() and 0xFF
                data.minCellV = u16(p, 3) / 1000.0
                data.minCellNo = p[5].toInt() and 0xFF
            }
            0x92 -> {
                data.maxTemp = (p[0].toInt() and 0xFF) - 40
                data.minTemp = (p[2].toInt() and 0xFF) - 40
            }
            0x93 -> {
                data.chargeMos = (p[1].toInt() and 0xFF) != 0
                data.dischargeMos = (p[2].toInt() and 0xFF) != 0
                data.remainingAh = u32(p, 4) / 1000.0
            }
            0x94 -> {
                data.cellCount = p[0].toInt() and 0xFF
                data.tempCount = p[1].toInt() and 0xFF
                data.chargerConnected = (p[2].toInt() and 0xFF) != 0
                data.loadConnected = (p[3].toInt() and 0xFF) != 0
                data.cycles = u16(p, 5)
                pruneCellsToCount()
            }
            DALY_BATTERY_CODE_CMD -> {
                if (storeAsciiCmdFrame(batteryCodeFrames, batteryCodeFrameHex, p)) {
                    rememberBatteryCode()
                }
            }
            DALY_HW_VERSION_CMD -> {
                if (storeAsciiCmdFrame(hwVersionFrames, hwVersionFrameHex, p)) {
                    rememberHwVersion()
                }
            }
            0x95 -> {
                val group = p[0].toInt() and 0xFF
                for (i in 0 until 3) {
                    val off = 1 + i * 2
                    val mv = u16(p, off)
                    val cellNo = (group - 1) * 3 + i + 1
                    val count = data.cellCount
                    if (count != null && cellNo > count) continue
                    if (mv in 500..5000) data.cells[cellNo] = mv / 1000.0
                }
            }
            0x96 -> {
                val group = p[0].toInt() and 0xFF
                for (i in 0 until 7) {
                    val idx = 1 + i
                    val raw = p[idx].toInt() and 0xFF
                    val tempNo = (group - 1) * 7 + i + 1
                    val count = data.tempCount
                    if (count != null && tempNo > count) continue
                    if (raw != 0x00 && raw != 0xFF) data.temps[tempNo] = raw - 40
                }
            }
            0x97 -> {
                data.balancingCells.clear()
                for (byteIndex in 0 until 6) {
                    val mask = p[byteIndex].toInt() and 0xFF
                    for (bit in 0..7) {
                        if (((mask shr bit) and 1) != 0) {
                            val cellNo = byteIndex * 8 + bit + 1
                            val count = data.cellCount
                            if (count == null || cellNo <= count) {
                                data.balancingCells.add(cellNo)
                            }
                        }
                    }
                }
            }
            0x98 -> {
                data.errors.clear()
                for (byteIndex in p.indices) {
                    val byteValue = p[byteIndex].toInt() and 0xFF
                    for (bitInByte in 0..7) {
                        if (((byteValue shr bitInByte) and 1) == 1) {
                            val globalBit = byteIndex * 8 + bitInByte
                            val description = dalyErrorDescription(globalBit)
                            if (!description.startsWith("Резерв") && !description.startsWith("Дополнительный fault code")) {
                                data.errors.add(description)
                            }
                        }
                    }
                }
            }
        }
        data.lastUpdatedAt = System.currentTimeMillis()
        Log.d(BLE_LOG_TAG, "parsed cmd=0x%02X".format(cmd))
        updateDerived()
        completeRuntimeCommand(cmd, p)

        if (cmd == 0x98) {
            onPollCompleted()
        }
    }

    private fun pruneCellsToCount() {
        val count = data.cellCount ?: return
        val stale = data.cells.keys.filter { it < 1 || it > count }
        if (stale.isEmpty()) return
        for (key in stale) data.cells.remove(key)
    }

    private fun updateDerived() {
        pruneCellsToCount()
        if (hasTemperatureSensorError()) {
            data.temps.clear()
            data.minTemp = null
            data.maxTemp = null
        }
        if (data.cells.isNotEmpty()) {
            val vals = data.cells.values
            data.cellDiffV = vals.maxOrNull()!! - vals.minOrNull()!!
        }
        val soc = data.soc
        val rem = data.remainingAh
        if (soc != null && soc > 0.0 && rem != null) {
            data.estimatedFullAh = rem / (soc / 100.0)
        }
    }

    private fun updateDashboardUi() {
        if (polling) saveCurrentBattery()
        if (screenState == "debug") {
            debugDumpView?.text = bmsRegisterDump()
            return
        }
        if (screenState == "manage") {
            if (::manageContentLayout.isInitialized) renderManageContent()
            return
        }
        if (screenState == "qtc") {
            if (::qtcContentLayout.isInitialized) renderQtcContent()
            return
        }
        if (!::socGauge.isInitialized) return

        socGauge.setSoc(data.soc)
        if (::socProgress.isInitialized) socProgress.progress = data.soc?.toInt() ?: 0
        voltageValue.text = data.voltage?.let { "%.2f В".format(it) } ?: "-- В"
        currentValue.text = data.current?.let { "%.1f А".format(it) } ?: "-- А"
        remainingValue.text = data.remainingAh?.let { "%.1f А·ч".format(it) } ?: "-- А·ч"

        chargeMosValue.text = if (data.chargeMos == true) "ON" else "OFF"
        chargeMosValue.setTextColor(if (data.chargeMos == true) green else orange)
        dischargeMosValue.text = if (data.dischargeMos == true) "ON" else "OFF"
        dischargeMosValue.setTextColor(if (data.dischargeMos == true) green else orange)

        if (hasTemperatureSensorError()) {
            t1Text.text = "Нет датчика"
            t2Text.text = "Нет датчика"
        } else {
            val t1 = data.temps[1] ?: data.minTemp
            val t2 = data.temps[2] ?: data.maxTemp
            t1Text.text = "${t1?.toString() ?: "--"} °C"
            t2Text.text = "${t2?.toString() ?: "--"} °C"
        }
        balanceValue.text = when {
            data.errors.isNotEmpty() -> "Внимание"
            data.balancingCells.isNotEmpty() -> "Балансировка"
            else -> "Нормальный"
        }
        balanceValue.setTextColor(
            when {
                data.errors.isNotEmpty() -> Color.rgb(239, 83, 80)
                data.balancingCells.isNotEmpty() -> Color.rgb(255, 153, 0)
                else -> Color.rgb(31, 179, 90)
            }
        )
        val device = selectedDeviceName.ifBlank { selectedAddress ?: "не выбрано" }
        if (::deviceNameValue.isInitialized) deviceNameValue.text = device
        bluetoothIdValue?.text = bluetoothId().ifBlank { "--" }
        bmsSnValue?.text = displayFactorySn().ifBlank { "--" }
        bmsVersionValue?.text = displayBmsVersion().ifBlank { "--" }
        if (isServiceApp()) {
            serviceVersionDebugView?.text = serviceVersionDebugDump()
        }
        if (::cycleCountValue.isInitialized) {
            cycleCountValue.text = data.cycles?.toString() ?: "--"
        }
        if (::batteryInfoText.isInitialized) {
            val cycles = data.cycles?.let { " · Циклов: $it" }.orEmpty()
            batteryInfoText.text = if (data.errors.isEmpty()) {
                "✓  Нормальное состояние · Устройство: $device$cycles"
            } else {
                "!  Требуется внимание · Устройство: $device$cycles"
            }
        }

        renderCells()
    }

    private fun isHistoricalCandidate(cmd: Int): Boolean {
        // Безопасность: неизвестные сервисные команды Daly больше не считаем историческим журналом.
        // Опыт показал, что команда 0x50 может перезагружать BMS и отключать MOS.
        // До точного определения безопасной команды Historical Alarm ничего не парсим как журнал.
        return false
    }

    private fun decodeHistoricalRawCandidate(cmd: Int, frame: ByteArray): String {
        // Полный формат Historical Alarm у Daly пока не подтвержден.
        // Поэтому в v14 показываем найденную запись как отдельное событие с RAW,
        // чтобы потом по реальным ответам разобрать тип события и дату.
        val payload = if (frame.size >= 12) frame.copyOfRange(4, 12) else ByteArray(0)
        if (payload.all { it.toInt() == 0x00 || it.toInt() == 0xFF }) return ""

        val hexCmd = "0x${cmd.toString(16).uppercase().padStart(2, '0')}"
        val guessedName = when (cmd) {
            0x99 -> "Historical Alarm response"
            0x9A -> "Historical Alarm response"
            0x9B -> "Historical Alarm response"
            0x9C -> "Historical Alarm response"
            else -> "Daly historical record"
        }
        return "$guessedName / $hexCmd — ${bytesToHex(frame)}"
    }

    private fun readDalyHistoricalLogExperimental() {
        historicalReadInProgress = false
        historicalLastStatus = "Чтение внутреннего журнала временно заблокировано"
        AlertDialog.Builder(this)
            .setTitle("Чтение журнала Daly заблокировано")
            .setMessage(
                "Экспериментальный перебор команд отключен.\n\n" +
                "Причина: команда 0x50 на этой BMS перезагружает плату и отключает MOS зарядки/разрядки.\n\n" +
                "До точного определения безопасной команды Historical Alarm приложение не будет отправлять неизвестные сервисные команды."
            )
            .setPositiveButton("OK", null)
            .show()
        if (screenState == "journal") showJournalScreen()
    }

    private fun sendHistoricalCandidateQueue(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        commands: List<Int>,
        index: Int
    ) {
        // Отключено по безопасности. Не отправлять неизвестные команды Daly.
        historicalReadInProgress = false
        historicalLastStatus = "Экспериментальный опрос отключен"
    }

    private fun rememberCurrentBmsState() {
        if (data.soc != null) lastKnownSoc = data.soc
        val chargeText = mosText(data.chargeMos)
        val dischargeText = mosText(data.dischargeMos)
        if (chargeText != "--") lastKnownChargeMosTextText = chargeText
        if (dischargeText != "--") lastKnownDischargeMosTextText = dischargeText
    }

    private fun currentSocTextForConfig(): String {
        return fmtPct(data.soc ?: lastKnownSoc)
    }

    private fun currentChargeMosTextForConfig(): String {
        val current = mosText(data.chargeMos)
        return if (current != "--") current else (lastKnownChargeMosTextText ?: "--")
    }

    private fun currentDischargeMosTextForConfig(): String {
        val current = mosText(data.dischargeMos)
        return if (current != "--") current else (lastKnownDischargeMosTextText ?: "--")
    }

    private fun mosStateNumForSite(value: String): Any {
        return when (value.uppercase()) {
            "ON", "ВКЛ", "OPEN", "ОТКРЫТЬ" -> 1
            "OFF", "ВЫКЛ", "CLOSE", "ЗАКРЫТЬ" -> 0
            else -> JSONObject.NULL
        }
    }


    private fun onPollCompleted() {
        rememberCurrentBmsState()
        detectAndStoreEvents()
        val forceFirst = isServiceApp() && pendingFirstTelemetryUpload
        if (forceFirst) pendingFirstTelemetryUpload = false
        uploadCurrentData(force = forceFirst)
        // Конфиг BMS больше не читаем автоматически на каждом цикле опроса.
        // Он читается только вручную кнопкой «Обновить конфиг BMS» и сохраняется в память приложения.
    }

    private fun isDalyBluetoothDeviceId(value: String): Boolean {
        return value.matches(Regex("^DL-[0-9A-Fa-f]+$"))
    }

    private fun dalyBluetoothDeviceId(): String {
        val advertised = advertisedBluetoothName()
        if (isDalyBluetoothDeviceId(advertised)) return advertised
        val mac = selectedAddress.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
        if (mac.isNotBlank()) return "DL-$mac"
        return advertised.ifBlank { selectedAddress ?: "unknown_bms" }
    }

    private fun bmsUid(): String = dalyBluetoothDeviceId()

    private fun sanitizeBleText(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val cut = value.indexOf('\u0000')
        val head = if (cut >= 0) value.substring(0, cut) else value
        return buildString(head.length) {
            for (ch in head) {
                val code = ch.code
                if (code in 32..126 || ch.isLetterOrDigit()) append(ch)
            }
        }.trim()
    }

    private fun isServiceApp(): Boolean = BuildConfig.IS_SERVICE

    private fun appBrandTitle(): String {
        return if (isServiceApp()) "ЛИФЕРЫЧ Сервис" else "ЛИФЕРЫЧ BMS"
    }

    private fun bluetoothId(): String = selectedAddress.orEmpty()

    private fun identityPrefs() = getSharedPreferences("bms_identity", MODE_PRIVATE)

    private fun bmsSn(): String {
        val live = factorySerialFromRegisters()
        if (live.isNotBlank()) {
            rememberFactorySerial()
            return live
        }
        return cachedFactorySerial()
    }

    private fun bmsBatteryCode(): String {
        val live = liveBatteryCode()
        if (live.isNotBlank()) {
            rememberBatteryCode()
            return live
        }
        return cachedBatteryCode()
    }

    private fun liveBatteryCode(): String = joinAsciiFrames(batteryCodeFrames)

    private fun bmsHwVersionText(): String {
        val live = liveHwVersion()
        if (live.isNotBlank()) {
            rememberHwVersion()
            return live
        }
        return cachedHwVersion()
    }

    private fun liveHwVersion(): String = joinAsciiFrames(hwVersionFrames)

    private fun joinAsciiFrames(frames: Map<Int, String>): String {
        if (frames.isEmpty()) return ""
        val last = frames.keys.maxOrNull() ?: return ""
        return buildString {
            for (frameNo in 1..last) {
                append(frames[frameNo].orEmpty())
            }
        }.trim()
    }

    private fun storeAsciiCmdFrame(
        frames: MutableMap<Int, String>,
        hex: MutableMap<Int, String>,
        payload: ByteArray
    ): Boolean {
        val frameNo = payload[0].toInt() and 0xFF
        if (frameNo !in 1..8) return false
        hex[frameNo] = payload.joinToString(" ") { b ->
            (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
        frames[frameNo] = buildString {
            for (i in 1..7) {
                val b = payload[i].toInt() and 0xFF
                if (b == 0) return@buildString
                if (b !in 32..126) return@buildString
                append(b.toChar())
            }
        }
        return true
    }

    private fun recordDebugA5Frame(cmd: Int, frame: ByteArray, payload: ByteArray) {
        if (!isServiceApp()) return
        val ascii = buildString {
            for (b in payload) {
                val v = b.toInt() and 0xFF
                append(if (v in 32..126) v.toChar() else '.')
            }
        }
        val line = "${bytesToHex(frame)}  \"$ascii\""
        val list = debugA5Frames.getOrPut(cmd) { mutableListOf() }
        if (list.lastOrNull() == line) return
        if (list.size >= 8) list.removeAt(0)
        list.add(line)
    }

    private fun startDebugA5Scan() {
        if (!isServiceApp()) return
        if (bluetoothGatt == null || writeCharacteristic == null) {
            toast("Нет BLE")
            return
        }
        if (debugA5ScanActive) {
            toast("Скан A5 уже идёт")
            return
        }
        val token = ++debugA5ScanToken
        debugA5ScanStatus = "жду Modbus, затем скан A5"
        debugDumpView?.text = bmsRegisterDump()
        fun beginWhenIdle() {
            if (token != debugA5ScanToken) return
            if (bluetoothGatt == null) return
            if (configReadInProgress || remoteWriteInProgress) {
                debugA5ScanStatus = "жду Modbus…"
                debugDumpView?.text = bmsRegisterDump()
                mainHandler.postDelayed({ beginWhenIdle() }, 400)
                return
            }
            pendingRuntimeCommand = null
            pollLoopToken++
            debugA5ScanActive = true
            debugA5ScanCommands = (0x01..0x9F).filter { it !in DEBUG_A5_SKIP_CMDS }
            debugA5ScanIndex = 0
            debugA5ScanStatus = "скан A5 0/${debugA5ScanCommands.size}"
            sendNextDebugA5Command(token)
        }
        mainHandler.post { beginWhenIdle() }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextDebugA5Command(token: Int) {
        if (token != debugA5ScanToken || !debugA5ScanActive) return
        if (debugA5ScanIndex >= debugA5ScanCommands.size) {
            debugA5ScanActive = false
            debugA5ScanStatus = "скан A5 готов: ${debugA5Frames.size} команд с ответом"
            debugDumpView?.text = bmsRegisterDump()
            toast("Скан A5 готов: ${debugA5Frames.size} команд")
            if (polling) pollOnce()
            return
        }
        val cmd = debugA5ScanCommands[debugA5ScanIndex]
        debugA5ScanStatus = "скан A5 ${debugA5ScanIndex + 1}/${debugA5ScanCommands.size}  cmd=0x%02X".format(cmd)
        debugDumpView?.text = bmsRegisterDump()
        writeBleFrame(buildRequest(cmd))
        val waitMs = when (cmd) {
            DALY_BATTERY_CODE_CMD, DALY_HW_VERSION_CMD, 0x95, 0x96 -> 1200L
            else -> 320L
        }
        mainHandler.postDelayed({
            if (token != debugA5ScanToken) return@postDelayed
            debugA5ScanIndex++
            sendNextDebugA5Command(token)
        }, waitMs)
    }

    private fun displayBmsVersion(): String {
        if (isServiceApp()) {
            return bmsHwVersionText().ifBlank { bmsHardwareVersion() }
        }
        return bmsBatteryCode().ifBlank { bmsHardwareVersion() }
    }

    private fun parseBmsHardwareVersion(raw: String): String {
        val upper = raw.uppercase().trim()
        if (upper in BMS_VERSION_TOKENS) return upper
        var bestIndex = Int.MAX_VALUE
        var bestToken = ""
        for (token in BMS_VERSION_TOKENS) {
            val index = upper.indexOf(token)
            if (index >= 0 && index < bestIndex) {
                bestIndex = index
                bestToken = token
            }
        }
        return bestToken
    }

    private fun factorySerialCandidates(): List<String> {
        return listOf(
            decodeDalySnCode(swapBytes = false),
            decodeDalySnCode(swapBytes = true)
        ).filter { it.isNotBlank() }.distinct()
    }

    private fun versionFromFactorySn(): String {
        for (candidate in factorySerialCandidates() + listOf(cachedFactorySerial())) {
            val token = parseBmsHardwareVersion(candidate)
            if (token.isNotBlank()) return token
        }
        return ""
    }

    private fun bmsHardwareVersion(): String {
        if (isServiceApp()) {
            val fromHw = parseBmsHardwareVersion(bmsHwVersionText())
            if (fromHw.isNotBlank()) return fromHw
            val fromSn = versionFromFactorySn()
            if (fromSn.isNotBlank()) return fromSn
            val fromCode = parseBmsHardwareVersion(bmsBatteryCode())
            if (fromCode.isNotBlank()) return fromCode
            val address = selectedAddress ?: return ""
            return identityPrefs().getString("ver_$address", "")?.trim().orEmpty()
        }
        val fromCode = parseBmsHardwareVersion(bmsBatteryCode())
        if (fromCode.isNotBlank()) return fromCode
        val address = selectedAddress ?: return ""
        return identityPrefs().getString("ver_$address", "")?.trim().orEmpty()
    }

    private fun serviceVersionDebugDump(): String {
        val snBe = decodeDalySnCode(swapBytes = false)
        val snLe = decodeDalySnCode(swapBytes = true)
        val hw = bmsHwVersionText()
        val fromHw = parseBmsHardwareVersion(hw)
        val code = liveBatteryCode().ifBlank { cachedBatteryCode() }
        val fromSn = versionFromFactorySn()
        val fromCode = parseBmsHardwareVersion(code)
        val source = when {
            fromHw.isNotBlank() -> "A5 команда 0x63"
            fromSn.isNotBlank() -> "заводской SN (Modbus 0x0057-0x005D)"
            fromCode.isNotBlank() -> "A5 команда 0x57"
            else -> "не определена"
        }
        val hwFrames = if (hwVersionFrameHex.isEmpty()) {
            "кадров A5 0x63 нет"
        } else {
            hwVersionFrameHex.entries.sortedBy { it.key }.joinToString("\n") { (no, hex) ->
                "кадр $no: $hex"
            }
        }
        val frames = if (batteryCodeFrameHex.isEmpty()) {
            "кадров A5 0x57 нет"
        } else {
            batteryCodeFrameHex.entries.sortedBy { it.key }.joinToString("\n") { (no, hex) ->
                "кадр $no: $hex"
            }
        }
        val regs = (DALY_SN_CODE_START..DALY_SN_CODE_END).joinToString(" ") { addr ->
            val value = configRegisters[addr]
            if (value == null) {
                "%04X:—".format(addr)
            } else {
                "%04X:%04X".format(addr, value and 0xFFFF)
            }
        }
        return buildString {
            appendLine("Отладка версии (сервис)")
            appendLine("A5 0x63: ${hw.ifBlank { "—" }} → ${fromHw.ifBlank { "нет" }}")
            appendLine(hwFrames)
            appendLine("A5 0x57: ${code.ifBlank { "—" }} → ${fromCode.ifBlank { "нет" }}")
            appendLine(frames)
            appendLine("SN BE: ${snBe.ifBlank { "—" }}")
            appendLine("SN LE: ${snLe.ifBlank { "—" }}")
            appendLine("Modbus: $regs")
            appendLine("Берём: ${bmsHardwareVersion().ifBlank { "—" }} ($source)")
        }.trim()
    }

    private fun registerAscii(value: Int, swapBytes: Boolean): String {
        val hi = (value shr 8) and 0xFF
        val lo = value and 0xFF
        val bytes = if (swapBytes) intArrayOf(lo, hi) else intArrayOf(hi, lo)
        return buildString {
            for (b in bytes) {
                append(if (b in 32..126) b.toChar() else '.')
            }
        }
    }

    private fun consecutiveAsciiRuns(swapBytes: Boolean): List<String> {
        if (configRegisters.isEmpty()) return emptyList()
        val runs = mutableListOf<String>()
        val addrs = configRegisters.keys.sorted()
        var start = addrs.first()
        var prev = addrs.first()
        val chunk = StringBuilder(registerAscii(configRegisters.getValue(start), swapBytes))
        for (addr in addrs.drop(1)) {
            if (addr == prev + 1) {
                chunk.append(registerAscii(configRegisters.getValue(addr), swapBytes))
            } else {
                val text = chunk.toString().trim { it == '.' || it.isWhitespace() }
                if (text.length >= 4 && text.any { it.isLetter() }) {
                    runs += "0x%04X–0x%04X  %s".format(start, prev, text)
                }
                start = addr
                chunk.clear()
                chunk.append(registerAscii(configRegisters.getValue(addr), swapBytes))
            }
            prev = addr
        }
        val text = chunk.toString().trim { it == '.' || it.isWhitespace() }
        if (text.length >= 4 && text.any { it.isLetter() }) {
            runs += "0x%04X–0x%04X  %s".format(start, prev, text)
        }
        return runs
    }

    private fun bmsRegisterDump(): String {
        val status = when {
            configReadInProgress -> "идёт чтение Modbus"
            configRegisters.isEmpty() -> "регистров пока нет — нажмите «Прочитать»"
            else -> "прочитано ${configRegisters.size} holding"
        }
        return buildString {
            appendLine("=== ${appBrandTitle()} $APP_VERSION ===")
            appendLine("UID: ${bmsUid()}")
            appendLine("SN: ${displayFactorySn().ifBlank { "—" }}")
            appendLine("Версия: ${bmsHardwareVersion().ifBlank { "—" }}")
            appendLine("A5 0x63: ${bmsHwVersionText().ifBlank { "—" }}")
            appendLine("Код A5 0x57: ${bmsBatteryCode().ifBlank { "—" }}")
            appendLine("Статус: $status")
            appendLine("A5 скан: ${debugA5ScanStatus.ifBlank { "не запускался — нажмите «Прочитать»" }}")
            appendLine("lastConfig: $lastConfigStatus")
            appendLine()
            appendLine("--- Все ответы A5 ---")
            if (debugA5Frames.isEmpty()) {
                appendLine("пока нет кадров")
            } else {
                for ((cmd, frames) in debugA5Frames) {
                    appendLine("cmd 0x%02X  (%d кадр.)".format(cmd, frames.size))
                    for (line in frames) {
                        appendLine("  $line")
                    }
                }
            }
            appendLine()
            appendLine("--- Daly A5 last ---")
            if (data.raw.isEmpty()) {
                appendLine("нет кадров")
            } else {
                for ((cmd, hex) in data.raw.entries.sortedBy { it.key }) {
                    appendLine("$cmd  $hex")
                }
            }
            if (hwVersionFrameHex.isNotEmpty()) {
                appendLine()
                appendLine("--- A5 0x63 кадры (железо) ---")
                for ((no, hex) in hwVersionFrameHex.entries.sortedBy { it.key }) {
                    val ascii = hwVersionFrames[no].orEmpty()
                    appendLine("кадр $no  $hex  \"$ascii\"")
                }
            }
            if (batteryCodeFrameHex.isNotEmpty()) {
                appendLine()
                appendLine("--- A5 0x57 кадры ---")
                for ((no, hex) in batteryCodeFrameHex.entries.sortedBy { it.key }) {
                    val ascii = batteryCodeFrames[no].orEmpty()
                    appendLine("кадр $no  $hex  \"$ascii\"")
                }
            }
            appendLine()
            appendLine("--- Modbus holding ---")
            appendLine("addr    hex   u16    BE   LE")
            if (configRegisters.isEmpty()) {
                appendLine("пусто")
            } else {
                for ((addr, value) in configRegisters) {
                    val word = value and 0xFFFF
                    appendLine(
                        "0x%04X  %04X  %5d  '%s'  '%s'".format(
                            addr,
                            word,
                            word,
                            registerAscii(word, false),
                            registerAscii(word, true)
                        )
                    )
                }
            }
            val asciiBe = consecutiveAsciiRuns(false)
            val asciiLe = consecutiveAsciiRuns(true)
            if (asciiBe.isNotEmpty()) {
                appendLine()
                appendLine("--- ASCII BE ---")
                asciiBe.forEach { appendLine(it) }
            }
            if (asciiLe.isNotEmpty()) {
                appendLine()
                appendLine("--- ASCII LE ---")
                asciiLe.forEach { appendLine(it) }
            }
        }.trim()
    }

    private fun displayFactorySn(): String {
        val raw = bmsSn()
        if (raw.isBlank()) return ""
        val upper = raw.uppercase()
        val cut = BMS_VERSION_TOKENS.map { upper.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        return if (cut >= 0) raw.substring(0, cut).trim() else raw
    }

    private fun cachedFactorySerial(): String {
        val address = selectedAddress ?: return ""
        return identityPrefs().getString("sn_$address", "")?.trim().orEmpty()
    }

    private fun cachedBatteryCode(): String {
        val address = selectedAddress ?: return ""
        return identityPrefs().getString("code_$address", "")?.trim().orEmpty()
    }

    private fun cachedHwVersion(): String {
        val address = selectedAddress ?: return ""
        return identityPrefs().getString("hw_$address", "")?.trim().orEmpty()
    }

    private fun rememberFactorySerial() {
        val sn = factorySerialFromRegisters()
        val address = selectedAddress
        if (sn.isBlank() || address.isNullOrBlank()) return
        val editor = identityPrefs().edit().putString("sn_$address", sn)
        if (isServiceApp() && parseBmsHardwareVersion(bmsHwVersionText()).isBlank()) {
            val version = versionFromFactorySn()
            if (version.isNotBlank()) editor.putString("ver_$address", version)
        }
        editor.apply()
    }

    private fun rememberBatteryCode() {
        val code = liveBatteryCode()
        val address = selectedAddress
        if (code.isBlank() || address.isNullOrBlank()) return
        val editor = identityPrefs().edit().putString("code_$address", code)
        if (!isServiceApp()) {
            editor.putString("ver_$address", parseBmsHardwareVersion(code))
        }
        editor.apply()
    }

    private fun rememberHwVersion() {
        val hw = liveHwVersion()
        val address = selectedAddress
        if (hw.isBlank() || address.isNullOrBlank()) return
        val editor = identityPrefs().edit().putString("hw_$address", hw)
        if (isServiceApp()) {
            val version = parseBmsHardwareVersion(hw)
            if (version.isNotBlank()) editor.putString("ver_$address", version)
        }
        editor.apply()
    }

    private fun factorySerialFromRegisters(): String {
        val sn = factorySerialRaw()
        return if (isValidBmsSn(sn)) sn else ""
    }

    private fun factorySerialRaw(): String {
        if (configRegisters.isEmpty()) return ""
        if (isServiceApp()) {
            return factorySerialCandidates().firstOrNull { parseBmsHardwareVersion(it).isNotBlank() }
                ?: factorySerialCandidates().firstOrNull().orEmpty()
        }
        return decodeDalySnCode(swapBytes = false).ifBlank { decodeDalySnCode(swapBytes = true) }
    }

    // Daly SN Code: Modbus 0xD2, holding 0x0057–0x005D, 7 registers / 14 ASCII bytes.
    private fun decodeDalySnCode(swapBytes: Boolean): String {
        if ((DALY_SN_CODE_START..DALY_SN_CODE_END).any { it !in configRegisters }) return ""
        return buildString {
            for (addr in DALY_SN_CODE_START..DALY_SN_CODE_END) {
                val value = configRegisters[addr] ?: return ""
                val hi = (value shr 8) and 0xFF
                val lo = value and 0xFF
                val first = if (swapBytes) lo else hi
                val second = if (swapBytes) hi else lo
                for (b in intArrayOf(first, second)) {
                    if (b == 0) return@buildString
                    if (b !in 32..126) return ""
                    append(b.toChar())
                }
            }
        }.trim()
    }

    private fun isValidBmsSn(sn: String): Boolean {
        val value = sn.trim()
        if (value.length !in 8..20) return false
        if (value.startsWith("DL", ignoreCase = true)) return false
        return value.all { it.isLetterOrDigit() } && value.any { it.isLetter() } && value.any { it.isDigit() }
    }

    private fun assemblerName(): String {
        return servicePrefs.getString("assembler_name", "")?.trim().orEmpty()
    }

    private fun configCapacityAhFromHiLo(hiAddr: Int, loAddr: Int): Double? {
        val hi = configRegisters[hiAddr] ?: return null
        val lo = configRegisters[loAddr] ?: return null
        val raw = ((hi.toLong() and 0xFFFFL) shl 16) or (lo.toLong() and 0xFFFFL)
        val ah = raw / 1000.0
        return if (ah > 0.0 && ah < 5000.0) ah else null
    }

    private fun capacityMatchesTarget(targetAh: Double, tolerance: Double = 0.6): Boolean {
        for (pair in listOf(
            DALY_NOMINAL_CAPACITY_HI_REG to DALY_NOMINAL_CAPACITY_LO_REG,
            DALY_REMAINING_CAPACITY_HI_REG to DALY_REMAINING_CAPACITY_LO_REG
        )) {
            val actual = configCapacityAhFromHiLo(pair.first, pair.second) ?: continue
            if (kotlin.math.abs(actual - targetAh) <= tolerance) return true
        }
        return false
    }

    private fun reconstructedCapacityAh(): Double? {
        return configCapacityAhFromHiLo(DALY_REMAINING_CAPACITY_HI_REG, DALY_REMAINING_CAPACITY_LO_REG)
            ?: configCapacityAhFromHiLo(DALY_NOMINAL_CAPACITY_HI_REG, DALY_NOMINAL_CAPACITY_LO_REG)
    }

    private fun scaledRegisterValue(register: Int, scale: Double, offset: Double): Double? {
        val raw = configRegisters[register] ?: return null
        return (raw.toDouble() / scale) + offset
    }

    private fun registerMatchesExpected(
        register: Int,
        expected: Double,
        scale: Double,
        offset: Double,
        tolerance: Double
    ): Boolean {
        val raw = configRegisters[register] ?: return false
        val expectedRaw = Math.round((expected - offset) * scale).toInt()
        if (raw == expectedRaw) return true
        val actual = (raw.toDouble() / scale) + offset
        return kotlin.math.abs(actual - expected) <= tolerance
    }

    private fun currentNominalCapacityAh(): Double? {
        configCapacityAhFromHiLo(DALY_REMAINING_CAPACITY_HI_REG, DALY_REMAINING_CAPACITY_LO_REG)?.let { return it }
        configCapacityAhFromHiLo(DALY_NOMINAL_CAPACITY_HI_REG, DALY_NOMINAL_CAPACITY_LO_REG)?.let { return it }
        val soc = data.soc
        val rem = data.remainingAh
        if (rem != null && rem > 0.0 && soc != null && soc >= 99.0) return rem
        return data.estimatedFullAh
    }

    private fun capacityAlreadyMatches(targetAh: Double): Boolean {
        fun close(v: Double?) = v != null && kotlin.math.abs(v - targetAh) <= 0.6
        return close(configCapacityAhFromHiLo(DALY_REMAINING_CAPACITY_HI_REG, DALY_REMAINING_CAPACITY_LO_REG)) &&
            close(configCapacityAhFromHiLo(DALY_NOMINAL_CAPACITY_HI_REG, DALY_NOMINAL_CAPACITY_LO_REG))
    }

    private fun remoteWriteAlreadyMatches(command: RemoteWriteCommand): Boolean {
        if (command.key == "series_cell_count") {
            return data.cellCount == command.rawValue
        }
        if (command.key == "nominal_capacity") {
            val target = command.displayValue ?: serviceWriteCapacityAh ?: return false
            return capacityAlreadyMatches(target)
        }
        val raw = configRegisters[command.register] ?: return false
        if (raw == command.rawValue) return true
        val actual = (raw.toDouble() / command.scale) + command.offset
        return kotlin.math.abs(actual - command.value) <= 0.05
    }

    private fun advertisedBluetoothName(): String {
        val address = selectedAddress
        if (!address.isNullOrBlank()) {
            sanitizeBleText(scanNames[address]).takeIf { it.isNotBlank() }?.let { return it }
            loadSavedBatteries().firstOrNull { it.address == address }
                ?.bluetoothName
                ?.let { sanitizeBleText(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return sanitizeBleText(selectedDeviceName)
    }

    private fun isLegacyBmsWithoutBalancer(): Boolean {
        return bmsHardwareVersion().equals("R10K", ignoreCase = true)
    }

    private fun currentHardwareFamily(): String {
        return if (isLegacyBmsWithoutBalancer()) HARDWARE_FAMILY_R10K else HARDWARE_FAMILY_STANDARD
    }

    private fun shouldSkipTemplateParameter(parameter: BmsTemplateParameter, hardwareFamily: String): Boolean {
        if (isLegacyBmsWithoutBalancer() && parameter.key in R10K_SKIPPED_TEMPLATE_KEYS) return true
        return hardwareFamily in parameter.skipFor
    }

    private fun dlUnsupportedOr(value: String): String {
        return if (isLegacyBmsWithoutBalancer()) "не настраивается" else value
    }

    private fun addTk10NoticeIfNeeded(text: String) {
        if (!isLegacyBmsWithoutBalancer() || !::manageContentLayout.isInitialized) return
        manageContentLayout.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = round(Color.rgb(255, 236, 236), dp(14), Color.rgb(196, 40, 40), 1)
                addView(TextView(this@MainActivity).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(Color.rgb(140, 30, 30))
                })
            },
            marginLp(-1, -2, 0, 0, 0, 12)
        )
    }

    private fun putHardwareIdentity(obj: JSONObject) {
        val advertised = dalyBluetoothDeviceId()
        obj.put("advertised_name", advertised)
        obj.put("hardware_family", currentHardwareFamily())
        obj.put("bluetooth_id", bluetoothId())
        obj.put("bms_sn", displayFactorySn())
        obj.put("bms_battery_code", bmsBatteryCode())
        obj.put("bms_hw_version", bmsHwVersionText())
        obj.put("bms_version", bmsHardwareVersion())
        obj.put("source", if (isServiceApp()) "service" else "user")
        if (isServiceApp()) obj.put("assembler_name", assemblerName())
        putOwnerProfile(obj)
    }

    private fun putOwnerProfile(obj: JSONObject) {
        val prefs = getSharedPreferences("user_profile", MODE_PRIVATE)
        obj.put("owner_name", prefs.getString("name", "")?.trim().orEmpty())
        obj.put("owner_phone", prefs.getString("phone", "")?.trim().orEmpty())
        obj.put("owner_email", prefs.getString("email", "")?.trim().orEmpty())
    }

    private fun addLocalEvent(text: String, detail: String = text) {
        // В пользовательском журнале показываем только события BMS.
        val serviceWords = listOf(
            "Данные отправлены",
            "Не удалось отправить",
            "Подключение к BMS",
            "Отключено от BMS",
            "Запущено чтение",
            "Historical RAW",
            "Ответов на команды",
            "Получено RAW"
        )
        if (serviceWords.any { text.contains(it, ignoreCase = true) }) return

        val line = "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} — $text"
        localEvents.add(line)
        localEventDetails.add(detail)
        if (localEvents.size > 300) {
            localEvents.removeAt(0)
            if (localEventDetails.isNotEmpty()) localEventDetails.removeAt(0)
        }
    }

    private fun dalyErrorDescription(globalBit: Int): String {
        // Daly UART/RS485 0x98 Battery failure status.
        // Byte0..Byte6 = битовые ошибки, Byte7 = дополнительный fault code.
        // Важно:
        // - bit12 по официальной таблице = температура разрядки высокая, уровень 1.
        // - bit18 по официальной таблице = ток разрядки превышен, уровень 1.
        // - ошибка датчика температуры ячеек = bit42.
        // - состояние MOS зарядки/разрядки читаем отдельно из 0x93, а не из 0x98.
        return when (globalBit) {
            0 -> "Напряжение ячейки высокое, уровень 1"
            1 -> "Напряжение ячейки высокое, уровень 2"
            2 -> "Напряжение ячейки низкое, уровень 1"
            3 -> "Напряжение ячейки низкое, уровень 2"
            4 -> "Общее напряжение высокое, уровень 1"
            5 -> "Общее напряжение высокое, уровень 2"
            6 -> "Общее напряжение низкое, уровень 1"
            7 -> "Общее напряжение низкое, уровень 2"

            8 -> "Температура зарядки высокая, уровень 1"
            9 -> "Температура зарядки высокая, уровень 2"
            10 -> "Температура зарядки низкая, уровень 1"
            11 -> "Температура зарядки низкая, уровень 2"
            12 -> "Температура разрядки высокая, уровень 1"
            13 -> "Температура разрядки высокая, уровень 2"
            14 -> "Температура разрядки низкая, уровень 1"
            15 -> "Температура разрядки низкая, уровень 2"

            16 -> "Ток зарядки превышен, уровень 1"
            17 -> "Ток зарядки превышен, уровень 2"
            18 -> "Ток разрядки превышен, уровень 1"
            19 -> "Ток разрядки превышен, уровень 2"
            20 -> "SOC высокий, уровень 1"
            21 -> "SOC высокий, уровень 2"
            22 -> "SOC низкий, уровень 1"
            23 -> "SOC низкий, уровень 2"

            24 -> "Разбег напряжений ячеек, уровень 1"
            25 -> "Разбег напряжений ячеек, уровень 2"
            26 -> "Разбег температур, уровень 1"
            27 -> "Разбег температур, уровень 2"
            28 -> "Резерв Byte3 Bit4"
            29 -> "Резерв Byte3 Bit5"
            30 -> "Резерв Byte3 Bit6"
            31 -> "Резерв Byte3 Bit7"

            32 -> "Температура MOS зарядки высокая"
            33 -> "Температура MOS разрядки высокая"
            34 -> "Ошибка датчика температуры MOS зарядки"
            35 -> "Ошибка датчика температуры MOS разрядки"
            36 -> "Залипание MOS зарядки"
            37 -> "Залипание MOS разрядки"
            38 -> "Обрыв MOS зарядки"
            39 -> "Обрыв MOS разрядки"

            40 -> "Ошибка AFE / чипа измерения"
            41 -> "Отвалился сбор напряжения"
            42 -> "Ошибка датчика температуры ячеек"
            43 -> "Ошибка EEPROM"
            44 -> "Ошибка RTC"
            45 -> "Ошибка предзаряда"
            46 -> "Ошибка связи"
            47 -> "Ошибка внутренней связи"

            48 -> "Ошибка токового модуля"
            49 -> "Ошибка измерения общего напряжения"
            50 -> "Защита от короткого замыкания"
            51 -> "Запрет зарядки из-за низкого напряжения"
            52 -> "Резерв Byte6 Bit4"
            53 -> "Резерв Byte6 Bit5"
            54 -> "Резерв Byte6 Bit6"
            55 -> "Резерв Byte6 Bit7"

            // Byte7 в протоколе указан как fault code, а не как стандартные битовые аварии.
            56 -> "Дополнительный fault code Byte7 Bit0"
            57 -> "Дополнительный fault code Byte7 Bit1"
            58 -> "Дополнительный fault code Byte7 Bit2"
            59 -> "Дополнительный fault code Byte7 Bit3"
            60 -> "Дополнительный fault code Byte7 Bit4"
            61 -> "Дополнительный fault code Byte7 Bit5"
            62 -> "Дополнительный fault code Byte7 Bit6"
            63 -> "Дополнительный fault code Byte7 Bit7"

            else -> "Неизвестная ошибка Daly"
        }
    }

    private fun hasTemperatureSensorError(): Boolean {
        // Официально для 0x98:
        // bit42 = ошибка датчика температуры ячеек.
        // Также считаем ошибкой датчики температуры MOS.
        return data.errors.any {
            it.contains("Ошибка датчика температуры ячеек", ignoreCase = true) ||
            it.contains("Ошибка датчика температуры MOS", ignoreCase = true) ||
            it.contains("temp sensor", ignoreCase = true)
        }
    }

    private fun detectAndStoreEvents() {
        val errSig = data.errors.joinToString("|")
        if (errSig != lastErrorSignature) {
            if (data.errors.isEmpty()) {
                if (lastErrorSignature.isNotBlank()) {
                    addLocalEvent(
                        "Ошибки BMS сброшены",
                        "BMS больше не отдает активные биты ошибок в команде 0x98."
                    )
                }
            } else {
                addLocalEvent(
                    "Ошибки BMS: ${data.errors.joinToString(", ")}",
                    buildErrorDetails()
                )
            }
            lastErrorSignature = errSig
        }

        val chg = data.chargeMos
        if (chg != null && chg != lastChargeMos) {
            addLocalEvent(
                "MOS зарядки: ${if (chg) "ON" else "OFF"}",
                "Состояние MOS зарядки изменилось.\nТекущее состояние: ${if (chg) "ON / включен" else "OFF / отключен"}\n\nRAW 0x93: ${data.raw["0x93"] ?: "нет"}"
            )
            lastChargeMos = chg
        }

        val dsg = data.dischargeMos
        if (dsg != null && dsg != lastDischargeMos) {
            addLocalEvent(
                "MOS разрядки: ${if (dsg) "ON" else "OFF"}",
                "Состояние MOS разрядки изменилось.\nТекущее состояние: ${if (dsg) "ON / включен" else "OFF / отключен"}\n\nRAW 0x93: ${data.raw["0x93"] ?: "нет"}"
            )
            lastDischargeMos = dsg
        }

        val diff = data.cellDiffV
        if (diff != null && diff >= 0.080) {
            addLocalEvent(
                "Большой разбег ячеек: ${"%.3f".format(diff)} V",
                "Разбег напряжения ячеек превышает 0.080 В.\nПерепад: ${"%.3f".format(diff)} В\nMin: ${data.minCellV ?: "--"} В\nMax: ${data.maxCellV ?: "--"} В"
            )
        }
    }

    private fun buildErrorDetails(): String {
        val lines = mutableListOf<String>()
        lines.add("Активные ошибки BMS по команде 0x98:")
        lines.add("")
        for (err in data.errors) {
            lines.add("• $err")
        }
        lines.add("")
        lines.add("Примечание:")
        lines.add("• Состояние MOS зарядки/разрядки читается отдельно из команды 0x93.")
        lines.add("• 0x98 показывает аварии и защиты BMS.")
        lines.add("")
        lines.add("RAW 0x98:")
        lines.add(data.raw["0x98"] ?: "нет")
        return lines.joinToString("\n")
    }

    private fun currentUploadStatusText(): String {
        return "$lastUploadStatus\n${adminServerBaseUrl()}$LOCAL_UPLOAD_PATH"
    }

    private fun refreshUploadStatusUi() {
        runOnUiThread {
            val text = currentUploadStatusText()
            dashboardUploadStatusText?.text = text
            serviceUploadStatusText?.text = text
        }
    }

    private fun pingAdminServer() {
        lastUploadStatus = "Проверка связи..."
        refreshUploadStatusUi()
        thread {
            val url = adminServerUrl("/health")
            val message = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } catch (_: Exception) {
                    conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                }
                conn.disconnect()
                if (code == 200 && body.contains("\"ok\":true")) {
                    "Связь с сервером есть"
                } else {
                    "Сервер ответил HTTP $code"
                }
            } catch (e: Exception) {
                "Нет связи с ${adminServerBaseUrl()}: ${e.message ?: e.toString()}. Телефон и ПК должны быть в одной Wi‑Fi сети."
            }
            lastUploadStatus = message
            runOnUiThread {
                refreshUploadStatusUi()
                toast(lastUploadStatus)
            }
        }
    }

    private fun uploadCurrentData(force: Boolean) {
        if (uploading) return
        if (data.voltage == null && data.soc == null && !force) return

        val now = System.currentTimeMillis()
        if (!force && now - lastUploadAt < UPLOAD_INTERVAL_MS) return

        val payload = buildUploadJson()
        uploading = true
        lastUploadStatus = "Отправка..."
        refreshUploadStatusUi()

        thread {
            var statusMessage = ""
            var ok = false
            try {
                val conn = (URL(adminServerUrl(LOCAL_UPLOAD_PATH)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }

                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val response = try {
                    if (code in 200..299) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                } catch (_: Exception) {
                    ""
                }

                if (code in 200..299 && response.contains("\"ok\":true")) {
                    ok = true
                    lastUploadAt = System.currentTimeMillis()
                    val logId = Regex("\"log_id\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    lastLogId = logId
                    statusMessage = "Успешно отправлено${logId?.let { ", log_id=$it" } ?: ""}"
                } else {
                    statusMessage = "Ошибка сервера HTTP $code: ${response.take(160)}"
                }
                conn.disconnect()
            } catch (e: Exception) {
                statusMessage = "Ошибка отправки: ${e.message ?: e.toString()}"
            } finally {
                uploading = false
                lastUploadStatus = statusMessage
                runOnUiThread {
                    refreshUploadStatusUi()
                    if (force || (isServiceApp() && !ok)) toast(lastUploadStatus)
                    if (screenState == "journal") showJournalScreen()
                }
            }
        }
    }

    private fun originalDalyConfigReadRequests(): List<ConfigReadRequest> {
        // Команды взяты не из предположений, а из HCI snoop оригинального Daly BMS.
        // Запросы пишутся в BLE handle 0x0014, ответы приходят notification с handle 0x0010.
        // Важно: запрос начинается с 0x81, а ответ BMS начинается с 0x51.
        // CRC — обычный Modbus Low/High.
        return listOf(
            // SN Code — публичный Modbus 0xD2, регистры 0x0057–0x005D (ASCII).
            ConfigReadRequest("dl_sn_0057_005D", 0xD2, DALY_SN_CODE_START, DALY_SN_CODE_COUNT),
            // Читаем только регистры настроек, которые реально используются в приложении и на сайте.
            // Runtime-блоки 0x0000/0x0041 читаются обычными быстрыми командами A5 0x90-0x98,
            // поэтому здесь они не нужны и только замедляют подключение.
            ConfigReadRequest("dl_settings_0100_0150", 0x81, 0x0100, 0x51),
            ConfigReadRequest("dl_settings_0151_0177", 0x81, 0x0151, 0x27),
            ConfigReadRequest("dl_settings_01C3_0212", 0x81, 0x01C3, 0x50),
            ConfigReadRequest("dl_balance_0220_022A", 0x81, 0x0220, 0x0B),
            ConfigReadRequest("dl_soc100_0229", 0x81, 0x0229, 0x01),
            ConfigReadRequest("dl_comm_024B_024C", 0x81, 0x024B, 0x02)
        )
    }

    private fun resetRemoteWriteState() {
        remoteWriteInProgress = false
        remoteWriteAwaitingVerify = false
        pendingRemoteWrite = null
        stopRemoteWritePolling()
    }

    private fun startRemoteWritePolling() {
        if (isServiceApp()) return
        mainHandler.removeCallbacks(remoteWritePollRunnable)
        mainHandler.postDelayed(remoteWritePollRunnable, 1500)
    }

    private fun stopRemoteWritePolling() {
        mainHandler.removeCallbacks(remoteWritePollRunnable)
    }

    private fun fetchAndApplyRemoteWrites(force: Boolean = false) {
        if (isServiceApp()) return
        if (remoteWriteInProgress || configReadInProgress) return
        val uid = bmsUid()
        if (bluetoothGatt == null || writeCharacteristic == null) return
        if (uid.isBlank() || uid == "unknown_bms") return
        val now = System.currentTimeMillis()
        if (!force && now - lastRemoteWritePollAt < 2500L) return
        lastRemoteWritePollAt = now

        thread {
            val encoded = URLEncoder.encode(uid, "UTF-8")
            val json = adminJsonRequest(
                "GET",
                "/api/v1/batteries/$encoded/write-commands?status=pending&limit=20"
            )
            val commands = json?.optJSONArray("commands") ?: JSONArray()
            val parsed = if (commands.length() > 0) {
                parseRemoteWriteCommand(commands.optJSONObject(0))
            } else {
                null
            }
            runOnUiThread {
                if (parsed != null) startRemoteWrite(parsed)
            }
        }
    }

    private fun parseRemoteWriteCommand(obj: JSONObject?): RemoteWriteCommand? {
        if (obj == null) return null
        val id = obj.optInt("id", 0)
        val key = obj.optString("key")
        val registerText = obj.optString("register")
        val rawValue = obj.optInt("raw_value", Int.MIN_VALUE)
        val value = obj.optDouble("value", Double.NaN)
        if (id <= 0 || key.isBlank() || registerText.isBlank() || rawValue == Int.MIN_VALUE || value.isNaN()) {
            return null
        }
        val register = try {
            registerText.removePrefix("0x").removePrefix("0X").toInt(16)
        } catch (_: Exception) {
            return null
        }
        return RemoteWriteCommand(
            id = id,
            key = key,
            label = obj.optString("label").ifBlank { key },
            register = register,
            rawValue = rawValue,
            value = value,
            scale = obj.optDouble("scale", 1.0).takeIf { it != 0.0 } ?: 1.0,
            offset = obj.optDouble("offset", 0.0),
            unit = obj.optString("unit")
        )
    }

    @SuppressLint("MissingPermission")
    private fun startRemoteWrite(command: RemoteWriteCommand) {
        if (command.localOnly && isServiceApp()) {
            startServiceLocalWrite(command)
            return
        }
        if (remoteWriteInProgress || configReadInProgress) {
            if (command.localOnly) {
                mainHandler.postDelayed({ startRemoteWrite(command) }, 350)
            }
            return
        }
        if (bluetoothGatt == null || writeCharacteristic == null) {
            if (command.localOnly) completeServiceWriteStep(command, false, null, "no_ble")
            return
        }
        if (remoteWriteAlreadyMatches(command)) {
            val actual = if (command.key == "nominal_capacity") {
                currentNominalCapacityAh()
            } else {
                scaledRegisterValue(command.register, command.scale, command.offset)
            }
            if (!command.localOnly) {
                toast("${command.label}: уже совпадает")
            }
            if (command.localOnly) {
                completeServiceWriteStep(command, true, actual, null)
            } else {
                ackRemoteWrite(command, "done", actual, null)
                mainHandler.postDelayed({ fetchAndApplyRemoteWrites(force = true) }, 400)
            }
            return
        }

        pendingRemoteWrite = command
        remoteWriteInProgress = true
        remoteWriteAwaitingVerify = false
        pollLoopToken++
        val prefix = if (command.localOnly) "Запись" else "Запись с сайта"
        toast("$prefix: ${command.label} = ${formatTemplateNumber(command.displayValue ?: command.value)} ${command.unit}".trim())
        if (!command.localOnly) ackRemoteWrite(command, "writing", null, null)

        val timeFrame = buildDalyTimeFrame()
        val writeFrame = buildModbusWriteSingleRequest(0x81, command.register, command.rawValue)
        val openFrame = buildModbusWriteSingleRequest(0x81, 0x0174, 0x00A2)
        configRaw["remote_write_key"] = command.key
        configRaw["remote_write_raw"] = command.rawValue.toString()
        configRaw["remote_write_frame"] = bytesToHex(writeFrame)

        val timeOk = writeBleFrame(timeFrame)
        configRaw["remote_write_time"] = if (timeOk) "ok" else "failed"
        mainHandler.postDelayed({
            val unlockOk = writeBleFrame(openFrame)
            configRaw["remote_write_unlock"] = if (unlockOk) "ok" else "failed"
            mainHandler.postDelayed({
                val writeOk = writeBleFrame(writeFrame)
                configRaw["remote_write_reg"] = if (writeOk) "ok" else "failed"
                if (!writeOk) {
                    failRemoteWrite("ble_write_failed")
                    return@postDelayed
                }
                mainHandler.postDelayed({
                    remoteWriteAwaitingVerify = true
                    startConfigReadIfNeeded(force = true)
                    if (!configReadInProgress) {
                        mainHandler.postDelayed({
                            if (remoteWriteAwaitingVerify && !configReadInProgress) {
                                failRemoteWrite("config_read_not_started")
                            }
                        }, 400)
                    }
                }, 900)
            }, 250)
        }, 140)
    }

    @SuppressLint("MissingPermission")
    private fun startServiceLocalWrite(command: RemoteWriteCommand) {
        if (remoteWriteInProgress || configReadInProgress) {
            mainHandler.postDelayed({ startServiceLocalWrite(command) }, 150)
            return
        }
        if (bluetoothGatt == null || writeCharacteristic == null) {
            completeServiceWriteStep(command, false, null, "no_ble")
            return
        }
        if (remoteWriteAlreadyMatches(command)) {
            completeServiceWriteStep(
                command,
                true,
                command.displayValue ?: command.value,
                null
            )
            return
        }
        if (command.key == "series_cell_count") {
            startSeriesCountLocalWrite(command)
            return
        }

        remoteWriteInProgress = true
        pendingRemoteWrite = command
        pollLoopToken++

        val timeFrame = buildDalyTimeFrame()
        val openFrame = buildModbusWriteSingleRequest(0x81, 0x0174, 0x00A2)
        val writeFrames = command.writeFrames ?: listOf(
            buildModbusWriteSingleRequest(0x81, command.register, command.rawValue)
        )

        fun writeFrameAt(index: Int) {
            if (index >= writeFrames.size) {
                remoteWriteInProgress = false
                pendingRemoteWrite = null
                completeServiceWriteStep(
                    command,
                    true,
                    command.displayValue ?: command.value,
                    null
                )
                return
            }
            writeBleFrame(timeFrame)
            mainHandler.postDelayed({
                writeBleFrame(writeFrames[index])
                mainHandler.postDelayed({
                    writeBleFrame(openFrame)
                    mainHandler.postDelayed({ writeFrameAt(index + 1) }, 120L)
                }, 120L)
            }, 80L)
        }

        writeFrameAt(0)
    }

    @SuppressLint("MissingPermission")
    private fun startSeriesCountLocalWrite(command: RemoteWriteCommand) {
        remoteWriteInProgress = true
        pendingRemoteWrite = command
        pollLoopToken++
        val frames = command.writeFrames ?: buildDalyCellCountWriteFrames(command.rawValue)
        fun writeAt(index: Int) {
            if (index >= frames.size) {
                writeBleFrame(buildRequest(0x94))
                mainHandler.postDelayed({
                    val actual = data.cellCount?.toDouble()
                    val ok = data.cellCount == command.rawValue
                    remoteWriteInProgress = false
                    pendingRemoteWrite = null
                    completeServiceWriteStep(
                        command,
                        ok,
                        actual,
                        if (ok) null else "not_confirmed"
                    )
                }, 600)
                return
            }
            writeBleFrame(frames[index])
            mainHandler.postDelayed({ writeAt(index + 1) }, 160L)
        }
        writeAt(0)
    }

    @SuppressLint("MissingPermission")
    private fun writeBleFrame(frame: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val ch = writeCharacteristic ?: return false
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = frame
            gatt.writeCharacteristic(ch)
        } catch (e: Exception) {
            configRaw["remote_write_error"] = e.message ?: e.toString()
            false
        }
    }

    private fun finishRemoteWriteVerification() {
        val command = pendingRemoteWrite
        remoteWriteAwaitingVerify = false
        if (command == null) {
            remoteWriteInProgress = false
            pollOnce()
            return
        }
        val raw = configRegisters[command.register]
        val actual = raw?.let { (it.toDouble() / command.scale) + command.offset }
        val ok = raw != null && (
            raw == command.rawValue ||
                (actual != null && kotlin.math.abs(actual - command.value) <= 0.05)
            )
        configRaw["remote_write_verify"] = if (ok) {
            "OK raw=$raw actual=$actual"
        } else {
            "NOT_CONFIRMED raw=${raw ?: "null"} actual=${actual ?: "null"} expected_raw=${command.rawValue}"
        }
        if (command.localOnly) {
            pendingRemoteWrite = null
            remoteWriteInProgress = false
            rememberCurrentBmsState()
            completeServiceWriteStep(command, ok, actual, if (ok) null else "not_confirmed")
            mainHandler.postDelayed({ pollOnce() }, 400)
            return
        }
        ackRemoteWrite(
            command,
            if (ok) "done" else "failed",
            actual,
            if (ok) null else "not_confirmed"
        )
        pendingRemoteWrite = null
        remoteWriteInProgress = false
        rememberCurrentBmsState()
        refreshManageIfVisible()
        toast(
            if (ok) "${command.label}: ${formatTemplateNumber(actual)} ${command.unit}".trim()
            else "Не подтверждено: ${command.label}"
        )
        uploadConfigSnapshot(force = true)
        mainHandler.postDelayed({ pollOnce() }, 400)
        mainHandler.postDelayed({ fetchAndApplyRemoteWrites(force = true) }, 1200)
    }

    private fun failRemoteWrite(reason: String) {
        val command = pendingRemoteWrite
        remoteWriteAwaitingVerify = false
        remoteWriteInProgress = false
        pendingRemoteWrite = null
        if (command != null) {
            if (command.localOnly) {
                completeServiceWriteStep(command, false, null, reason)
            } else {
                ackRemoteWrite(command, "failed", null, reason)
            }
        }
        toast("Не удалось записать параметр: $reason")
        mainHandler.postDelayed({ pollOnce() }, 400)
        if (command == null || !command.localOnly) {
            mainHandler.postDelayed({ fetchAndApplyRemoteWrites(force = true) }, 800)
        }
    }

    private fun ackRemoteWrite(command: RemoteWriteCommand, status: String, actual: Double?, error: String?) {
        if (command.localOnly || command.id <= 0) return
        val uid = bmsUid()
        thread {
            val encoded = URLEncoder.encode(uid, "UTF-8")
            val body = JSONObject().apply {
                put("api_key", SERVER_API_KEY)
                put("status", status)
                if (actual != null && actual.isFinite()) put("actual", actual)
                if (!error.isNullOrBlank()) put("error", error)
            }
            adminJsonRequest("POST", "/api/v1/batteries/$encoded/write-commands/${command.id}/ack", body)
        }
    }

    private fun adminJsonRequest(method: String, path: String, body: JSONObject? = null): JSONObject? {
        return try {
            val conn = (URL(adminServerUrl(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-api-key", SERVER_API_KEY)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            }
            conn.disconnect()
            if (text.isBlank()) return null
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(BLE_LOG_TAG, "admin request $method $path failed: ${e.message}")
            null
        }
    }

    private fun startConfigReadIfNeeded(force: Boolean) {
        if (configReadInProgress) return
        if (configUploading && !remoteWriteAwaitingVerify) return
        if (remoteWriteInProgress && !remoteWriteAwaitingVerify) return
        val now = System.currentTimeMillis()
        if (!force && now - lastConfigUploadAt < CONFIG_UPLOAD_INTERVAL_MS) return

        val gatt = bluetoothGatt ?: return
        val ch = writeCharacteristic ?: return

        configReadInProgress = true
        setTemplateCheckChecking()
        pollLoopToken++
        pendingRuntimeCommand = null
        activeConfigRead = null
        configModbusBuffer.clear()

        // Ручное обновление: очищаем старые значения и читаем свежие.
        // После записи параметра не чистим карту — проверяем только записанный регистр.
        if (force && !remoteWriteAwaitingVerify) {
            configRegisters.clear()
            configLoadedFromCache = false
        }

        configRaw.clear()
        configReadQueue.clear()
        val verifyCommand = pendingRemoteWrite.takeIf { remoteWriteAwaitingVerify }
        if (verifyCommand != null) {
            val slave = if (verifyCommand.register in DALY_SN_CODE_START..DALY_SN_CODE_END) {
                0xD2
            } else {
                0x81
            }
            configReadQueue.add(
                ConfigReadRequest("verify_${verifyCommand.key}", slave, verifyCommand.register, 1)
            )
        } else {
            for (req in originalDalyConfigReadRequests()) {
                configReadQueue.add(req)
            }
        }
        lastConfigStatus = "auto_read_once_hci_exact_daly_registers"
        refreshManageIfVisible()

        sendHciPreflightThenRead(gatt, ch)
    }

    @SuppressLint("MissingPermission")
    private fun sendHciPreflightThenRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        // В оригинальном приложении перед чтением настроек идут две служебные команды:
        // 81 10 01 23 00 03 + YY MM DD HH mm ss + CRC
        // 81 06 01 74 00 A2 + CRC
        // Это не запись параметров защиты, а служебная синхронизация/разрешение доступа.
        val timeFrame = buildDalyTimeFrame()
        val openFrame = buildModbusWriteSingleRequest(0x81, 0x0174, 0x00A2)

        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = timeFrame
        configRaw["request_hci_time_0123"] = bytesToHex(timeFrame)
        configRaw["write_hci_time_0123"] = if (gatt.writeCharacteristic(ch)) "ok" else "failed"
        refreshManageIfVisible()

        mainHandler.postDelayed({
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = openFrame
            configRaw["request_hci_open_0174"] = bytesToHex(openFrame)
            configRaw["write_hci_open_0174"] = if (gatt.writeCharacteristic(ch)) "ok" else "failed"
            refreshManageIfVisible()
        }, 500)

        mainHandler.postDelayed({
            sendNextConfigRead(gatt, ch)
        }, 1200)
    }

    private fun buildDalyTimeFrame(): ByteArray {
        val cal = Calendar.getInstance()
        val payload = byteArrayOf(
            ((cal.get(Calendar.YEAR) - 2000) and 0xFF).toByte(),
            ((cal.get(Calendar.MONTH) + 1) and 0xFF).toByte(),
            (cal.get(Calendar.DAY_OF_MONTH) and 0xFF).toByte(),
            (cal.get(Calendar.HOUR_OF_DAY) and 0xFF).toByte(),
            (cal.get(Calendar.MINUTE) and 0xFF).toByte(),
            (cal.get(Calendar.SECOND) and 0xFF).toByte()
        )
        val frame = ByteArray(6 + payload.size + 2)
        frame[0] = 0x81.toByte()
        frame[1] = 0x10.toByte()
        frame[2] = 0x01.toByte()
        frame[3] = 0x23.toByte()
        frame[4] = 0x00.toByte()
        frame[5] = 0x03.toByte()
        for (i in payload.indices) frame[6 + i] = payload[i]
        val crc = modbusCrc16(frame, frame.size - 2)
        frame[frame.size - 2] = (crc and 0xFF).toByte()
        frame[frame.size - 1] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    private fun buildModbusWriteSingleRequest(slave: Int, register: Int, value: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = (slave and 0xFF).toByte()
        frame[1] = 0x06.toByte()
        frame[2] = ((register shr 8) and 0xFF).toByte()
        frame[3] = (register and 0xFF).toByte()
        frame[4] = ((value shr 8) and 0xFF).toByte()
        frame[5] = (value and 0xFF).toByte()
        val crc = modbusCrc16(frame, 6)
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    private fun buildModbusWriteMultipleRegisters(slave: Int, startRegister: Int, values: IntArray): ByteArray {
        val byteCount = values.size * 2
        val frame = ByteArray(7 + byteCount + 2)
        frame[0] = (slave and 0xFF).toByte()
        frame[1] = 0x10.toByte()
        frame[2] = ((startRegister shr 8) and 0xFF).toByte()
        frame[3] = (startRegister and 0xFF).toByte()
        frame[4] = ((values.size shr 8) and 0xFF).toByte()
        frame[5] = (values.size and 0xFF).toByte()
        frame[6] = byteCount.toByte()
        for (i in values.indices) {
            val v = values[i]
            frame[7 + i * 2] = ((v shr 8) and 0xFF).toByte()
            frame[8 + i * 2] = (v and 0xFF).toByte()
        }
        val crc = modbusCrc16(frame, frame.size - 2)
        frame[frame.size - 2] = (crc and 0xFF).toByte()
        frame[frame.size - 1] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    @SuppressLint("MissingPermission")
    private fun sendNextConfigRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (!configReadInProgress) return

        if (configReadQueue.isEmpty()) {
            configReadInProgress = false
            activeConfigRead = null
            lastConfigStatus = if (configRegisters.isEmpty()) {
                "no_modbus_config_response"
            } else {
                "ok_original_daly_modbus_registers"
            }
            if (configRegisters.isNotEmpty()) {
                rememberFactorySerial()
                saveConfigCacheForCurrentBms()
            }
            setTemplateCheckResult(evaluateTemplateCheck())
            rememberCurrentBmsState()
            refreshManageIfVisible()
            runOnUiThread { updateDashboardUi() }
            uploadCurrentData(force = true)

            if (serviceVerifyNeedsReadAfterCurrent) {
                serviceVerifyNeedsReadAfterCurrent = false
                val gatt = bluetoothGatt
                val ch = writeCharacteristic
                if (gatt != null && ch != null) {
                    configReadInProgress = true
                    configReadQueue.clear()
                    for (req in originalDalyConfigReadRequests()) {
                        configReadQueue.add(req)
                    }
                    sendHciPreflightThenRead(gatt, ch)
                }
                return
            }

            if (pendingServiceWriteFinalVerify) {
                finishServiceBatchWrite()
                mainHandler.postDelayed({ pollOnce() }, 800)
                return
            }

            if (remoteWriteAwaitingVerify) {
                finishRemoteWriteVerification()
                return
            }

            mainHandler.postDelayed({
                rememberCurrentBmsState()
                uploadConfigSnapshot(force = true)
                scheduleExactSocWriteTest()
                fetchAndApplyRemoteWrites()
            }, 1200)

            mainHandler.postDelayed({ pollOnce() }, 800)
            return
        }

        val req = configReadQueue.removeFirst()
        activeConfigRead = req
        configModbusBuffer.clear()

        val frame = buildModbusReadRequest(req.slave, req.start, req.count)
        configRaw["request_${req.name}"] = bytesToHex(frame)

        // В HCI логе оригинальное приложение отправляет команды как ATT Write Command (0x52), без ответа на запись.
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.value = frame
        val writeOk = gatt.writeCharacteristic(ch)
        Log.d(
            BLE_LOG_TAG,
            "config request ${req.name} write=$writeOk uuid=${ch.uuid}: ${bytesToHex(frame)}"
        )
        configRaw["write_${req.name}"] = if (writeOk) "ok" else "failed"
        refreshManageIfVisible()

        mainHandler.postDelayed({
            if (configReadInProgress && activeConfigRead == req) {
                configRaw["timeout_${req.name}"] = "timeout"
                activeConfigRead = null
                refreshManageIfVisible()
                sendNextConfigRead(gatt, ch)
            }
        }, 5000)
    }

    private fun handleConfigModbusIncoming(bytes: ByteArray) {
        synchronized(configModbusBuffer) {
            for (b in bytes) configModbusBuffer.add(b)

            while (true) {
                if (configModbusBuffer.size < 5) return

                // Different Daly firmware revisions answer as 0x51, echo 0x81,
                // or expose another Modbus slave address. Detect a frame by its
                // function byte and CRC later instead of hard-coding one address.
                var startIndex = -1
                val knownFunctions = setOf(0x03, 0x06, 0x10, 0x83, 0x86, 0x90)
                for (i in 0 until configModbusBuffer.lastIndex) {
                    val slave = configModbusBuffer[i].toInt() and 0xFF
                    val function = configModbusBuffer[i + 1].toInt() and 0xFF
                    if (slave != (FRAME_START.toInt() and 0xFF) && function in knownFunctions) {
                        startIndex = i
                        break
                    }
                }

                if (startIndex < 0) {
                    if (configModbusBuffer.size > 1024) configModbusBuffer.clear()
                    return
                }

                repeat(startIndex) { configModbusBuffer.removeAt(0) }
                if (configModbusBuffer.size < 5) return

                val func = configModbusBuffer[1].toInt() and 0xFF

                if (func == 0x10 || func == 0x06) {
                    // Ответ на служебные команды 0x10/0x06: 8 байт.
                    if (configModbusBuffer.size < 8) return
                    val frame = ByteArray(8)
                    for (i in 0 until 8) frame[i] = configModbusBuffer.removeAt(0)
                    configRaw["response_service_0x%02X".format(func)] = bytesToHex(frame)
                    refreshManageIfVisible()
                    continue
                }

                if (func == 0x83 || func == 0x90 || func == 0x86) {
                    if (configModbusBuffer.size < 5) return
                    val frame = ByteArray(5)
                    for (i in 0 until 5) frame[i] = configModbusBuffer.removeAt(0)
                    val reqName = activeConfigRead?.name ?: "unknown"
                    configRaw["exception_$reqName"] = bytesToHex(frame)
                    activeConfigRead = null
                    refreshManageIfVisible()
                    val gatt = bluetoothGatt
                    val ch = writeCharacteristic
                    if (gatt != null && ch != null) mainHandler.postDelayed({ sendNextConfigRead(gatt, ch) }, 200)
                    return
                }

                if (func != 0x03) {
                    configRaw["skip_unknown_response_func"] = "0x%02X".format(func)
                    configModbusBuffer.removeAt(0)
                    continue
                }

                val req = activeConfigRead ?: return
                val byteCount = configModbusBuffer[2].toInt() and 0xFF
                val expectedLength = 3 + byteCount + 2
                if (configModbusBuffer.size < expectedLength) return

                val frame = ByteArray(expectedLength)
                for (i in 0 until expectedLength) frame[i] = configModbusBuffer.removeAt(0)

                if (!isValidModbusCrc(frame)) {
                    configRaw["bad_crc_${req.name}"] = bytesToHex(frame)
                    activeConfigRead = null
                    refreshManageIfVisible()
                    val gatt = bluetoothGatt
                    val ch = writeCharacteristic
                    if (gatt != null && ch != null) mainHandler.postDelayed({ sendNextConfigRead(gatt, ch) }, 200)
                    return
                }

                parseConfigModbusResponse(req, frame)
                activeConfigRead = null
                refreshManageIfVisible()

                val gatt = bluetoothGatt
                val ch = writeCharacteristic
                if (gatt != null && ch != null) {
                    mainHandler.postDelayed({ sendNextConfigRead(gatt, ch) }, 250)
                }
                return
            }
        }
    }

    private fun parseConfigModbusResponse(req: ConfigReadRequest, frame: ByteArray) {
        configRaw["response_${req.name}"] = bytesToHex(frame)
        val byteCount = frame[2].toInt() and 0xFF
        val regCount = byteCount / 2
        for (i in 0 until regCount) {
            val hi = frame[3 + i * 2].toInt() and 0xFF
            val lo = frame[4 + i * 2].toInt() and 0xFF
            val value = (hi shl 8) or lo
            configRegisters[req.start + i] = value
        }
    }

    private fun buildModbusReadRequest(slave: Int, start: Int, count: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = (slave and 0xFF).toByte()
        frame[1] = 0x03
        frame[2] = ((start shr 8) and 0xFF).toByte()
        frame[3] = (start and 0xFF).toByte()
        frame[4] = ((count shr 8) and 0xFF).toByte()
        frame[5] = (count and 0xFF).toByte()
        val crc = modbusCrc16(frame, 6)
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    private fun modbusCrc16(bytes: ByteArray, length: Int = bytes.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (bytes[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    private fun isValidModbusCrc(frame: ByteArray): Boolean {
        if (frame.size < 5) return false
        val calc = modbusCrc16(frame, frame.size - 2)
        val gotLowHigh = (frame[frame.size - 2].toInt() and 0xFF) or
                ((frame[frame.size - 1].toInt() and 0xFF) shl 8)
        val gotHighLow = ((frame[frame.size - 2].toInt() and 0xFF) shl 8) or
                (frame[frame.size - 1].toInt() and 0xFF)
        return calc == gotLowHigh || calc == gotHighLow
    }

    private fun uploadConfigSnapshot(force: Boolean) {
        if (configUploading) return
        if (configRegisters.isEmpty() && !force) return

        configUploading = true
        val payload = buildConfigUploadJson()

        thread {
            try {
                val conn = (URL(adminServerUrl(LOCAL_CONFIG_UPLOAD_PATH)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) {
                    lastConfigUploadAt = System.currentTimeMillis()
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Тихая фоновая отправка, без вывода в интерфейс.
            } finally {
                configUploading = false
            }
        }
    }

    private fun reg(addr: Int): Int? = configRegisters[addr]

    private fun putRegister(obj: JSONObject, name: String, addr: Int, scale: Double? = null, unit: String? = null) {
        val raw = reg(addr)
        val item = JSONObject()
        item.put("register", "0x%04X".format(addr))
        if (raw == null) {
            item.put("raw", JSONObject.NULL)
            item.put("value", JSONObject.NULL)
        } else {
            item.put("raw", raw)
            if (scale != null) item.put("value", raw / scale) else item.put("value", raw)
        }
        if (unit != null) item.put("unit", unit)
        obj.put(name, item)
    }

    private fun configRegScaled(addr: Int, scale: Double? = null, offset: Double = 0.0): Double? {
        val raw = configRegisters[addr] ?: return null
        val base = if (scale != null) raw / scale else raw.toDouble()
        return base - offset
    }

    private fun putDisplayValue(obj: JSONObject, key: String, text: String) {
        obj.put(key, if (text == readOnlyUnavailable()) JSONObject.NULL else text)
    }

    private fun ensureConfigForUpload() {
        if (configRegisters.isEmpty() && !configReadInProgress) {
            loadCachedConfigForCurrentBms()
        }
        rememberCurrentBmsState()
    }

    private fun templateCheckUploadJson(): JSONObject {
        val result = currentTemplateCheck()?.takeIf {
            it.status == "ok" || it.status == "mismatch" || it.status == "incomplete"
        } ?: TemplateCheckResult(
            templateId = "liferych-lfp-default",
            templateVersion = 1,
            status = "incomplete",
            checkedAt = System.currentTimeMillis(),
            seriesCount = data.cellCount,
            missing = listOf(
                TemplateCheckItem(
                    key = "template_check_unavailable",
                    label = "Проверка конфигурации",
                    expected = null,
                    actual = null,
                    unit = "",
                    tolerance = 0.0,
                    reason = "check_not_completed"
                )
            ),
            hardwareFamily = currentHardwareFamily()
        )

        fun mismatchJson(item: TemplateCheckItem) = JSONObject().apply {
            put("key", item.key)
            put("label", item.label)
            put("expected", item.expected ?: JSONObject.NULL)
            put("actual", item.actual ?: JSONObject.NULL)
            put("unit", item.unit)
            put("tolerance", item.tolerance)
        }
        fun unavailableJson(item: TemplateCheckItem) = JSONObject().apply {
            put("key", item.key)
            put("label", item.label)
            put("expected", item.expected ?: JSONObject.NULL)
            put("actual", JSONObject.NULL)
            put("unit", item.unit)
            put("reason", item.reason ?: "register_missing")
        }
        fun unverifiedJson(item: TemplateCheckItem) = JSONObject().apply {
            put("key", item.key)
            put("label", item.label)
            put("expected", item.expected ?: JSONObject.NULL)
            put("actual", item.actual ?: JSONObject.NULL)
            put("unit", item.unit)
            put("reason", item.reason ?: "mapping_unverified")
        }

        return JSONObject().apply {
            put("template_id", result.templateId)
            put("template_version", result.templateVersion)
            put("status", result.status)
            put("checked_at", result.checkedAt)
            put("series_count", result.seriesCount ?: JSONObject.NULL)
            put("mismatch_count", result.mismatches.size)
            put("missing_count", result.missing.size)
            put("mismatches", JSONArray().apply { result.mismatches.forEach { put(mismatchJson(it)) } })
            put("missing", JSONArray().apply { result.missing.forEach { put(unavailableJson(it)) } })
            put("unverified", JSONArray().apply { result.unverified.forEach { put(unverifiedJson(it)) } })
            put("hardware_family", result.hardwareFamily)
            put("bms_version", bmsHardwareVersion())
        }
    }

    private fun buildConfigUploadJson(): JSONObject {
        ensureConfigForUpload()

        val obj = JSONObject()
        obj.put("api_key", SERVER_API_KEY)
        obj.put("bms_uid", bmsUid())
        obj.put("bluetooth_name", dalyBluetoothDeviceId())
        obj.put("bluetooth_address", selectedAddress ?: "")
        putHardwareIdentity(obj)
        obj.put("app_version", APP_VERSION)
        obj.put("read_status", lastConfigStatus)
        obj.put("template_check", templateCheckUploadJson())

        val config = JSONObject()

        val general = JSONObject()
        general.put("Тип батареи", batteryTypeText())
        general.put("Номинальная емкость", nominalCapacityText())
        general.put("Серийный номер", displayFactorySn().ifBlank { "не прочитан" })
        general.put("Версия BMS", displayBmsVersion().ifBlank { "не прочитана" })
        if (isServiceApp()) {
            general.put("Bluetooth ID", bluetoothId().ifBlank { "не указан" })
        }
        general.put("Источник емкости", nominalCapacitySource())
        general.put("Время ожидания сна", regText(0x0115, 0.1, "S"))
        general.put("Настройка SOC", currentSocTextForConfig())
        general.put("Калибр. SOC 0", regText(0x01C7, 1000.0, "V"))
        general.put("Калибр. SOC 100", regText(0x0229, 1000.0, "V"))
        general.put("Переключатель зарядки", currentChargeMosTextForConfig())
        general.put("Переключатель разрядки", currentDischargeMosTextForConfig())
        general.put("Отправить данные в облако", "OFF")

        general.put("battery_type", batteryTypeText())
        general.put("nominal_capacity_ah", nominalCapacityAh() ?: JSONObject.NULL)
        general.put("nominal_capacity_text", nominalCapacityText())
        general.put("capacity_source", nominalCapacitySource())
        general.put("daly_raw_capacity_registers", rawCapacityRegistersJson())
        general.put("sleep_time_s", regText(0x0115, 0.1, "S"))
        general.put("sleep_time_s_num", configRegScaled(0x0115, 0.1) ?: JSONObject.NULL)
        general.put("soc_percent", currentSocTextForConfig())
        general.put("soc_percent_num", data.soc ?: lastKnownSoc ?: JSONObject.NULL)
        general.put("soc_calibration_0_v", regText(0x01C7, 1000.0, "V"))
        general.put("soc_calibration_100_v", regText(0x0229, 1000.0, "V"))
        general.put("charge_mos_state_from_0x93", currentChargeMosTextForConfig())
        general.put("discharge_mos_state_from_0x93", currentDischargeMosTextForConfig())
        general.put("charge_mos_state_num", mosStateNumForSite(currentChargeMosTextForConfig()))
        general.put("discharge_mos_state_num", mosStateNumForSite(currentDischargeMosTextForConfig()))
        config.put("general", general)

        val voltage = JSONObject()
        voltage.put("Защита от повышенного напряжения элемента", settingV(0x0131))
        voltage.put("Защита от пониженного напряжения элемента", settingV(0x0135))
        voltage.put("Защита от повышенного общего напряжения", settingV(0x0139, 10.0))
        voltage.put("Защита от пониженного общего напряжения", settingV(0x013D, 10.0))
        voltage.put("Защита от перепада напряжения", settingV(0x015B))
        voltage.put("Защита от перетока зарядки", settingA(0x0163))
        voltage.put("Защита от перетока разрядки", settingA(0x0164))
        voltage.put("Ток короткого замыкания", settingA(0x016E))

        voltage.put("cell_over_voltage_v", settingV(0x0131))
        voltage.put("cell_under_voltage_v", settingV(0x0135))
        voltage.put("pack_over_voltage_v", settingV(0x0139, 10.0))
        voltage.put("pack_under_voltage_v", settingV(0x013D, 10.0))
        voltage.put("voltage_diff_protection_v", settingV(0x015B))
        voltage.put("charge_overcurrent_a", settingA(0x0163))
        voltage.put("discharge_overcurrent_a", settingA(0x0164))
        voltage.put("short_circuit_current_a", settingA(0x016E))

        voltage.put("cell_over_voltage_num", configRegScaled(0x0131, 1000.0) ?: JSONObject.NULL)
        voltage.put("cell_under_voltage_num", configRegScaled(0x0135, 1000.0) ?: JSONObject.NULL)
        voltage.put("pack_over_voltage_num", configRegScaled(0x0139, 10.0) ?: JSONObject.NULL)
        voltage.put("pack_under_voltage_num", configRegScaled(0x013D, 10.0) ?: JSONObject.NULL)
        voltage.put("voltage_diff_protection_num", configRegScaled(0x015B, 1000.0) ?: JSONObject.NULL)
        voltage.put("charge_overcurrent_num", configRegScaled(0x0163, 10.0) ?: JSONObject.NULL)
        voltage.put("discharge_overcurrent_num", configRegScaled(0x0164, 10.0) ?: JSONObject.NULL)
        voltage.put("short_circuit_current_num", configRegScaled(0x016E, 10.0) ?: JSONObject.NULL)
        config.put("voltage_current_protection", voltage)

        val temp = JSONObject()
        temp.put("Защита от высокой температуры зарядки", settingTemp(0x014B))
        temp.put("Защита от низкой температуры зарядки", settingTemp(0x014F))
        temp.put("Защита от высокой температуры разрядки", settingTemp(0x0153))
        temp.put("Защита от низкой температуры разрядки", settingTemp(0x0157))
        temp.put("Защита от перепада температур", settingTempNoOffset(0x015F))
        temp.put("Температура включения вентилятора", settingTemp(0x01F5))
        temp.put("Температура включения нагрева", settingTemp(0x01F7))
        temp.put("Температура отключения нагрева", settingTemp(0x01F8))

        temp.put("charge_high_temp_c", settingTemp(0x014B))
        temp.put("charge_low_temp_c", settingTemp(0x014F))
        temp.put("discharge_high_temp_c", settingTemp(0x0153))
        temp.put("discharge_low_temp_c", settingTemp(0x0157))
        temp.put("temperature_diff_c", settingTempNoOffset(0x015F))
        temp.put("fan_on_temp_c", settingTemp(0x01F5))
        temp.put("heat_on_temp_c", settingTemp(0x01F7))
        temp.put("heat_off_temp_c", settingTemp(0x01F8))

        temp.put("charge_high_temp_num", configRegScaled(0x014B, null, 40.0) ?: JSONObject.NULL)
        temp.put("charge_low_temp_num", configRegScaled(0x014F, null, 40.0) ?: JSONObject.NULL)
        temp.put("discharge_high_temp_num", configRegScaled(0x0153, null, 40.0) ?: JSONObject.NULL)
        temp.put("discharge_low_temp_num", configRegScaled(0x0157, null, 40.0) ?: JSONObject.NULL)
        temp.put("temperature_diff_num", configRegScaled(0x015F) ?: JSONObject.NULL)
        temp.put("fan_on_temp_num", configRegScaled(0x01F5, null, 40.0) ?: JSONObject.NULL)
        temp.put("heat_on_temp_num", configRegScaled(0x01F7, null, 40.0) ?: JSONObject.NULL)
        temp.put("heat_off_temp_num", configRegScaled(0x01F8, null, 40.0) ?: JSONObject.NULL)
        config.put("temperature_protection", temp)

        val balancing = JSONObject()

        // Живые уставки балансировки: старт 0x011A (мВ), разность 0x011B (мВ).
        // 0x0227 на этой BMS фиксированно 2.5 В, 0x01FA пустой, 0x0156 — чужой регистр (15).
        balancing.put("Напряжение включения балансировки", regVoltageOneDecimal(0x011A))
        balancing.put("Напряжение отключения балансировки", regText(0x01FB, 1000.0, "V"))
        balancing.put("Перепад напряжения при открытии балансировки", regText(0x011B, null, "mV"))
        balancing.put("Ток включения балансировки", regCurrentOneDecimal(0x0151))
        balancing.put("Переключатель активной балансировки", regSwitchText(0x0220))

        balancing.put("balance_start_voltage_v", regVoltageOneDecimal(0x011A))
        balancing.put("balance_stop_voltage_v", regText(0x01FB, 1000.0, "V"))
        balancing.put("balance_delta_v", regText(0x011B, null, "mV"))
        balancing.put("balance_current_a", regCurrentOneDecimal(0x0151))
        balancing.put("active_balance_enabled", regSwitchText(0x0220))

        balancing.put("balance_start_voltage_num", configRegScaled(0x011A, 1000.0) ?: JSONObject.NULL)
        balancing.put("balance_stop_voltage_num", configRegScaled(0x01FB, 1000.0) ?: JSONObject.NULL)
        balancing.put("balance_delta_num", configRegScaled(0x011B) ?: JSONObject.NULL)
        balancing.put("balance_current_num", configRegScaled(0x0151, 1000.0) ?: JSONObject.NULL)
        balancing.put("active_balance_enabled_num", configRegisters[0x0220] ?: JSONObject.NULL)
        config.put("balancing", balancing)

        val cellParams = JSONObject()
        cellParams.put("Тип батареи", batteryTypeText())
        cellParams.put("Номинальная емкость", nominalCapacityText())
        cellParams.put("Источник емкости", nominalCapacitySource())
        cellParams.put("Время ожидания сна", regText(0x0115, 0.1, "S"))
        cellParams.put("Настройка SOC", currentSocTextForConfig())
        cellParams.put("Калибр. SOC 0", regText(0x01C7, 1000.0, "V"))
        cellParams.put("Калибр. SOC 100", regText(0x0229, 1000.0, "V"))
        cellParams.put("Адрес подчиненной платы", regText(0x020F, null, ""))
        cellParams.put("Тип инвертора", regText(0x024B, null, ""))
        cellParams.put("Способ связи", regText(0x024C, null, ""))
        cellParams.put("Протокол одной шины", regText(0x022A, null, ""))

        cellParams.put("battery_type", batteryTypeText())
        cellParams.put("nominal_capacity", nominalCapacityText())
        cellParams.put("nominal_capacity_ah", nominalCapacityAh() ?: JSONObject.NULL)
        cellParams.put("capacity_source", nominalCapacitySource())
        cellParams.put("daly_raw_capacity_registers", rawCapacityRegistersJson())
        cellParams.put("sleep_time_s", regText(0x0115, 0.1, "S"))
        cellParams.put("soc_percent", currentSocTextForConfig())
        cellParams.put("soc_calibration_0_v", regText(0x01C7, 1000.0, "V"))
        cellParams.put("soc_calibration_100_v", regText(0x0229, 1000.0, "V"))
        cellParams.put("slave_board_address", regText(0x020F, null, ""))
        cellParams.put("inverter_type", regText(0x024B, null, ""))
        cellParams.put("communication_method", regText(0x024C, null, ""))
        cellParams.put("single_bus_protocol", regText(0x022A, null, ""))

        cellParams.put("cell_count_current", data.cellCount ?: JSONObject.NULL)
        cellParams.put("temp_count_current", data.tempCount ?: JSONObject.NULL)
        cellParams.put("remaining_ah_current", data.remainingAh ?: JSONObject.NULL)
        cellParams.put("estimated_full_ah_current", data.estimatedFullAh ?: JSONObject.NULL)
        config.put("cell_parameters", cellParams)

        val registersJson = JSONObject()
        for ((addr, value) in configRegisters) {
            registersJson.put("0x%04X".format(addr), value)
        }
        config.put("original_daly_modbus_registers", registersJson)

        val note = JSONObject()
        note.put("source", "v47: audited runtime/config payload; capacity from runtime, raw Daly capacity regs diagnostic only")
        note.put("write_commands_enabled", false)
        note.put("register_count", configRegisters.size)
        config.put("read_note", note)

        obj.put("config", config)

        val raw = JSONObject()
        for ((k, v) in data.raw) raw.put(k, v)
        val configRawJson = JSONObject()
        for ((k, v) in configRaw) configRawJson.put(k, v)
        raw.put("original_daly_config_modbus", configRawJson)
        obj.put("raw", raw)

        return obj
    }

    private fun buildUploadJson(): JSONObject {
        val obj = JSONObject()
        obj.put("api_key", SERVER_API_KEY)
        obj.put("bms_uid", bmsUid())
        obj.put("bluetooth_name", dalyBluetoothDeviceId())
        obj.put("bluetooth_address", selectedAddress ?: "")
        putHardwareIdentity(obj)
        putNullable(obj, "voltage", data.voltage)
        putNullable(obj, "current", data.current)
        putNullable(obj, "soc", data.soc)
        putNullable(obj, "remaining_ah", data.remainingAh)
        putNullable(obj, "estimated_full_ah", data.estimatedFullAh)
        putNullable(obj, "nominal_capacity_ah", nominalCapacityAh())
        data.cellCount?.let { obj.put("cell_count", it) }
        obj.put("capacity_source", nominalCapacitySource())
        putNullable(obj, "cell_diff_v", data.cellDiffV)
        putNullable(obj, "min_cell_v", data.minCellV)
        putNullable(obj, "max_cell_v", data.maxCellV)
        putNullable(obj, "min_temp", data.minTemp)
        putNullable(obj, "max_temp", data.maxTemp)
        putNullable(obj, "charge_mos", data.chargeMos)
        putNullable(obj, "discharge_mos", data.dischargeMos)

        val cells = JSONObject()
        for ((k, v) in data.cells) cells.put(k.toString(), v)
        obj.put("cells", cells)

        val temps = JSONObject()
        for ((k, v) in data.temps) temps.put(k.toString(), v)
        obj.put("temps", temps)

        val errors = JSONArray()
        for (e in data.errors) errors.put(e)
        obj.put("errors", errors)

        val raw = JSONObject()
        for ((k, v) in data.raw) raw.put(k, v)
        if (historicalRawResponses.isNotEmpty()) {
            val hist = JSONArray()
            for (line in historicalRawResponses.takeLast(80)) hist.put(line)
            raw.put("historical_raw", hist)
        }
        obj.put("raw", raw)

        val events = JSONArray()
        for (e in localEvents.takeLast(20)) {
            val ev = JSONObject()
            ev.put("type", "bms_event")
            ev.put("severity", if (e.contains("Ошибки") || e.contains("OFF") || e.contains("разбег")) "warning" else "info")
            ev.put("text", e.substringAfter(" — ", e))
            ev.put("local_time", e.substringBefore(" — ", ""))
            events.put(ev)
        }
        for (e in dalyHistoricalEvents.takeLast(80)) {
            val ev = JSONObject()
            ev.put("type", "daly_historical")
            ev.put("severity", "info")
            ev.put("text", e)
            ev.put("local_time", "")
            events.put(ev)
        }
        obj.put("events", events)

        return obj
    }

    private fun putNullable(obj: JSONObject, key: String, value: Any?) {
        if (value == null) obj.put(key, JSONObject.NULL) else obj.put(key, value)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { b ->
            (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        }
    }

    private fun buildRequest(cmd: Int): ByteArray {
        return buildDalyA5Frame(cmd, ByteArray(8))
    }

    private fun buildDalyA5Frame(cmd: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(13)
        frame[0] = FRAME_START
        frame[1] = REQUEST_ADDRESS
        frame[2] = cmd.toByte()
        frame[3] = DATA_LEN
        for (i in 0 until 8) {
            frame[4 + i] = if (i < payload.size) payload[i] else 0
        }
        frame[12] = checksum(frame, 12)
        return frame
    }

    private fun lastDaly94Payload(): ByteArray? {
        val hex = data.raw["0x94"] ?: return null
        val parts = hex.trim().split(Regex("\\s+"))
        if (parts.size < 13) return null
        val bytes = ByteArray(13)
        for (i in 0 until 13) {
            val value = parts[i].toIntOrNull(16) ?: return null
            bytes[i] = value.toByte()
        }
        if ((bytes[2].toInt() and 0xFF) != 0x94) return null
        return bytes.copyOfRange(4, 12)
    }

    private fun buildDalyCellCountWriteFrames(series: Int): List<ByteArray> {
        val count = series.coerceIn(1, 24)
        val prev = lastDaly94Payload()
        val temps = prev?.get(1)?.toInt()?.and(0xFF)?.takeIf { it in 1..16 }
            ?: data.tempCount?.takeIf { it in 1..16 }
            ?: 2
        val setBoard = ByteArray(8)
        setBoard[0] = 1
        setBoard[1] = count.toByte()
        setBoard[4] = temps.toByte()
        val set94 = prev?.copyOf() ?: ByteArray(8)
        set94[0] = count.toByte()
        set94[1] = temps.toByte()
        return listOf(
            buildDalyA5Frame(0x11, setBoard),
            buildDalyA5Frame(0x94, set94)
        )
    }

    private fun checksum(bytes: ByteArray, length: Int): Byte {
        var sum = 0
        for (i in 0 until length) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    private fun isValidFrame(frame: ByteArray): Boolean {
        return frame.size == 13 && frame[0] == FRAME_START && checksum(frame, 12) == frame[12]
    }

    private fun u16(bytes: ByteArray, off: Int): Int {
        return ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, off: Int): Long {
        return ((bytes[off].toLong() and 0xFF) shl 24) or
            ((bytes[off + 1].toLong() and 0xFF) shl 16) or
            ((bytes[off + 2].toLong() and 0xFF) shl 8) or
            (bytes[off + 3].toLong() and 0xFF)
    }

    private fun hex(bytes: ByteArray): String {
        return bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val need = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                showQrScannerScreen()
            } else {
                toast("Для сканирования нужен доступ к камере")
                showQrInputScreen()
            }
            return
        }
        if (requestCode != 1001 || screenState != "loading") return
        if (hasBlePermissions()) {
            startScan()
        } else {
            showSplashScreen()
            toast("Для поиска батареи нужен доступ к Bluetooth")
        }
    }

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun round(color: Int, radius: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), strokeColor)
        }
    }

    private fun marginLp(w: Int, h: Int, l: Int, t: Int, r: Int, b: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(w, h).apply {
            setMargins(dp(l), dp(t), dp(r), dp(b))
        }
    }

    private fun equalizeRowChildHeights(row: LinearLayout) {
        row.post {
            var maxH = 0
            for (i in 0 until row.childCount) {
                maxH = max(maxH, row.getChildAt(i).measuredHeight)
            }
            if (maxH <= 0) return@post
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                val lp = child.layoutParams
                if (lp.height != maxH) {
                    lp.height = maxH
                    child.layoutParams = lp
                }
            }
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Не найдено приложение для открытия ссылки")
        }
    }

    private fun dialPhone(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } catch (_: Exception) {
            toast("Не удалось открыть приложение телефона")
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun interFont(weight: Int): Typeface {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(interBase, weight, false)
        } else {
            Typeface.create(
                interBase,
                if (weight >= 700) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun hPad(): Int {
        return if (resources.displayMetrics.widthPixels < dp(360)) dp(10) else dp(16)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screenState) {
            "dashboard", "journal", "support", "profile", "manage", "service", "qtc", "debug" -> {
                disconnectGatt()
                showBatteriesScreen()
            }
            "search", "loading" -> {
                disconnectGatt()
                showBatteriesScreen()
            }
            "batteries" -> {
                disconnectGatt()
                showSplashScreen()
            }
            "qr_scan" -> {
                stopQrCamera()
                showQrInputScreen()
            }
            "qr_result" -> showQrInputScreen()
            "qr_input" -> showSplashScreen()
            "auth" -> showBatteriesScreen()
            else -> {
                disconnectGatt()
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        stopQrCamera()
        barcodeScanner.close()
        cameraExecutor.shutdown()
        disconnectGatt()
        super.onDestroy()
    }
}

class SocGaugeView(context: Context) : View(context) {
    private var soc: Double? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 225, 225)
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 176, 69)
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 35, 35)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(90, 90, 90)
        textAlign = Paint.Align.CENTER
    }

    fun setSoc(value: Double?) {
        soc = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 20f
        val rect = RectF(pad, pad, w - pad, h + 50f)
        val start = 200f
        val total = 140f

        canvas.drawArc(rect, start, total, false, bgPaint)

        val s = max(0.0, min(100.0, soc ?: 0.0))
        valuePaint.color = when {
            s >= 80 -> Color.rgb(45, 176, 69)
            s >= 40 -> Color.rgb(215, 160, 35)
            s >= 20 -> Color.rgb(230, 125, 35)
            else -> Color.rgb(210, 70, 70)
        }
        canvas.drawArc(rect, start, (total * (s / 100.0)).toFloat(), false, valuePaint)

        smallPaint.textSize = 28f
        canvas.drawText("SOC", w / 2f, h / 2f - 8f, smallPaint)
        textPaint.textSize = 42f
        canvas.drawText(if (soc == null) "--%" else "%.1f%%".format(s), w / 2f, h / 2f + 40f, textPaint)
        smallPaint.textSize = 18f
        canvas.drawText("0", pad + 6f, h - 4f, smallPaint)
        canvas.drawText("100", w - pad - 8f, h - 4f, smallPaint)
    }
}
