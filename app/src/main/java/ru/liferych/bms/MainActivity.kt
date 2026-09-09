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
import android.util.TypedValue
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

private const val DEFAULT_ADMIN_SERVER_BASE_URL = BmsApiConfig.BASE_URL
private const val UPLOAD_PATH = "/api/upload.php"
private const val CONFIG_UPLOAD_PATH = "/api/config_upload.php"
private const val SERVICE_REPORT_PATH = "/api/v1/service-report"
private const val WARRANTY_SUBMIT_PATH = "/api/warranty_submit.php"
private const val WARRANTY_LIST_PATH = "/api/warranty_list.php"
private val APP_VERSION = BuildConfig.VERSION_NAME
private const val REMOTE_WRITE_POLL_MS = 8000L
private const val UPLOAD_INTERVAL_MS = 15000L
private const val CONFIG_UPLOAD_INTERVAL_MS = 300000L
private const val CONFIG_PREFS_NAME = "bms_config_cache"
private const val CONFIG_AUTO_READ_DELAY_MS = 12000L
private const val TEST_AUTO_WRITE_SOC_ON_CONNECT = false
private const val TEST_SOC_PERCENT_ON_CONNECT = 90.0
private const val TEST_SOC_REGISTER_ADDR = 0x0116
private const val SERVICE_PACK_SOC_PERCENT = 100.0
private const val SERVICE_SOC_TOLERANCE_PERCENT = 1.0
private const val SERVICE_CAPACITY_TOLERANCE_AH = 0.6
private const val HARDWARE_FAMILY_STANDARD = "standard"
private const val HARDWARE_FAMILY_DL_RED = "dl_red"
private const val HARDWARE_FAMILY_R10K = "r10k"
private val BMS_VERSION_TOKENS = listOf("R24TK", "R24TH", "R10K")
private const val DALY_BATTERY_CODE_CMD = 0x57
private const val DALY_BATTERY_CODE_FRAMES = 5
private const val DALY_HW_VERSION_CMD = 0x63
private const val DALY_HW_VERSION_FRAMES = 5
private const val DALY_SN_CODE_START = 0x0057
private const val DALY_SN_CODE_END = 0x005D
private const val DALY_SN_CODE_COUNT = DALY_SN_CODE_END - DALY_SN_CODE_START + 1
private const val DALY_NOMINAL_CAPACITY_HI_REG = 0x0109
private const val DALY_NOMINAL_CAPACITY_LO_REG = 0x010A
private const val DALY_REMAINING_CAPACITY_HI_REG = 0x010B
private const val DALY_REMAINING_CAPACITY_LO_REG = 0x010C
private const val DALY_PASSWORD_REG_0 = 0x0126
private const val DALY_SOC_CALIBRATION_0_REG = 0x0227
private const val DALY_SOC_CALIBRATION_100_REG = 0x0229
private const val DALY_CELL_OV_ALARM_REG = 0x0130
private const val DALY_CELL_OV_PROTECT_REG = 0x0131
private const val DALY_CELL_OV_RECOVERY_REG = 0x0132
private const val SERVICE_SETTINGS_PASSWORD = "113355"
private const val SERVICE_WRITE_MAX_RETRY_ROUNDS = 1
private const val SERVICE_WRITE_VERIFY_MAX_ATTEMPTS = 2
private const val SERVICE_WRITE_VERIFY_RETRY_DELAY_MS = 700L
private val SERVICE_TEMPLATE_WRITE_ORDER = listOf(
    "sleep_timeout",
    "cell_over_voltage",
    "cell_under_voltage",
    "pack_over_voltage",
    "pack_under_voltage",
    "charge_high_temp",
    "charge_low_temp",
    "discharge_high_temp",
    "discharge_low_temp",
    "balance_start_voltage",
    "balance_stop_voltage",
    "balance_delta",
    "soc_calibration_0",
    "soc_calibration_100"
)
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
    val writeFrames: List<ByteArray>? = null,
    var verifyAttempt: Int = 0
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
    val parameters: List<BmsTemplateParameter>,
    val updatedAt: Long = 0L
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
    val skipFor: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val writable: Boolean = false
)

data class TemplateCheckItem(
    val key: String,
    val label: String,
    val expected: Double?,
    val actual: Double?,
    val unit: String,
    val tolerance: Double,
    val reason: String? = null,
    val status: String = "mismatch" // ok | mismatch | missing | skipped | disabled
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
    val hardwareFamily: String = HARDWARE_FAMILY_STANDARD,
    val items: List<TemplateCheckItem> = emptyList(),
    val templateUpdatedAt: Long = 0L
)

private enum class QtcDbStatus {
    IDLE,
    WAITING_BMS,
    CHECKING,
    IN_DATABASE,
    SENDING,
    VERIFYING,
    ADDED,
    ERROR
}

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

/** Простые outline-иконки для экрана «Главное» — одинаковый визуальный размер. */
private enum class DashIconKind {
    LIST, TAG, BARCODE, CHIP, BOLT, CURRENT, THERMO, CELLS, POWER, SHIELD, PULSE, CHART, CHECK
}

private class DashIconView(
    context: Context,
    private val kind: DashIconKind,
    private val iconColor: Int
) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = iconColor
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width, height) / 24f
        val offsetX = (width - 24f * scale) / 2f
        val offsetY = (height - 24f * scale) / 2f
        stroke.strokeWidth = 1.85f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        when (kind) {
            DashIconKind.LIST -> {
                canvas.drawLine(5f, 7f, 19f, 7f, stroke)
                canvas.drawLine(5f, 12f, 19f, 12f, stroke)
                canvas.drawLine(5f, 17f, 19f, 17f, stroke)
                canvas.drawCircle(5f, 7f, 1.2f, fill)
                canvas.drawCircle(5f, 12f, 1.2f, fill)
                canvas.drawCircle(5f, 17f, 1.2f, fill)
            }
            DashIconKind.TAG -> {
                canvas.drawRoundRect(4f, 8f, 16f, 16f, 2f, 2f, stroke)
                canvas.drawLine(16f, 10f, 20f, 12f, stroke)
                canvas.drawLine(20f, 12f, 16f, 14f, stroke)
                canvas.drawCircle(8.5f, 12f, 1.2f, fill)
            }
            DashIconKind.BARCODE -> {
                for (x in listOf(5f, 7.5f, 10f, 12f, 14.5f, 17f, 19f)) {
                    val thick = if (x % 5f == 0f) 1.6f else 1.1f
                    stroke.strokeWidth = thick
                    canvas.drawLine(x, 6f, x, 18f, stroke)
                }
                stroke.strokeWidth = 1.85f
            }
            DashIconKind.CHIP -> {
                canvas.drawRoundRect(7f, 7f, 17f, 17f, 2f, 2f, stroke)
                canvas.drawRoundRect(9.5f, 9.5f, 14.5f, 14.5f, 1f, 1f, stroke)
                canvas.drawLine(9f, 4.5f, 9f, 7f, stroke)
                canvas.drawLine(12f, 4.5f, 12f, 7f, stroke)
                canvas.drawLine(15f, 4.5f, 15f, 7f, stroke)
                canvas.drawLine(9f, 17f, 9f, 19.5f, stroke)
                canvas.drawLine(12f, 17f, 12f, 19.5f, stroke)
                canvas.drawLine(15f, 17f, 15f, 19.5f, stroke)
                canvas.drawLine(4.5f, 9f, 7f, 9f, stroke)
                canvas.drawLine(4.5f, 12f, 7f, 12f, stroke)
                canvas.drawLine(4.5f, 15f, 7f, 15f, stroke)
                canvas.drawLine(17f, 9f, 19.5f, 9f, stroke)
                canvas.drawLine(17f, 12f, 19.5f, 12f, stroke)
                canvas.drawLine(17f, 15f, 19.5f, 15f, stroke)
            }
            DashIconKind.BOLT -> {
                canvas.drawPath(Path().apply {
                    moveTo(13f, 3f)
                    lineTo(8f, 13f)
                    lineTo(12f, 13f)
                    lineTo(11f, 21f)
                    lineTo(16f, 11f)
                    lineTo(12f, 11f)
                    close()
                }, stroke)
            }
            DashIconKind.CURRENT -> {
                canvas.drawLine(7f, 8f, 17f, 8f, stroke)
                canvas.drawLine(14f, 5.5f, 17f, 8f, stroke)
                canvas.drawLine(14f, 10.5f, 17f, 8f, stroke)
                canvas.drawLine(17f, 16f, 7f, 16f, stroke)
                canvas.drawLine(10f, 13.5f, 7f, 16f, stroke)
                canvas.drawLine(10f, 18.5f, 7f, 16f, stroke)
            }
            DashIconKind.THERMO -> {
                canvas.drawRoundRect(10.5f, 3.5f, 13.5f, 14f, 1.5f, 1.5f, stroke)
                canvas.drawCircle(12f, 17.5f, 3.4f, stroke)
                canvas.drawLine(12f, 7f, 12f, 14.5f, stroke)
            }
            DashIconKind.CELLS -> {
                canvas.drawRoundRect(4f, 7f, 10f, 17f, 1.5f, 1.5f, stroke)
                canvas.drawRoundRect(9.5f, 5.5f, 15.5f, 17f, 1.5f, 1.5f, stroke)
                canvas.drawRoundRect(15f, 7f, 21f, 17f, 1.5f, 1.5f, stroke)
            }
            DashIconKind.POWER -> {
                canvas.drawCircle(12f, 12.5f, 7.5f, stroke)
                canvas.drawLine(12f, 5f, 12f, 12f, stroke)
            }
            DashIconKind.SHIELD -> {
                canvas.drawPath(Path().apply {
                    moveTo(12f, 3.5f)
                    lineTo(19f, 6.5f)
                    lineTo(19f, 12.5f)
                    quadTo(19f, 18f, 12f, 21f)
                    quadTo(5f, 18f, 5f, 12.5f)
                    lineTo(5f, 6.5f)
                    close()
                }, stroke)
            }
            DashIconKind.PULSE -> {
                canvas.drawPath(Path().apply {
                    moveTo(3f, 12f)
                    lineTo(7f, 12f)
                    lineTo(9.5f, 6f)
                    lineTo(12.5f, 18f)
                    lineTo(15f, 12f)
                    lineTo(21f, 12f)
                }, stroke)
            }
            DashIconKind.CHART -> {
                canvas.drawLine(5f, 18f, 19f, 18f, stroke)
                canvas.drawLine(7f, 18f, 7f, 11f, stroke)
                canvas.drawLine(12f, 18f, 12f, 7f, stroke)
                canvas.drawLine(17f, 18f, 17f, 13f, stroke)
            }
            DashIconKind.CHECK -> {
                canvas.drawCircle(12f, 12f, 8.5f, stroke)
                canvas.drawPath(Path().apply {
                    moveTo(7.5f, 12.2f)
                    lineTo(10.5f, 15.2f)
                    lineTo(16.5f, 8.8f)
                }, stroke)
            }
        }
        canvas.restore()
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
    /** Только для сервисного приложения: список АКБ текущей сессии (не сохраняется). */
    private val sessionBatteries: MutableList<SavedBattery> = mutableListOf()
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
    /** История UI-экранов для стрелки «Назад» и системной кнопки Back (без Jetpack Navigation). */
    private val uiBackStack = ArrayDeque<String>()
    private var navigatingBack: Boolean = false
    private val templateChecksByBms: MutableMap<String, TemplateCheckResult> = mutableMapOf()
    private var latestTemplateCheck: TemplateCheckResult? = null
    /** Актуальный эталон с сервера. Без успешного fetch не используем локальный APK-template как истину. */
    private var activeServerTemplate: BmsConfigTemplate? = null
    private var serverTemplateFetchStatus: String = "idle" // idle | fetching | ok | error
    private var serverTemplateFetchError: String? = null
    private var serverTemplateFetchInFlight = false
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
    /** Клиентское «Настроить BMS»: только отклонения шаблона, без service extras. */
    private var clientTemplateApplyMode: Boolean = false
    private var serviceWriteTotal: Int = 0
    private var serviceWriteDone: Int = 0
    private var serviceWriteProgressBar: ProgressBar? = null
    private var serviceWriteProgressText: TextView? = null
    private var pendingServiceWriteFinalVerify: Boolean = false
    private var serviceWriteRetryRound: Int = 0
    private var serviceVerifyNeedsReadAfterCurrent: Boolean = false
    private var bluetoothIdValue: TextView? = null
    private var bmsSnValue: TextView? = null
    private var bmsVersionValue: TextView? = null
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
    private var socProgress: ProgressBar? = null
    private lateinit var voltageValue: TextView
    private lateinit var currentValue: TextView
    private var currentSubValue: TextView? = null
    private lateinit var remainingValue: TextView
    private var fullCapacityValue: TextView? = null
    private var socStatusText: TextView? = null
    private var cellCountValue: TextView? = null
    private var cellDiffHeaderValue: TextView? = null
    private lateinit var chargeMosValue: TextView
    private lateinit var dischargeMosValue: TextView
    private var chargeMosDot: View? = null
    private var dischargeMosDot: View? = null
    private lateinit var balanceValue: TextView
    private var balanceDot: View? = null
    private var stateValue: TextView? = null
    private var stateDot: View? = null
    private var overallStatusTitle: TextView? = null
    private var overallStatusSub: TextView? = null
    private var overallStatusBanner: LinearLayout? = null
    private var overallStatusIcon: TextView? = null
    private lateinit var heatValue: TextView
    private lateinit var batteryInfoText: TextView
    private lateinit var deviceNameValue: TextView
    private lateinit var cycleCountValue: TextView
    private lateinit var t1Text: TextView
    private lateinit var t2Text: TextView
    private lateinit var cellsLayout: LinearLayout
    private lateinit var manageContentLayout: LinearLayout
    private lateinit var qtcContentLayout: LinearLayout
    private var qtcDbStatus: QtcDbStatus = QtcDbStatus.IDLE
    private var qtcDbUid: String = ""
    private var qtcDbError: String = ""
    private var qtcDbJobToken: Int = 0

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
        if (isServiceApp()) {
            // Старый persistent-список сервисного приложения больше не используем.
            batteryPrefs.edit().clear().apply()
            sessionBatteries.clear()
        } else {
            removeTestBatteryIfPresent()
        }
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
            launchTop.addView(
                TextView(this).apply {
                    text = BuildConfig.VERSION_NAME
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(111, 119, 129))
                    textSize = 12f
                    typeface = interFont(600)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
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
        root.addView(header("Проверка QR-кода", showBack = true))

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
        root.addView(header("Сканер QR-кода", showBack = true))
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
        root.addView(header("Результат QR-кода", showBack = true))
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
        enterScreen("loading", track = false)
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

    private fun showBatteriesScreen(asRootHome: Boolean = false) {
        if (asRootHome) clearUiBackStack()
        enterScreen("batteries", track = !asRootHome)
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
                beginAddBatteryFlow()
            }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(74)))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = if (isServiceApp()) "Рабочие АКБ" else "Мои батареи"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(750)
        }, marginLp(-1, -2, 0, 4, 0, 14))

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
                text = if (isServiceApp()) "+ ДОБАВИТЬ АКБ" else "ДОБАВИТЬ БАТАРЕЮ"
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(780)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(dp(36), 1))
            setOnClickListener {
                beginAddBatteryFlow()
            }
        }
        content.addView(addButton, LinearLayout.LayoutParams(-1, dp(56)).apply {
            bottomMargin = dp(14)
        })

        content.addView(TextView(this).apply {
            text = if (isServiceApp()) {
                "Добавленные в текущей сессии АКБ"
            } else {
                "Добавленные устройства"
            }
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
                text = if (isServiceApp()) {
                    "В этой сессии АКБ ещё не добавлены"
                } else {
                    "Сохранённых батарей пока нет"
                }
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(111, 119, 129))
                background = round(Color.rgb(246, 247, 249), dp(16), Color.rgb(223, 229, 235), 1)
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }, marginLp(-1, -2, 0, 0, 0, 10))
        }

        content.addView(TextView(this).apply {
            text = if (isServiceApp()) {
                "Список только для текущей сессии. После закрытия приложения он очищается. Успешно настроенные АКБ убираются автоматически."
            } else {
                "Нажмите на батарею, чтобы открыть её параметры. Удерживайте карточку, чтобы задать имя. Если батарей ещё нет, нажмите кнопку выше."
            }
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.rgb(251, 251, 252), dp(14), Color.rgb(202, 211, 220), 1)
        }, marginLp(-1, -2, 0, 2, 0, 10))

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
                    showDashboardScreen(asRootHome = true)
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
                    showBatteriesScreen(asRootHome = true)
                }
            }
            .setNegativeButton("Отмена", null)
            .setNeutralButton("Удалить") { _, _ ->
                val wasSelected = selectedAddress == battery.address
                saveBatteries(loadSavedBatteries().filterNot {
                    it.address == battery.address
                })
                if (wasSelected) {
                    disconnectGatt()
                    selectedAddress = null
                    selectedDeviceName = ""
                    // Локальное удаление не трогает серверную историю.
                    // Очищаем только volatile-состояние текущей сессии, чтобы SN/конфиг
                    // не ушли на сервер под uid=unknown_bms.
                    configRegisters.clear()
                    configRaw.clear()
                    data.cells.clear()
                    data.voltage = null
                    data.soc = null
                    pendingFirstTelemetryUpload = false
                    resetRemoteWriteState()
                }
                showBatteriesScreen(asRootHome = true)
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
        clearUiBackStack()
        showLoadingScreen("Подключение к $displayName...")
        connectSelectedDevice()
    }

    private fun removeTestBatteryIfPresent() {
        val saved = loadSavedBatteries()
        val filtered = saved.filterNot { it.address == TEST_BATTERY_ADDRESS }
        if (filtered.size != saved.size) {
            saveBatteries(filtered)
        }
        // Больше не сидируем демо-АКБ.
        batteryPrefs.edit().putBoolean("test_battery_seeded", true).apply()
    }

    private fun userProfilePrefs(): SharedPreferences {
        return getSharedPreferences("user_profile", MODE_PRIVATE)
    }

    private fun profileFullName(): String {
        return userProfilePrefs().getString("name", "")?.trim().orEmpty()
    }

    private fun profilePhoneStored(): String {
        return userProfilePrefs().getString("phone", "")?.trim().orEmpty()
    }

    private fun profilePhoneDigits(phone: String = profilePhoneStored()): String {
        var digits = phone.filter { it.isDigit() }
        if (digits.startsWith("8") && digits.length == 11) {
            digits = "7" + digits.drop(1)
        } else if (digits.length == 10 && !digits.startsWith("7")) {
            digits = "7$digits"
        }
        return digits
    }

    private fun isValidRuPhone(phone: String): Boolean {
        val digits = profilePhoneDigits(phone)
        return digits.length == 11 && digits.startsWith("7")
    }

    /** Единая проверка заполненности профиля пользователя (ФИО + телефон). */
    private fun isProfileComplete(): Boolean {
        if (isServiceApp()) return true
        return profileFullName().isNotBlank() && isValidRuPhone(profilePhoneStored())
    }

    private fun formatRuPhoneMask(input: String): String {
        var digits = input.filter { it.isDigit() }
        when {
            digits.isEmpty() -> return "+7"
            digits.startsWith("8") -> digits = "7" + digits.drop(1)
            !digits.startsWith("7") -> digits = "7$digits"
        }
        digits = digits.take(11)
        val n = digits.drop(1)
        return buildString {
            append("+7")
            if (n.isEmpty()) return@buildString
            append(" (")
            append(n.take(3))
            if (n.length < 3) return@buildString
            append(") ")
            append(n.drop(3).take(3))
            if (n.length <= 6) return@buildString
            append("-")
            append(n.drop(6).take(2))
            if (n.length <= 8) return@buildString
            append("-")
            append(n.drop(8).take(2))
        }
    }

    private fun attachRuPhoneMask(edit: EditText) {
        edit.inputType = android.text.InputType.TYPE_CLASS_PHONE
        var selfChange = false
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (selfChange) return
                val formatted = formatRuPhoneMask(s?.toString().orEmpty())
                if (formatted == s?.toString()) return
                selfChange = true
                edit.setText(formatted)
                edit.setSelection(formatted.length.coerceAtMost(edit.text?.length ?: 0))
                selfChange = false
            }
        })
    }

    private fun beginAddBatteryFlow() {
        if (!isProfileComplete()) {
            AlertDialog.Builder(this)
                .setTitle("Сначала заполните профиль")
                .setMessage("Для добавления АКБ необходимо указать ФИО и номер телефона.")
                .setPositiveButton("Перейти в профиль") { _, _ -> showProfileScreen() }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        disconnectGatt()
        showSearchScreen()
        startScan()
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
        showDashboardScreen(asRootHome = true)
    }

    private fun loadSavedBatteries(): List<SavedBattery> {
        if (isServiceApp()) {
            return sessionBatteries.sortedByDescending { it.lastSeenAt }
        }
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
        if (isServiceApp()) {
            sessionBatteries.clear()
            sessionBatteries.addAll(items)
            return
        }
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

    private fun removeCurrentSessionBattery() {
        if (!isServiceApp()) return
        val address = selectedAddress ?: return
        sessionBatteries.removeAll { it.address.equals(address, ignoreCase = true) }
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
        if (!isProfileComplete()) {
            beginAddBatteryFlow()
            return
        }
        enterScreen("search")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        root.addView(header("Поиск устройств", showBack = true), LinearLayout.LayoutParams(-1, dp(74)))

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
        content.addView(TextView(this).apply {
            text = "Обновить список"
            textSize = 14f
            setTextColor(redDark)
            typeface = interFont(700)
            isClickable = true
            isFocusable = true
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { startScan() }
        })
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

    private fun openBatteriesListFromDashboard() {
        clearUiBackStack()
        showBatteriesScreen(asRootHome = true)
    }

    private fun currentDirectionLabel(current: Double?): String {
        if (current == null) return "Ожидание данных"
        return when {
            kotlin.math.abs(current) < 0.05 -> "Покой"
            current < 0.0 -> "Заряд"
            else -> "Разряд"
        }
    }

    private fun socStatusLabel(soc: Double?): Pair<String, Int> {
        val s = soc ?: return ("Нет данных" to Color.rgb(111, 119, 129))
        return when {
            s <= 20.0 -> "Низкий уровень заряда" to Color.rgb(210, 70, 70)
            s < 70.0 -> "Средний уровень заряда" to Color.rgb(215, 160, 35)
            else -> "Батарея заряжена" to Color.rgb(31, 179, 90)
        }
    }

    /**
     * Подгоняет размер шрифта под ширину TextView (1 строка, без «...»).
     * Надёжнее TextViewCompat auto-size: Activity не AppCompat, обычный TextView.
     */
    private fun fitTextToWidth(tv: TextView, minSp: Float, maxSp: Float) {
        val doFit = Runnable {
            val available = tv.width - tv.paddingLeft - tv.paddingRight
            val text = tv.text?.toString().orEmpty()
            if (available <= 0 || text.isEmpty()) return@Runnable
            var lo = minSp
            var hi = maxSp
            var best = minSp
            val paint = Paint(tv.paint)
            while (hi - lo > 0.2f) {
                val mid = (lo + hi) / 2f
                paint.textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    mid,
                    resources.displayMetrics
                )
                if (paint.measureText(text) <= available) {
                    best = mid
                    lo = mid
                } else {
                    hi = mid
                }
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, best)
        }
        tv.maxLines = 1
        tv.isSingleLine = true
        tv.ellipsize = null
        tv.includeFontPadding = false
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxSp)
        if (tv.width > 0) {
            doFit.run()
        } else {
            tv.post(doFit)
        }
    }

    private fun enableWidthFit(tv: TextView, minSp: Float, maxSp: Float) {
        fitTextToWidth(tv, minSp, maxSp)
        tv.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                fitTextToWidth(tv, minSp, maxSp)
            }
        }
    }

    /** Компактная info-плитка: заголовок и значение в 1 строку, без иконки, без обрезки. */
    private fun infoOneLineTile(label: String, initial: String): Pair<LinearLayout, TextView> {
        val titleView = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(650)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            ellipsize = null
        }
        val valueView = TextView(this).apply {
            text = initial
            textSize = 13f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(720)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            ellipsize = null
            letterSpacing = -0.02f
        }
        enableWidthFit(titleView, 7f, 11f)
        enableWidthFit(valueView, 6f, 13f)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            minimumHeight = dp(78)
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            addView(titleView, LinearLayout.LayoutParams(-1, dp(16)))
            addView(valueView, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(4) })
        }
        return box to valueView
    }

    private fun capacityInnerTile(label: String, valueView: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(Color.rgb(248, 249, 251), dp(12), Color.rgb(223, 229, 235), 1)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(Color.rgb(111, 119, 129))
                typeface = interFont(650)
                maxLines = 1
            })
            addView(valueView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    /** Единая типографика карточек главного экрана (без смены визуального языка). */
    private fun classicMetricBox(
        icon: String,
        initial: String,
        label: String,
        sub: String = "",
        valueSize: Float = 18f,
        titleSize: Float = 12f,
        truncate: Boolean = false,
        fixedHeight: Boolean = true,
        compact: Boolean = false,
        fitOneLine: Boolean = false
    ): Pair<LinearLayout, TextView> {
        val value = TextView(this).apply {
            text = initial
            textSize = valueSize
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(if (compact) 720 else 760)
            maxLines = if (truncate) 2 else 1
            if (fitOneLine) {
                setSingleLine(true)
                ellipsize = null
            } else {
                ellipsize = TextUtils.TruncateAt.END
            }
            setLineSpacing(0f, 1.05f)
        }
        val subView = TextView(this).apply {
            text = sub.ifBlank { " " }
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(600)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.VISIBLE
        }
        val titleView = TextView(this).apply {
            text = if (icon.isBlank()) label else "$icon  $label"
            textSize = titleSize
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(650)
            maxLines = 1
            if (fitOneLine) {
                setSingleLine(true)
                ellipsize = null
            } else {
                ellipsize = TextUtils.TruncateAt.END
            }
            setLineSpacing(0f, 1.05f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (compact) 10 else 12), dp(11), dp(if (compact) 10 else 12), dp(11))
            if (fixedHeight) minimumHeight = dp(if (compact) 88 else 86)
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            addView(titleView, LinearLayout.LayoutParams(-1, if (fitOneLine) dp(16) else -2))
            addView(
                value,
                LinearLayout.LayoutParams(-1, if (fitOneLine) dp(22) else -2).apply {
                    topMargin = dp(5)
                }
            )
            addView(subView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        if (fitOneLine) {
            enableWidthFit(titleView, 7f, titleSize.coerceAtLeast(7f))
            enableWidthFit(value, 6f, valueSize.coerceAtLeast(6f))
        }
        box.tag = subView
        return box to value
    }

    private fun classicMetricSub(box: LinearLayout): TextView? = box.tag as? TextView

    private fun showDashboardScreen(asRootHome: Boolean = false) {
        if (asRootHome) clearUiBackStack()
        enterScreen("dashboard", track = !asRootHome)
        currentTab = "main"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            header(
                "ЛИФЕРЫЧ",
                selectedDeviceName.ifBlank { selectedAddress ?: "" },
                showBack = true,
                showBrand = true
            )
        )

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad(), 0, hPad(), dp(22))
        }

        // Список BMS — в стиле старых карточек
        val listRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            isClickable = true
            isFocusable = true
            setOnClickListener { openBatteriesListFromDashboard() }
            addView(TextView(this@MainActivity).apply {
                text = "≡"
                textSize = 18f
                setTextColor(Color.rgb(16, 17, 20))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), -2))
            addView(TextView(this@MainActivity).apply {
                text = "Список BMS / Добавить новую"
                textSize = 14f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(700)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 22f
                setTextColor(Color.rgb(111, 119, 129))
            })
        }
        content.addView(listRow, marginLp(-1, -2, 0, 8, 0, 8))

        // Имя | Серийный номер | Версия — 1 строка, полный текст (без иконок, auto-size)
        val nameMetric = infoOneLineTile(
            "Имя устройства",
            selectedDeviceName.ifBlank { selectedAddress ?: "--" }
        )
        deviceNameValue = nameMetric.second
        val snMetric = infoOneLineTile(
            "Серийный номер",
            displayFactorySn().ifBlank { "--" }
        )
        bmsSnValue = snMetric.second
        val verMetric = infoOneLineTile(
            "Версия BMS",
            displayBmsVersion().ifBlank { "--" }
        )
        bmsVersionValue = verMetric.second
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.FILL
            isMeasureWithLargestChildEnabled = true
        }
        infoRow.addView(nameMetric.first, marginLp(0, -1, 0, 0, 4, 0).apply { weight = 1f })
        infoRow.addView(snMetric.first, marginLp(0, -1, 4, 0, 4, 0).apply { weight = 1f })
        infoRow.addView(verMetric.first, marginLp(0, -1, 4, 0, 0, 0).apply { weight = 1f })
        content.addView(infoRow, marginLp(-1, -2, 0, 0, 0, 8))
        equalizeRowChildHeights(infoRow)

        // SOC (крупнее) + ёмкость правее (две внутренние плашки)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(16), dp(16), dp(16))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            elevation = dp(2).toFloat()
        }
        val socCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        socGauge = SocGaugeView(this)
        val gaugeSize = dp(156)
        socCol.addView(socGauge, LinearLayout.LayoutParams(gaugeSize, gaugeSize))
        val (stLabel, stColor) = socStatusLabel(data.soc)
        socStatusText = TextView(this).apply {
            text = stLabel
            textSize = 12f
            setTextColor(stColor)
            typeface = interFont(700)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        socCol.addView(socStatusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        hero.addView(socCol, LinearLayout.LayoutParams(0, -2, 0.95f))

        val capacityCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(2), dp(2))
        }
        fullCapacityValue = TextView(this).apply {
            text = "-- А·ч"
            textSize = 20f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(770)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        remainingValue = TextView(this).apply {
            text = "-- А·ч"
            textSize = 20f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(770)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        capacityCol.addView(
            capacityInnerTile("Полная ёмкость", fullCapacityValue!!),
            LinearLayout.LayoutParams(-1, -2)
        )
        capacityCol.addView(
            capacityInnerTile("Осталось", remainingValue),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        )
        socProgress = null
        hero.addView(capacityCol, LinearLayout.LayoutParams(0, -1, 1.05f))
        content.addView(hero, marginLp(-1, -2, 0, 0, 0, 8))

        fun addPair(left: View, right: View) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.FILL
                isMeasureWithLargestChildEnabled = true
            }
            row.addView(left, marginLp(0, -1, 0, 0, 4, 0).apply { weight = 1f })
            row.addView(right, marginLp(0, -1, 4, 0, 0, 0).apply { weight = 1f })
            content.addView(row, marginLp(-1, -2, 0, 0, 0, 8))
            equalizeRowChildHeights(row)
        }

        val voltageMetric = classicMetricBox(
            "⚡", "-- В", "Напряжение", "Напряжение батареи",
            valueSize = 18f, titleSize = 12f
        )
        voltageValue = voltageMetric.second
        val currentMetric = classicMetricBox(
            "↯",
            "-- А",
            "Ток",
            currentDirectionLabel(data.current),
            valueSize = 18f,
            titleSize = 12f
        )
        currentValue = currentMetric.second
        currentSubValue = classicMetricSub(currentMetric.first)
        addPair(voltageMetric.first, currentMetric.first)

        val tempMetric = classicMetricBox(
            "°", "-- °C", "Температура", "Датчик BMS",
            valueSize = 18f, titleSize = 12f
        )
        t1Text = tempMetric.second
        val cellsMetric = classicMetricBox(
            "▤",
            data.cellCount?.toString() ?: "--",
            "Количество ячеек",
            "LiFePO4",
            valueSize = 18f,
            titleSize = 12f
        )
        cellCountValue = cellsMetric.second
        addPair(tempMetric.first, cellsMetric.first)

        val chargeMetric = classicMetricBox(
            "⏻", "—", "MOS зарядки", " ",
            valueSize = 18f, titleSize = 12f
        )
        chargeMosValue = chargeMetric.second
        chargeMosDot = null
        val dischargeMetric = classicMetricBox(
            "⏻", "—", "MOS разрядки", " ",
            valueSize = 18f, titleSize = 12f
        )
        dischargeMosValue = dischargeMetric.second
        dischargeMosDot = null
        addPair(chargeMetric.first, dischargeMetric.first)

        val statusMetric = classicMetricBox(
            "◉", "—", "Статус BMS", " ",
            valueSize = 18f, titleSize = 12f
        )
        balanceValue = statusMetric.second
        balanceDot = null
        val stateMetric = classicMetricBox(
            "≈", "—", "Состояние", " ",
            valueSize = 18f, titleSize = 12f
        )
        stateValue = stateMetric.second
        stateDot = null
        addPair(statusMetric.first, stateMetric.first)

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
        heatValue = TextView(this)
        t2Text = TextView(this)
        cycleCountValue = TextView(this)
        bluetoothIdValue = if (isServiceApp()) {
            TextView(this).apply { visibility = View.GONE }
        } else null

        val cellsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
        }
        val cellsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cellsHeader.addView(TextView(this).apply {
            text = "Напряжение по ячейкам"
            textSize = 14f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(760)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        cellDiffHeaderValue = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(650)
        }
        cellsHeader.addView(cellDiffHeaderValue)
        cellsCard.addView(cellsHeader)
        cellsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        cellsCard.addView(cellsLayout)
        content.addView(cellsCard, marginLp(-1, -2, 0, 8, 0, 8))

        overallStatusBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
        }
        overallStatusIcon = null
        overallStatusTitle = TextView(this).apply {
            text = "✓  Батарея в норме"
            textSize = 14f
            setTextColor(Color.rgb(31, 179, 90))
            typeface = interFont(760)
        }
        overallStatusSub = TextView(this).apply {
            text = "Все параметры в пределах нормы"
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
        }
        overallStatusBanner?.addView(overallStatusTitle)
        overallStatusBanner?.addView(
            overallStatusSub,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        overallStatusBanner?.isClickable = true
        overallStatusBanner?.isFocusable = true
        overallStatusBanner?.setOnClickListener { showConfigCheckScreen() }
        content.addView(overallStatusBanner, marginLp(-1, -2, 0, 0, 0, 4))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
        updateDashboardUi()
    }

    private fun header(
        title: String,
        @Suppress("UNUSED_PARAMETER") sub: String = "",
        @Suppress("UNUSED_PARAMETER") right: String = "",
        showBack: Boolean = false,
        showBrand: Boolean = !showBack
    ): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setBackgroundColor(Color.WHITE)
        }

        if (showBack) {
            root.addView(TextView(this).apply {
                text = "←"
                textSize = 26f
                setTextColor(Color.rgb(16, 17, 20))
                gravity = Gravity.CENTER
                contentDescription = "Назад"
                isClickable = true
                isFocusable = true
                setPadding(dp(2), dp(4), dp(10), dp(4))
                setOnClickListener { navigateBackUi() }
            }, LinearLayout.LayoutParams(-2, dp(56)))
        }

        if (showBrand) {
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
                    textSize = if (showBack) 20f else 22f
                    setTextColor(Color.rgb(16, 17, 20))
                    typeface = interFont(800)
                }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            }
            root.addView(brand, LinearLayout.LayoutParams(0, dp(56), 1f))
        } else {
            root.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(Color.rgb(16, 17, 20))
                typeface = interFont(750)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }

        return root
    }

    private fun clearUiBackStack() {
        uiBackStack.clear()
    }

    private fun isTrackableScreen(state: String): Boolean {
        return state in setOf(
            "batteries", "dashboard", "journal", "support", "profile",
            "manage", "service", "qtc", "auth", "search"
        )
    }

    private fun enterScreen(newState: String, track: Boolean = true) {
        if (track && !navigatingBack) {
            val prev = screenState
            if (prev != newState && isTrackableScreen(prev)) {
                if (uiBackStack.lastOrNull() != prev) {
                    uiBackStack.addLast(prev)
                }
                while (uiBackStack.size > 32) uiBackStack.removeFirst()
            }
        }
        screenState = newState
    }

    /** Текущая выбранная АКБ, если она ещё есть в сохранённом списке. */
    private fun currentSavedBatteryOrNull(): SavedBattery? {
        val addr = selectedAddress ?: return null
        return loadSavedBatteries().firstOrNull { it.address.equals(addr, ignoreCase = true) }
    }

    private fun isRootHomeScreen(state: String = screenState): Boolean {
        // Корневой экран выхода — список BMS. Dashboard с выбранной АКБ не корень:
        // ← / Back ведут к списку, а не закрывают приложение.
        return state == "batteries"
    }

    private fun goHomeFromMenu() {
        clearUiBackStack()
        val active = currentSavedBatteryOrNull()
        if (active != null) {
            selectedAddress = active.address
            if (selectedDeviceName.isBlank()) {
                selectedDeviceName = active.customName.ifBlank {
                    active.bluetoothName.ifBlank { active.address }
                }
            }
            showDashboardScreen(asRootHome = true)
        } else {
            if (selectedAddress != null &&
                loadSavedBatteries().none { it.address.equals(selectedAddress, ignoreCase = true) }
            ) {
                selectedAddress = null
            }
            showBatteriesScreen(asRootHome = true)
        }
    }

    private fun stopBleScanQuietly() {
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun restoreUiScreen(state: String) {
        when (state) {
            "batteries" -> showBatteriesScreen()
            "dashboard" -> showDashboardScreen()
            "journal" -> showJournalScreen()
            "support" -> showSupportScreen()
            "profile" -> showProfileScreen()
            "manage" -> showManageScreen()
            "config_check" -> showConfigCheckScreen()
            "service" -> showServiceScreen()
            "qtc" -> showQtcScreen()
            "auth" -> showAuthScreen()
            "search" -> showSearchScreen()
            else -> goHomeFromMenu()
        }
    }

    /** Возврат по UI-стеку. true — обработано; false — на корне, можно выйти из приложения. */
    private fun navigateBackUi(): Boolean {
        when (screenState) {
            "qr_scan" -> {
                stopQrCamera()
                showQrInputScreen()
                return true
            }
            "qr_result" -> {
                showQrInputScreen()
                return true
            }
            "qr_input" -> {
                showSplashScreen()
                return true
            }
            "dashboard" -> {
                openBatteriesListFromDashboard()
                return true
            }
        }

        if (screenState == "search" || screenState == "loading") {
            stopBleScanQuietly()
            disconnectGatt()
        }

        while (uiBackStack.isNotEmpty()) {
            val prev = uiBackStack.removeLast()
            if (prev == screenState) continue
            navigatingBack = true
            try {
                restoreUiScreen(prev)
            } finally {
                navigatingBack = false
            }
            return true
        }

        if (!isRootHomeScreen()) {
            navigatingBack = true
            try {
                goHomeFromMenu()
            } finally {
                navigatingBack = false
            }
            return true
        }
        return false
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
                    val onWorkingHome = screenState == "dashboard" && currentSavedBatteryOrNull() != null
                    val onListHome = screenState == "batteries" && currentSavedBatteryOrNull() == null
                    if (onWorkingHome || onListHome) return@setOnClickListener
                    goHomeFromMenu()
                } else if (text.contains("Сервис")) {
                    if (screenState == "service") return@setOnClickListener
                    showServiceScreen()
                } else if (text.contains("ОТК")) {
                    if (screenState == "qtc") return@setOnClickListener
                    showQtcScreen()
                } else if (text.contains("Управление") || text.contains("Настройки")) {
                    if (screenState == "manage") return@setOnClickListener
                    showManageScreen()
                } else if (text.contains("Журнал")) {
                    if (screenState == "journal") return@setOnClickListener
                    showJournalScreen()
                } else if (text.contains("Поддержка") || text.contains("Техподдержка")) {
                    if (screenState == "support") return@setOnClickListener
                    showSupportScreen()
                } else if (text.contains("Профиль")) {
                    if (isServiceApp()) {
                        if (screenState == "profile") return@setOnClickListener
                        showProfileScreen()
                    } else {
                        val loggedIn = getSharedPreferences("user_profile", MODE_PRIVATE)
                            .getBoolean("logged_in", false)
                        if (loggedIn) {
                            if (screenState == "profile") return@setOnClickListener
                            showProfileScreen()
                        } else {
                            if (screenState == "auth") return@setOnClickListener
                            showAuthScreen()
                        }
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
        enterScreen("service")
        currentTab = "service"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Сервис BMS",
                selectedDeviceName.ifBlank { selectedAddress ?: "нет подключения" },
                showBack = true
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

        serviceUploadStatusText = TextView(this).apply {
            text = currentUploadStatusText()
            textSize = 13f
            setTextColor(Color.rgb(50, 50, 50))
        }
        content.addView(serviceUploadStatusText, marginLp(-1, -2, 0, 0, 0, 10))
        val sendRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendRow.addView(TextView(this).apply {
            text = "Проверить связь"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
            setOnClickListener { pingAdminServer() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        sendRow.addView(TextView(this).apply {
            text = "Отправить сейчас"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(12), Color.TRANSPARENT, 0)
            setOnClickListener { uploadCurrentData(force = true) }
        }, marginLp(0, dp(48), 8, 0, 0, 0).apply { weight = 1f })
        content.addView(sendRow, marginLp(-1, -2, 0, 0, 0, 12))

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

        val writePercent = serviceWriteProgressPercent()
        serviceWriteProgressText = TextView(this).apply {
            text = "Запись шаблона $writePercent%"
            textSize = 13f
            typeface = interFont(700)
            setTextColor(Color.rgb(50, 50, 50))
            visibility = if (serviceWriteActive) View.VISIBLE else View.GONE
        }
        content.addView(serviceWriteProgressText, marginLp(-1, -2, 0, 0, 0, 6))
        serviceWriteProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = writePercent
            isIndeterminate = false
            visibility = if (serviceWriteActive) View.VISIBLE else View.GONE
        }
        content.addView(serviceWriteProgressBar, marginLp(-1, dp(10), 0, 0, 0, 10))

        if (serviceWriteResults.isNotEmpty() && !serviceWriteActive) {
            val failed = serviceWriteResults.count { !it.ok }
            val summaryCard = card()
            summaryCard.addView(sectionTitle(
                if (failed == 0) "Настройка завершена" else "Настройка не завершена",
                if (failed == 0) {
                    "Все параметры записаны и проверены чтением из BMS"
                } else {
                    "Не удалось подтвердить $failed параметр(ов). АКБ остаётся в списке сессии."
                }
            ))
            serviceWriteResults.forEach { item ->
                summaryCard.addView(TextView(this).apply {
                    text = formatServiceWriteResultLine(item)
                    textSize = 13f
                    typeface = interFont(if (item.ok) 650 else 700)
                    setTextColor(
                        if (item.ok) Color.rgb(31, 120, 70) else Color.rgb(180, 40, 40)
                    )
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
            content.addView(summaryCard, marginLp(-1, -2, 0, 0, 0, 12))
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("service"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showQtcScreen() {
        if (!isServiceApp()) {
            showBatteriesScreen()
            return
        }
        if (configRegisters.isEmpty() && !configReadInProgress) loadCachedConfigForCurrentBms()
        fetchServerConfigTemplate(force = false)

        enterScreen("qtc")
        currentTab = "qtc"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "ОТК",
                selectedDeviceName.ifBlank { selectedAddress ?: "нет подключения" },
                showBack = true
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
        ensureQtcBatteryRegistry(fromUser = true)
    }

    private fun renderQtcContent(triggerRegistry: Boolean = true) {
        if (!::qtcContentLayout.isInitialized) return
        qtcContentLayout.removeAllViews()

        val result = if (configRegisters.isNotEmpty()) evaluateTemplateCheck() else currentTemplateCheck()
        qtcContentLayout.addView(qtcDatabaseStatusCard(), marginLp(-1, -2, 0, 0, 0, 12))
        qtcContentLayout.addView(qtcStatusCard(result), marginLp(-1, -2, 0, 0, 0, 12))
        if (triggerRegistry) ensureQtcBatteryRegistry(fromUser = false)

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
            "unavailable" -> "Проверка конфигурации недоступна"
            else -> "Конфигурация ещё не проверена"
        }
        val detail = when (status) {
            "ok" -> "Серия $series. Все параметры шаблона совпадают."
            "mismatch" -> "Серия $series. Ниже указаны расхождения."
            "incomplete" -> "Серия $series. Не все параметры удалось сравнить."
            "unavailable" -> "Не удалось получить актуальный шаблон с сервера."
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
        val series = resolvedSeriesCount()
        val template = activeCheckTemplate()
            ?: return listOf("Шаблон" to "не получен с сервера")
        val family = currentHardwareFamily()
        val rows = mutableListOf<Pair<String, String>>()
        for (parameter in template.parameters) {
            if (!parameter.enabled || parameter.enforcement == "informational") continue
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

    private fun qtcHasIdentity(): Boolean {
        if (bluetoothGatt == null) return false
        val uid = bmsUid().trim()
        return uid.isNotBlank() && uid != "unknown_bms"
    }

    private fun qtcDatabaseStatusCard(): LinearLayout {
        val title: String
        val detail: String
        val color: Int
        val showRetry: Boolean
        when (qtcDbStatus) {
            QtcDbStatus.IN_DATABASE -> {
                title = "✓  АКБ в базе"
                detail = "Батарея уже зарегистрирована на сервере."
                color = Color.rgb(28, 160, 55)
                showRetry = false
            }
            QtcDbStatus.ADDED -> {
                title = "✓  АКБ добавлена в базу"
                detail = "Регистрация подтверждена сервером."
                color = Color.rgb(21, 122, 163)
                showRetry = false
            }
            QtcDbStatus.SENDING -> {
                title = "Отправка на сервер..."
                detail = "Регистрируем аккумулятор в базе."
                color = Color.rgb(224, 150, 0)
                showRetry = false
            }
            QtcDbStatus.VERIFYING -> {
                title = "Проверка базы..."
                detail = "Подтверждаем регистрацию на сервере."
                color = Color.rgb(110, 118, 128)
                showRetry = false
            }
            QtcDbStatus.ERROR -> {
                title = "АКБ не отправлена"
                detail = qtcDbError.ifBlank { "Нет связи с сервером" }
                color = Color.rgb(211, 47, 47)
                showRetry = true
            }
            QtcDbStatus.WAITING_BMS -> {
                title = "Проверка базы..."
                detail = if (bluetoothGatt == null) {
                    "Подключите BMS для регистрации в базе."
                } else {
                    "Ожидание идентификатора BMS."
                }
                color = Color.rgb(110, 118, 128)
                showRetry = false
            }
            else -> {
                title = "Проверка базы..."
                detail = "Сверяем аккумулятор с серверной базой."
                color = Color.rgb(110, 118, 128)
                showRetry = false
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = round(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), dp(12), color, 1)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = interFont(740)
                setTextColor(color)
            })
            addView(TextView(this@MainActivity).apply {
                text = detail
                textSize = 12f
                setTextColor(Color.rgb(75, 79, 84))
                setPadding(0, dp(3), 0, 0)
            })
            if (showRetry) {
                addView(TextView(this@MainActivity).apply {
                    text = "Повторить"
                    gravity = Gravity.CENTER
                    textSize = 14f
                    typeface = interFont(740)
                    setTextColor(Color.rgb(16, 17, 20))
                    background = round(Color.WHITE, dp(12), color, 1)
                    setOnClickListener { ensureQtcBatteryRegistry(fromUser = true, retry = true) }
                }, marginLp(-1, dp(44), 0, 8, 0, 0))
            }
        }
    }

    private fun ensureQtcBatteryRegistry(fromUser: Boolean, retry: Boolean = false) {
        if (!isServiceApp() || screenState != "qtc") return
        if (!qtcHasIdentity()) {
            qtcDbStatus = QtcDbStatus.WAITING_BMS
            qtcDbError = ""
            return
        }
        val uid = bmsUid().trim()
        if (uid != qtcDbUid) {
            qtcDbUid = uid
            qtcDbError = ""
            qtcDbStatus = QtcDbStatus.CHECKING
            startQtcBatteryRegistryJob(uid)
            return
        }
        if (retry) {
            qtcDbError = ""
            qtcDbStatus = QtcDbStatus.CHECKING
            startQtcBatteryRegistryJob(uid)
            renderQtcContent(triggerRegistry = false)
            return
        }
        if (qtcDbStatus == QtcDbStatus.CHECKING ||
            qtcDbStatus == QtcDbStatus.SENDING ||
            qtcDbStatus == QtcDbStatus.VERIFYING
        ) {
            return
        }
        if (!fromUser && qtcDbStatus != QtcDbStatus.IDLE && qtcDbStatus != QtcDbStatus.WAITING_BMS) {
            return
        }
        qtcDbError = ""
        qtcDbStatus = QtcDbStatus.CHECKING
        startQtcBatteryRegistryJob(uid)
    }

    private fun startQtcBatteryRegistryJob(uid: String) {
        val token = ++qtcDbJobToken
        thread {
            runQtcBatteryRegistry(uid, token)
        }
    }

    private fun runQtcBatteryRegistry(uid: String, token: Int) {
        fun stillCurrent(): Boolean {
            return token == qtcDbJobToken && uid == qtcDbUid && screenState == "qtc"
        }
        fun publish(status: QtcDbStatus, error: String = "") {
            if (!stillCurrent()) return
            qtcDbStatus = status
            qtcDbError = error
            runOnUiThread {
                if (stillCurrent() && ::qtcContentLayout.isInitialized) {
                    renderQtcContent(triggerRegistry = false)
                }
            }
        }

        publish(QtcDbStatus.CHECKING)
        when (val lookup = lookupBatteryOnServer(uid)) {
            is QtcLookup.Exists -> {
                publish(QtcDbStatus.IN_DATABASE)
                return
            }
            is QtcLookup.Failed -> {
                publish(QtcDbStatus.ERROR, lookup.reason)
                return
            }
            QtcLookup.Missing -> Unit
        }

        if (!stillCurrent()) return
        publish(QtcDbStatus.SENDING)
        val sent = postCurrentTelemetry()
        if (!stillCurrent()) return
        if (!sent.ok) {
            publish(QtcDbStatus.ERROR, sent.message)
            return
        }

        publish(QtcDbStatus.VERIFYING)
        when (val verify = lookupBatteryOnServer(uid)) {
            is QtcLookup.Exists -> publish(QtcDbStatus.ADDED)
            is QtcLookup.Missing -> publish(QtcDbStatus.ERROR, "Сервер не подтвердил регистрацию")
            is QtcLookup.Failed -> publish(QtcDbStatus.ERROR, verify.reason)
        }
    }

    private sealed class QtcLookup {
        object Exists : QtcLookup()
        object Missing : QtcLookup()
        data class Failed(val reason: String) : QtcLookup()
    }

    private fun lookupBatteryOnServer(uid: String): QtcLookup {
        val encoded = URLEncoder.encode(uid, "UTF-8")
        val json = adminJsonRequest("GET", "/api/v1/batteries/$encoded/telemetry?limit=1")
            ?: return QtcLookup.Failed("Нет связи с сервером")
        if (json.optBoolean("ok")) return QtcLookup.Exists
        return when (json.optString("error")) {
            "battery_not_found" -> QtcLookup.Missing
            else -> QtcLookup.Failed("Нет связи с сервером")
        }
    }

    private fun serviceWriteSummaryText(): String {
        val ok = serviceWriteResults.count { it.ok }
        return "Успешно $ok из ${serviceWriteResults.size}"
    }

    private fun writeTargetSeries(): Int {
        // Сервисный полный шаблон: серия из выбора 12В/24В.
        if (serviceWriteCapacityAh != null) {
            return if (serviceTemplateKey == "24v") 8 else 4
        }
        return resolvedSeriesCount() ?: if (serviceTemplateKey == "24v") 8 else 4
    }

    /** Клиент: записать только writable-параметры шаблона, которые не совпадают. */
    private fun startClientTemplateApply() {
        if (serviceWriteActive) {
            toast("Запись уже выполняется")
            return
        }
        if (bluetoothGatt == null || writeCharacteristic == null) {
            toast("Подключите BMS по Bluetooth")
            return
        }
        toast("Получаем актуальный шаблон с сервера…")
        fetchServerConfigTemplate(force = true) {
            val template = activeServerTemplate
            if (template == null) {
                toast(
                    serverTemplateFetchError
                        ?: "Не удалось получить актуальный шаблон. Настройка недоступна."
                )
                return@fetchServerConfigTemplate
            }
            val series = resolvedSeriesCount()
            if (series == null || series !in template.supportedSeries) {
                toast("Нет подходящего шаблона конфигурации для этой BMS")
                return@fetchServerConfigTemplate
            }
            enqueueClientTemplateWrites(series, template)
        }
    }

    private fun enqueueClientTemplateWrites(series: Int, template: BmsConfigTemplate) {
        val family = currentHardwareFamily()
        val queue = java.util.ArrayDeque<RemoteWriteCommand>()
        var id = 1
        serviceWriteResults.clear()
        serviceWriteCapacityAh = null
        serviceWriteRetryRound = 0
        clientTemplateApplyMode = true
        Log.i(
            BLE_LOG_TAG,
            "CONFIG TEMPLATE client apply id=${template.id} version=${template.version} series=$series family=$family"
        )
        for (parameter in orderedTemplateParameters(template.parameters)) {
            if (!parameter.writable) continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            if (parameter.key == "series_cell_count") {
                if (data.cellCount == expected.toInt()) {
                    upsertServiceWriteResult(
                        ServiceWriteResult(
                            key = parameter.key,
                            label = parameter.label,
                            expected = expected,
                            actual = data.cellCount?.toDouble(),
                            unit = parameter.unit,
                            ok = true
                        )
                    )
                } else {
                    id = enqueueSeriesCountWrite(queue, expected.toInt(), id)
                }
                continue
            }
            if (templateParameterMatches(parameter, expected)) {
                upsertServiceWriteResult(
                    ServiceWriteResult(
                        key = parameter.key,
                        label = parameter.label,
                        expected = expected,
                        actual = templateParameterActual(parameter),
                        unit = parameter.unit,
                        ok = true
                    )
                )
                continue
            }
            val command = templateParameterWriteCommand(id, parameter, expected) ?: continue
            Log.i(
                BLE_LOG_TAG,
                "PARAM ${parameter.key} expected=$expected ${parameter.unit} client enqueue write"
            )
            queue.add(command)
            id++
        }
        if (queue.isEmpty()) {
            clientTemplateApplyMode = false
            serviceWriteActive = false
            if (configRegisters.isNotEmpty()) {
                setTemplateCheckResult(evaluateTemplateCheck())
            }
            toast(
                if (serviceWriteResults.isEmpty()) "Нет параметров для записи"
                else "Все параметры уже совпадают, запись не нужна"
            )
            showConfigCheckScreen()
            return
        }
        serviceWriteQueue = queue
        serviceWriteTotal = queue.size
        serviceWriteDone = 0
        serviceWriteActive = true
        toast("Запись ${queue.size} параметр(ов) по шаблону…")
        showConfigCheckScreen()
        val first = serviceWriteQueue.poll()
        if (first != null) startRemoteWrite(first)
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
        toast("Получаем актуальный шаблон с сервера…")
        fetchServerConfigTemplate(force = true) {
            val template = activeServerTemplate
            if (template == null) {
                toast(
                    serverTemplateFetchError
                        ?: "Не удалось получить актуальный шаблон конфигурации. Настройка недоступна."
                )
                return@fetchServerConfigTemplate
            }
            if (series !in template.supportedSeries) {
                toast("Нет подходящего шаблона конфигурации для этой BMS (${series}S)")
                return@fetchServerConfigTemplate
            }
            enqueueServiceTemplateWrites(capacity, series, template)
        }
    }

    private fun serviceWriteProgressPercent(): Int {
        if (serviceWriteTotal <= 0) return 0
        return ((serviceWriteDone * 100) / serviceWriteTotal).coerceIn(0, 100)
    }

    private fun updateServiceWriteProgressUi() {
        runOnUiThread {
            val percent = serviceWriteProgressPercent()
            serviceWriteProgressBar?.progress = percent
            serviceWriteProgressBar?.visibility = if (serviceWriteActive) View.VISIBLE else View.GONE
            serviceWriteProgressText?.text = "Запись шаблона $percent%"
            serviceWriteProgressText?.visibility = if (serviceWriteActive) View.VISIBLE else View.GONE
        }
    }

    /** Эталон для записи — только серверный template (не assets). */
    private fun activeWriteTemplate(): BmsConfigTemplate? = activeServerTemplate

    private fun enqueueServiceTemplateWrites(
        capacityAh: Double,
        series: Int,
        template: BmsConfigTemplate
    ) {
        if (series !in template.supportedSeries) {
            toast("Нет подходящего шаблона конфигурации для этой BMS (${series}S)")
            return
        }
        val family = currentHardwareFamily()
        val queue = java.util.ArrayDeque<RemoteWriteCommand>()
        var id = 1
        serviceWriteResults.clear()
        serviceWriteCapacityAh = capacityAh
        serviceWriteRetryRound = 0
        Log.i(
            BLE_LOG_TAG,
            "CONFIG TEMPLATE write start id=${template.id} version=${template.version} series=$series family=$family"
        )
        id = enqueueSeriesCountWrite(queue, series, id)
        for (parameter in orderedTemplateParameters(template.parameters)) {
            if (parameter.key == "series_cell_count") continue
            if (!parameter.writable) continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            if (templateParameterMatches(parameter, expected)) {
                upsertServiceWriteResult(
                    ServiceWriteResult(
                        key = parameter.key,
                        label = parameter.label,
                        expected = expected,
                        actual = templateParameterActual(parameter),
                        unit = parameter.unit,
                        ok = true,
                        error = null
                    )
                )
                continue
            }
            val command = templateParameterWriteCommand(id, parameter, expected) ?: continue
            Log.i(
                BLE_LOG_TAG,
                "PARAM ${parameter.key} expected=$expected ${parameter.unit} enqueue write register=0x${parameter.register.toString(16)}"
            )
            queue.add(command)
            id++
        }
        id = enqueueServiceCapacityWrite(queue, capacityAh, id)
        id = enqueueServiceSocWrite(queue, capacityAh, id)
        enqueueServicePasswordWrite(queue, id)
        if (queue.isEmpty()) {
            serviceWriteActive = false
            serviceWriteTotal = 0
            serviceWriteDone = 0
            uploadServiceReport()
            showServiceScreen()
            toast(
                if (serviceWriteResults.isEmpty()) "В шаблоне нет параметров для записи"
                else "Все параметры уже совпадают, запись не нужна"
            )
            if (configRegisters.isNotEmpty()) {
                setTemplateCheckResult(evaluateTemplateCheck())
            }
            return
        }
        serviceWriteQueue = queue
        serviceWriteTotal = queue.size
        serviceWriteDone = 0
        serviceWriteActive = true
        showServiceScreen()
        val first = serviceWriteQueue.poll()
        if (first != null) startRemoteWrite(first)
    }

    private fun capacityRegisterFrames(capacityAh: Double): List<ByteArray> {
        val milliAh = Math.round(capacityAh * 1000.0).toInt().coerceIn(1, 0x7FFFFFFF)
        val capHi = (milliAh ushr 16) and 0xFFFF
        val capLo = milliAh and 0xFFFF
        return listOf(
            buildModbusWriteSingleRequest(0x81, DALY_REMAINING_CAPACITY_HI_REG, capHi),
            buildModbusWriteSingleRequest(0x81, DALY_REMAINING_CAPACITY_LO_REG, capLo),
            buildModbusWriteSingleRequest(0x81, DALY_NOMINAL_CAPACITY_HI_REG, capHi),
            buildModbusWriteSingleRequest(0x81, DALY_NOMINAL_CAPACITY_LO_REG, capLo)
        )
    }

    private fun remainingCapacityFrames(capacityAh: Double): List<ByteArray> {
        val milliAh = Math.round(capacityAh * 1000.0).toInt().coerceIn(1, 0x7FFFFFFF)
        val capHi = (milliAh ushr 16) and 0xFFFF
        val capLo = milliAh and 0xFFFF
        return listOf(
            buildModbusWriteSingleRequest(0x81, DALY_REMAINING_CAPACITY_HI_REG, capHi),
            buildModbusWriteSingleRequest(0x81, DALY_REMAINING_CAPACITY_LO_REG, capLo)
        )
    }

    private fun socWriteFrame(percent: Double = SERVICE_PACK_SOC_PERCENT): ByteArray {
        val raw = Math.round(percent * 10.0).toInt().coerceIn(0, 1000)
        return buildModbusWriteSingleRequest(0x81, TEST_SOC_REGISTER_ADDR, raw)
    }

    private fun enqueueServiceCapacityWrite(
        queue: java.util.ArrayDeque<RemoteWriteCommand>,
        capacityAh: Double,
        nextId: Int
    ): Int {
        if (packGaugeAlreadyProgrammed(capacityAh) && capacityAlreadyMatches(capacityAh)) {
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = "nominal_capacity",
                    label = "Номинальная емкость",
                    expected = capacityAh,
                    actual = data.remainingAh ?: currentNominalCapacityAh(),
                    unit = "Ah",
                    ok = true,
                    error = null
                )
            )
            return nextId
        }
        val milliAh = Math.round(capacityAh * 1000.0).toInt().coerceIn(1, 0x7FFFFFFF)
        queue.add(
            RemoteWriteCommand(
                id = nextId,
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
                writeFrames = capacityRegisterFrames(capacityAh)
            )
        )
        return nextId + 1
    }

    private fun enqueueServiceSocWrite(
        queue: java.util.ArrayDeque<RemoteWriteCommand>,
        capacityAh: Double,
        nextId: Int
    ): Int {
        if (packGaugeAlreadyProgrammed(capacityAh)) {
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = "runtime_soc",
                    label = "SOC",
                    expected = SERVICE_PACK_SOC_PERCENT,
                    actual = data.soc,
                    unit = "%",
                    ok = true,
                    error = null
                )
            )
            return nextId
        }
        val raw = Math.round(SERVICE_PACK_SOC_PERCENT * 10.0).toInt().coerceIn(0, 1000)
        queue.add(
            RemoteWriteCommand(
                id = nextId,
                key = "runtime_soc",
                label = "SOC",
                register = TEST_SOC_REGISTER_ADDR,
                rawValue = raw,
                value = SERVICE_PACK_SOC_PERCENT,
                scale = 10.0,
                offset = 0.0,
                unit = "%",
                localOnly = true,
                displayValue = SERVICE_PACK_SOC_PERCENT,
                writeFrames = listOf(socWriteFrame()) +
                    remainingCapacityFrames(capacityAh) +
                    listOf(socWriteFrame())
            )
        )
        return nextId + 1
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

    private fun orderedTemplateParameters(parameters: List<BmsTemplateParameter>): List<BmsTemplateParameter> {
        return parameters.sortedBy { parameter ->
            val index = SERVICE_TEMPLATE_WRITE_ORDER.indexOf(parameter.key)
            if (index >= 0) index else SERVICE_TEMPLATE_WRITE_ORDER.size
        }
    }

    private fun templateParameterWriteFrames(parameter: BmsTemplateParameter, raw: Int): List<ByteArray>? {
        val write = buildModbusWriteSingleRequest(0x81, parameter.register, raw)
        return when (parameter.key) {
            "cell_over_voltage" -> {
                val side = (raw - 50).coerceIn(0, 0xFFFF)
                listOf(
                    buildModbusWriteSingleRequest(0x81, DALY_CELL_OV_ALARM_REG, side),
                    buildModbusWriteSingleRequest(0x81, DALY_CELL_OV_PROTECT_REG, raw),
                    buildModbusWriteSingleRequest(0x81, DALY_CELL_OV_RECOVERY_REG, side)
                )
            }
            "soc_calibration_0", "soc_calibration_100" -> listOf(write, write)
            else -> null
        }
    }

    private fun templateParameterWriteCommand(
        id: Int,
        parameter: BmsTemplateParameter,
        expected: Double
    ): RemoteWriteCommand? {
        val raw = Math.round((expected - parameter.offset) * parameter.scale).toInt()
        if (raw < 0 || raw > 0xFFFF) return null
        return RemoteWriteCommand(
            id = id,
            key = parameter.key,
            label = parameter.label,
            register = parameter.register,
            rawValue = raw,
            value = expected,
            scale = parameter.scale,
            offset = parameter.offset,
            unit = parameter.unit,
            localOnly = true,
            writeFrames = templateParameterWriteFrames(parameter, raw)
        )
    }

    private fun templateParameterMatches(parameter: BmsTemplateParameter, expected: Double): Boolean {
        val actual = templateParameterActual(parameter) ?: return false
        return templateParameterHasActual(parameter) &&
            kotlin.math.abs(actual - expected) <= parameter.tolerance
    }

    private fun enqueueServiceTemplateRetryIfNeeded(): Boolean {
        if (serviceWriteRetryRound >= SERVICE_WRITE_MAX_RETRY_ROUNDS) return false
        refreshServiceTemplateParamResults()
        val series = writeTargetSeries()
        val family = currentHardwareFamily()
        val template = activeWriteTemplate() ?: return false
        val queue = java.util.ArrayDeque<RemoteWriteCommand>()
        var id = 1000 + serviceWriteRetryRound * 100
        for (parameter in orderedTemplateParameters(template.parameters)) {
            if (parameter.key == "series_cell_count") continue
            if (!parameter.writable) continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            if (templateParameterMatches(parameter, expected)) continue
            val command = templateParameterWriteCommand(id++, parameter, expected) ?: continue
            queue.add(command)
        }
        if (queue.isEmpty()) return false
        serviceWriteRetryRound++
        serviceWriteQueue = queue
        serviceWriteTotal += queue.size
        serviceWriteActive = true
        pendingServiceWriteFinalVerify = false
        updateServiceWriteProgressUi()
        toast("Повторная запись ${queue.size} параметров по шаблону")
        val first = serviceWriteQueue.poll()
        if (first != null) startRemoteWrite(first)
        return true
    }

    private fun templateParameterHasActual(parameter: BmsTemplateParameter): Boolean {
        return if (parameter.key == "series_cell_count") resolvedSeriesCount() != null
        else configRegisters.containsKey(parameter.register)
    }

    private fun templateParameterActual(parameter: BmsTemplateParameter): Double? {
        if (parameter.key == "series_cell_count") return resolvedSeriesCount()?.toDouble()
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
        if (
            command.key != "nominal_capacity" &&
            command.key != "settings_password" &&
            command.key != "runtime_soc"
        ) {
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = command.key,
                    label = command.label,
                    expected = command.displayValue ?: command.value,
                    actual = actual,
                    unit = command.unit,
                    ok = ok,
                    error = if (ok) null else error
                )
            )
        }
        serviceWriteDone++
        updateServiceWriteProgressUi()
        val next = serviceWriteQueue.poll()
        if (next != null) {
            mainHandler.postDelayed({ startRemoteWrite(next) }, 400L)
        } else {
            serviceWriteDone = serviceWriteTotal
            updateServiceWriteProgressUi()
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
        serviceWriteActive = false
        serviceWriteTotal = 0
        serviceWriteDone = 0
        finalizeServiceWriteResults()
        if (configRegisters.isNotEmpty() && activeServerTemplate != null) {
            setTemplateCheckResult(evaluateTemplateCheck())
        }
        val clientMode = clientTemplateApplyMode
        clientTemplateApplyMode = false
        val failed = serviceWriteResults.count { !it.ok }
        if (clientMode) {
            if (serviceWriteResults.isNotEmpty() && failed == 0) {
                toast("Настройка BMS завершена. Все параметры записаны и проверены.")
            } else if (failed > 0) {
                toast("Настройка BMS не завершена. Не подтверждено: $failed")
            } else {
                toast("Запись завершена")
            }
            if (screenState == "dashboard") updateDashboardUi()
            showConfigCheckScreen()
            return
        }
        uploadServiceReport()
        if (serviceWriteResults.isNotEmpty() && failed == 0) {
            removeCurrentSessionBattery()
            toast("Настройка BMS успешно завершена. Все параметры записаны и проверены.")
        } else if (failed > 0) {
            toast("Настройка BMS НЕ завершена. Не удалось подтвердить $failed параметр(ов).")
        } else {
            toast("Запись завершена")
        }
        showServiceScreen()
    }

    private fun formatServiceWriteResultLine(item: ServiceWriteResult): String {
        val expected = item.expected?.let { formatTemplateNumber(it) } ?: "—"
        val actual = item.actual?.let { formatTemplateNumber(it) } ?: "—"
        val unit = item.unit.trim()
        return if (item.ok) {
            "✓ ${item.label}: $actual${if (unit.isNotEmpty()) " $unit" else ""} — записано и проверено"
        } else {
            "✗ ${item.label}\nТребовалось: $expected${if (unit.isNotEmpty()) " $unit" else ""}\nФактически: $actual${if (unit.isNotEmpty()) " $unit" else ""}"
        }
    }

    private fun finalizeServiceWriteResults() {
        refreshServiceTemplateParamResults()
        val target = serviceWriteCapacityAh
        if (target != null) {
            val remaining = data.remainingAh
            val settings = configCapacityAhFromHiLo(DALY_REMAINING_CAPACITY_HI_REG, DALY_REMAINING_CAPACITY_LO_REG)
                ?: configCapacityAhFromHiLo(DALY_NOMINAL_CAPACITY_HI_REG, DALY_NOMINAL_CAPACITY_LO_REG)
            val actual = remaining ?: settings
            val ok = remainingMatchesTarget(target) ||
                (capacityMatchesTarget(target) && runtimeSocIsFull())
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = "nominal_capacity",
                    label = "Номинальная емкость",
                    expected = target,
                    actual = actual,
                    unit = "Ah",
                    ok = ok,
                    error = if (ok) null else "not_confirmed"
                )
            )
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = "runtime_soc",
                    label = "SOC",
                    expected = SERVICE_PACK_SOC_PERCENT,
                    actual = data.soc,
                    unit = "%",
                    ok = runtimeSocIsFull(),
                    error = if (runtimeSocIsFull()) null else "not_confirmed"
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

    private fun refreshServiceTemplateParamResults() {
        val series = writeTargetSeries()
        val family = currentHardwareFamily()
        val template = activeWriteTemplate() ?: return
        for (parameter in template.parameters) {
            if (parameter.key == "series_cell_count") continue
            if (!parameter.writable) continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            val actual = templateParameterActual(parameter)
            val hasActual = templateParameterHasActual(parameter)
            val ok = hasActual && actual != null &&
                kotlin.math.abs(actual - expected) <= parameter.tolerance
            upsertServiceWriteResult(
                ServiceWriteResult(
                    key = parameter.key,
                    label = parameter.label,
                    expected = expected,
                    actual = actual,
                    unit = parameter.unit,
                    ok = ok,
                    error = if (ok) null else "not_confirmed"
                )
            )
        }
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
            put("api_key", BmsApiConfig.API_KEY)
            put("bms_uid", bmsUid())
            put("bluetooth_name", dalyBluetoothDeviceId())
            put("bluetooth_address", selectedAddress ?: "")
            put("bluetooth_id", bluetoothId())
            put("bms_sn", displayFactorySn())
            put("bms_battery_code", bmsBatteryCode())
            put("bms_hw_version", bmsHwVersionText())
            put("bms_version", displayBmsVersion())
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
            adminJsonRequest("POST", SERVICE_REPORT_PATH, body)
        }
    }

    private fun showManageScreen() {
        if (configRegisters.isEmpty() && !configReadInProgress) loadCachedConfigForCurrentBms()

        enterScreen("manage")
        currentTab = "manage"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(
            header(
                "Настройки",
                selectedDeviceName.ifBlank { selectedAddress ?: "" },
                showBack = true
            )
        )

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
        val sections = listOf("general", "voltage_current", "temperature", "balancing", "cell")
        val currentIndex = sections.indexOf(manageSection).coerceAtLeast(0)
        val nextIndex = (currentIndex + direction).coerceIn(sections.indices)
        if (nextIndex == currentIndex) return
        manageSection = sections[nextIndex]
        showManageScreen()
    }

    private fun renderManageContent() {
        if (!::manageContentLayout.isInitialized) return
        manageContentLayout.removeAllViews()

        manageContentLayout.addView(TextView(this).apply {
            text = when (manageSection) {
                "general" -> "Основные параметры"
                "voltage_current" -> "Напряжение / ток"
                "temperature" -> "Температура"
                "balancing" -> "Настройка балансировки"
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
            "balancing" -> renderManageBalancing()
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
            "cell" to "Параметры элемента",
            "balancing" to "Балансировка"
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
            "Калибровка SOC 0 и SOC 100 проверяется только на BMS R24TK."
        )
        manageContentLayout.addView(manageSectionCard(
            "1. Основные параметры",
            listOf(
                "Тип батареи" to batteryTypeText(),
                "Номинальная емкость" to nominalCapacityText(),
                "Время ожидания сна" to regText(0x0115, 0.1, "s"),
                "Настройка SOC" to fmtPct(data.soc),
                "Калибр. SOC 0" to dlUnsupportedOr(regText(DALY_SOC_CALIBRATION_0_REG, 1000.0, "V")),
                "Калибр. SOC 100" to dlUnsupportedOr(regText(DALY_SOC_CALIBRATION_100_REG, 1000.0, "V"))
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
                "Защита от повышенного общего напряжения" to settingPackVoltageOneDecimal(0x0139),
                "Защита от пониженного общего напряжения" to settingPackVoltageOneDecimal(0x013D),
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
            "Включение и отключение балансировки проверяются только на BMS R24TK."
        )
        manageContentLayout.addView(manageSectionCard(
            "4. Настройка балансировки",
            listOf(
                "Напряжение включения балансировки" to dlUnsupportedOr(regVoltageOneDecimal(0x011A)),
                "Напряжение отключения балансировки" to dlUnsupportedOr(regText(0x01FB, 1000.0, "V")),
                "Перепад напряжения при открытии балансировки" to dlUnsupportedOr(regText(0x011B, null, "mV")),
                "Ток включения балансировки" to dlUnsupportedOr(regCurrentOneDecimal(0x0151))
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
            "Калибровка SOC 0 и SOC 100 проверяется только на BMS R24TK."
        )
        manageContentLayout.addView(manageSectionCard(
            "5. Параметры элемента",
            listOf(
                "Тип батареи" to batteryTypeText(),
                "Номинальная емкость" to nominalCapacityText(),
                "Время ожидания сна" to regText(0x0115, 0.1, "s"),
                "Настройка SOC" to fmtPct(data.soc),
                "Калибр. SOC 0" to dlUnsupportedOr(regText(DALY_SOC_CALIBRATION_0_REG, 1000.0, "V")),
                "Калибр. SOC 100" to dlUnsupportedOr(regText(DALY_SOC_CALIBRATION_100_REG, 1000.0, "V"))
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
            val formatted = when (unit.lowercase(java.util.Locale.ROOT)) {
                "v" -> java.lang.String.format(java.util.Locale.US, "%.3f", v)
                "a" -> java.lang.String.format(java.util.Locale.US, "%.2f", v)
                "s" -> java.lang.String.format(java.util.Locale.US, "%.2f", v)
                else -> java.lang.String.format(java.util.Locale.US, "%.2f", v)
            }.trimTrailingZeros()
            formatted
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

    /** Отображение pack-напряжений (регистр / 10) в формате «10,0 В» без изменения raw. */
    private fun settingPackVoltageOneDecimal(addr: Int): String {
        val raw = configRegisters[addr] ?: return readOnlyUnavailable()
        val volts = raw / 10.0
        return String.format(java.util.Locale("ru", "RU"), "%.1f В", volts)
    }

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
        // Сервисные write-шаблоны (12V/24V) остаются в assets.
        // Эталон для проверки берётся только с сервера — см. activeCheckTemplate().
        if (fileName == "bms_config_template.json") {
            activeServerTemplate?.let { return it }
            throw IllegalStateException("server_template_unavailable")
        }
        val root = assets.open(fileName)
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        return parseBmsConfigTemplateJson(root)
    }

    private fun activeCheckTemplate(): BmsConfigTemplate? = activeServerTemplate

    private fun parseBmsConfigTemplateJson(root: JSONObject): BmsConfigTemplate {
        val templateId = root.optString("id").trim()
        val templateVersion = root.optInt("version", 0)
        val chemistry = root.optString("chemistry", "")
        if (templateId.isBlank() || templateVersion <= 0) {
            throw IllegalArgumentException("invalid_template_header")
        }
        val parametersJson = root.optJSONArray("parameters")
            ?: throw IllegalArgumentException("template_parameters_missing")
        if (parametersJson.length() <= 0) {
            throw IllegalArgumentException("template_parameters_empty")
        }
        val parameters = mutableListOf<BmsTemplateParameter>()
        for (i in 0 until parametersJson.length()) {
            val item = parametersJson.optJSONObject(i) ?: continue
            try {
                val key = item.optString("key").trim()
                if (key.isBlank()) {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip: empty key at index=$i")
                    continue
                }
                val registerText = item.optString("register").trim()
                if (registerText.isBlank()) {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip unsupported key=$key reason=missing_register")
                    continue
                }
                val register = try {
                    registerText.removePrefix("0x").removePrefix("0X").toInt(16)
                } catch (_: Exception) {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip unsupported key=$key reason=bad_register:$registerText")
                    continue
                }
                val expectedBySeries = mutableMapOf<Int, Double>()
                item.optJSONObject("expected_by_series")?.let { values ->
                    val keys = values.keys()
                    while (keys.hasNext()) {
                        val seriesKey = keys.next()
                        val series = seriesKey.toIntOrNull() ?: continue
                        if (!values.isNull(seriesKey)) {
                            expectedBySeries[series] = values.getDouble(seriesKey)
                        }
                    }
                }
                val enforcement = item.optString("enforcement", "required")
                if (enforcement != "required" && enforcement != "informational") {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip unsupported key=$key reason=bad_enforcement:$enforcement")
                    continue
                }
                if (!item.has("scale") || item.isNull("scale")) {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip unsupported key=$key reason=missing_scale")
                    continue
                }
                val scale = item.getDouble("scale")
                if (scale == 0.0) {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip unsupported key=$key reason=zero_scale")
                    continue
                }
                val tolerance = item.optDouble("tolerance", 0.0)
                if (tolerance < 0.0) {
                    Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE skip unsupported key=$key reason=negative_tolerance")
                    continue
                }
                val expected = if (item.has("expected") && !item.isNull("expected")) {
                    item.getDouble("expected")
                } else {
                    null
                }
                val enabled = when {
                    item.has("enabled") && !item.isNull("enabled") -> item.getBoolean("enabled")
                    item.has("check_enabled") && !item.isNull("check_enabled") -> item.getBoolean("check_enabled")
                    enforcement == "informational" -> false
                    else -> true
                }
                val writable = when {
                    item.has("writable") && !item.isNull("writable") -> item.getBoolean("writable")
                    item.has("write_enabled") && !item.isNull("write_enabled") -> item.getBoolean("write_enabled")
                    // Совместимость со старыми шаблонами без поля writable.
                    else -> key == "series_cell_count" || key in SERVICE_TEMPLATE_WRITE_ORDER
                }
                // Write-only параметры (например runtime_soc) могут не иметь expected —
                // они не участвуют в config check, но нужны для remote write.
                if (expected == null && expectedBySeries.isEmpty()) {
                    if (!(writable && !enabled)) {
                        Log.w(
                            BLE_LOG_TAG,
                            "CONFIG TEMPLATE skip key=$key reason=missing_expected writable=$writable enabled=$enabled"
                        )
                        continue
                    }
                    Log.i(BLE_LOG_TAG, "CONFIG TEMPLATE accept write-only key=$key (no expected)")
                }
                val skipFor = buildSet {
                    val skipJson = item.optJSONArray("skip_for") ?: return@buildSet
                    for (j in 0 until skipJson.length()) {
                        val family = skipJson.optString(j)
                        if (family.isNotBlank()) add(family)
                    }
                }
                parameters += BmsTemplateParameter(
                    key = key,
                    label = item.optString("label").ifBlank { key },
                    register = register,
                    scale = scale,
                    offset = item.optDouble("offset", 0.0),
                    unit = item.optString("unit", ""),
                    tolerance = tolerance,
                    enforcement = enforcement,
                    expected = expected,
                    expectedBySeries = expectedBySeries,
                    reason = item.optString("reason").takeIf { it.isNotBlank() },
                    skipFor = skipFor,
                    enabled = enabled,
                    writable = writable
                )
            } catch (e: Exception) {
                Log.w(
                    BLE_LOG_TAG,
                    "CONFIG TEMPLATE skip index=$i reason=${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
        if (parameters.isEmpty()) {
            throw IllegalArgumentException("template_parameters_unusable")
        }
        val supported = root.optJSONArray("supported_series")
        val supportedSeries = if (supported != null && supported.length() > 0) {
            (0 until supported.length()).mapNotNull {
                try {
                    supported.getInt(it)
                } catch (_: Exception) {
                    null
                }
            }.toSet()
        } else {
            setOf(4, 8)
        }
        Log.i(
            BLE_LOG_TAG,
            "CONFIG TEMPLATE parsed id=$templateId version=$templateVersion params=${parameters.size} " +
                "keys=${parameters.joinToString(",") { it.key }}"
        )
        return BmsConfigTemplate(
            id = templateId,
            version = templateVersion,
            chemistry = chemistry.ifBlank { "LiFePO4" },
            supportedSeries = supportedSeries,
            parameters = parameters,
            updatedAt = root.optLong("updated_at", 0L)
        )
    }

    private fun humanizeTemplateFetchError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return when {
            text.isBlank() -> "Не удалось получить актуальный шаблон конфигурации"
            text.equals("Failed requirement.", ignoreCase = true) ->
                "Серверный шаблон содержит параметр без эталонного значения"
            text == "invalid_template_header" -> "Сервер вернул некорректный шаблон"
            text == "template_parameters_missing" || text == "template_parameters_empty" ->
                "Сервер вернул пустой шаблон конфигурации"
            text == "template_parameters_unusable" ->
                "В шаблоне нет параметров, пригодных для проверки"
            text == "unauthorized" -> "Нет доступа к шаблону на сервере"
            text == "template_not_found" -> "Для этой BMS не найден шаблон конфигурации"
            text == "server_unreachable" -> "Не удалось получить шаблон с сервера"
            text.startsWith("HTTP ") || text.contains("timeout", ignoreCase = true) ->
                "Не удалось получить шаблон с сервера"
            else -> text.take(160)
        }
    }

    private fun fetchServerConfigTemplate(force: Boolean = false, onDone: (() -> Unit)? = null) {
        if (serverTemplateFetchInFlight && !force) {
            onDone?.invoke()
            return
        }
        serverTemplateFetchInFlight = true
        serverTemplateFetchStatus = "fetching"
        thread {
            Log.i(BLE_LOG_TAG, "CONFIG TEMPLATE REQUEST path=/api/v1/config-template")
            val json = adminJsonRequest("GET", "/api/v1/config-template", null)
            mainHandler.post {
                serverTemplateFetchInFlight = false
                try {
                    if (json?.optBoolean("ok") == true && json.has("template")) {
                        val templateObj = json.getJSONObject("template")
                        Log.i(
                            BLE_LOG_TAG,
                            "CONFIG TEMPLATE RESPONSE ok=true version=${templateObj.optInt("version")} " +
                                "params=${templateObj.optJSONArray("parameters")?.length() ?: 0}"
                        )
                        val parsed = parseBmsConfigTemplateJson(templateObj)
                        activeServerTemplate = parsed
                        serverTemplateFetchStatus = "ok"
                        serverTemplateFetchError = null
                        // Неавторитетный кэш только для диагностики (не для статуса «ok»).
                        getSharedPreferences(CONFIG_PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString("server_template_cache", templateObj.toString())
                            .putLong("server_template_cached_at", System.currentTimeMillis())
                            .apply()
                    } else {
                        activeServerTemplate = null
                        serverTemplateFetchStatus = "error"
                        val raw = json?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: json?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: if (json == null) "server_unreachable" else "template_response_invalid"
                        serverTemplateFetchError = humanizeTemplateFetchError(raw)
                        Log.w(BLE_LOG_TAG, "CONFIG TEMPLATE RESPONSE ok=false error=$raw")
                    }
                } catch (e: Exception) {
                    activeServerTemplate = null
                    serverTemplateFetchStatus = "error"
                    serverTemplateFetchError = humanizeTemplateFetchError(e.message ?: e.javaClass.simpleName)
                    Log.e(BLE_LOG_TAG, "CONFIG TEMPLATE PARSE failed: ${e.message}", e)
                }
                if (configRegisters.isNotEmpty()) {
                    setTemplateCheckResult(evaluateTemplateCheck())
                }
                if (screenState == "dashboard") updateDashboardUi()
                if (screenState == "config_check") showConfigCheckScreen()
                onDone?.invoke()
            }
        }
    }

    private fun resolvedSeriesCount(): Int? {
        data.cellCount?.takeIf { it == 4 || it == 8 }?.let { return it }
        val cells = data.cells.size
        if (cells == 4 || cells == 8) return cells
        val voltage = data.voltage ?: return null
        return when {
            voltage < 18.0 -> 4
            voltage < 36.0 -> 8
            else -> null
        }
    }

    private fun evaluateTemplateCheck(): TemplateCheckResult {
        val checkedAt = System.currentTimeMillis()
        val series = resolvedSeriesCount()
        val hardwareFamily = currentHardwareFamily()
        val template = activeCheckTemplate()
        if (template == null) {
            return TemplateCheckResult(
                templateId = "",
                templateVersion = 0,
                status = "unavailable",
                checkedAt = checkedAt,
                seriesCount = series,
                missing = listOf(
                    TemplateCheckItem(
                        key = "server_template",
                        label = "Шаблон конфигурации",
                        expected = null,
                        actual = null,
                        unit = "",
                        tolerance = 0.0,
                        reason = serverTemplateFetchError ?: "server_template_unavailable",
                        status = "missing"
                    )
                ),
                items = emptyList(),
                hardwareFamily = hardwareFamily
            )
        }
        return try {
            val mismatches = mutableListOf<TemplateCheckItem>()
            val missing = mutableListOf<TemplateCheckItem>()
            val unverified = mutableListOf<TemplateCheckItem>()
            val items = mutableListOf<TemplateCheckItem>()

            for (parameter in template.parameters) {
                val expected = parameter.expected ?: series?.let { parameter.expectedBySeries[it] }
                val actual = templateParameterActual(parameter)
                val hasActual = templateParameterHasActual(parameter)

                if (!parameter.enabled || parameter.enforcement == "informational") {
                    // В админке «Не проверять» — не показываем в конфигурации шаблона.
                    continue
                }

                if (shouldSkipTemplateParameter(parameter, hardwareFamily)) {
                    items += TemplateCheckItem(
                        parameter.key,
                        parameter.label,
                        expected,
                        if (hasActual) actual else null,
                        parameter.unit,
                        parameter.tolerance,
                        "skipped_for_hardware",
                        status = "skipped"
                    )
                    continue
                }

                val item = when {
                    parameter.expectedBySeries.isNotEmpty() && series == null -> {
                        TemplateCheckItem(
                            parameter.key,
                            parameter.label,
                            null,
                            null,
                            parameter.unit,
                            parameter.tolerance,
                            "unsupported_series",
                            status = "missing"
                        ).also { missing += it }
                    }
                    !hasActual -> {
                        TemplateCheckItem(
                            parameter.key,
                            parameter.label,
                            expected,
                            null,
                            parameter.unit,
                            parameter.tolerance,
                            "register_missing",
                            status = "missing"
                        ).also { missing += it }
                    }
                    expected == null -> {
                        TemplateCheckItem(
                            parameter.key,
                            parameter.label,
                            null,
                            null,
                            parameter.unit,
                            parameter.tolerance,
                            "expected_value_missing",
                            status = "missing"
                        ).also { missing += it }
                    }
                    kotlin.math.abs(actual!! - expected) > parameter.tolerance -> {
                        TemplateCheckItem(
                            parameter.key,
                            parameter.label,
                            expected,
                            actual,
                            parameter.unit,
                            parameter.tolerance,
                            status = "mismatch"
                        ).also { mismatches += it }
                    }
                    else -> {
                        TemplateCheckItem(
                            parameter.key,
                            parameter.label,
                            expected,
                            actual,
                            parameter.unit,
                            parameter.tolerance,
                            status = "ok"
                        )
                    }
                }
                items += item
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
                series,
                mismatches,
                missing,
                unverified,
                hardwareFamily,
                items,
                template.updatedAt
            )
        } catch (e: Exception) {
            TemplateCheckResult(
                templateId = template.id,
                templateVersion = template.version,
                status = "incomplete",
                checkedAt = checkedAt,
                seriesCount = series,
                missing = listOf(
                    TemplateCheckItem(
                        key = "template_load_error",
                        label = "Шаблон конфигурации",
                        expected = null,
                        actual = null,
                        unit = "",
                        tolerance = 0.0,
                        reason = "template_load_error: ${(e.message ?: e.javaClass.simpleName).take(160)}",
                        status = "missing"
                    )
                ),
                hardwareFamily = hardwareFamily,
                templateUpdatedAt = template.updatedAt
            )
        }
    }

    private fun setTemplateCheckResult(result: TemplateCheckResult) {
        latestTemplateCheck = result
        templateChecksByBms[bmsUid()] = result
    }

    private fun setTemplateCheckChecking() {
        val tpl = activeServerTemplate
        setTemplateCheckResult(
            TemplateCheckResult(
                templateId = tpl?.id ?: "",
                templateVersion = tpl?.version ?: 0,
                status = "checking",
                checkedAt = 0L,
                seriesCount = data.cellCount,
                hardwareFamily = currentHardwareFamily(),
                templateUpdatedAt = tpl?.updatedAt ?: 0L
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
            "incomplete", "unavailable" -> Color.rgb(224, 150, 0)
            else -> Color.rgb(110, 118, 128)
        }
        val title = when (status) {
            "checking" -> "Проверка конфигурации…"
            "ok" -> "Конфигурация соответствует шаблону"
            "mismatch" -> "Есть отклонения конфигурации"
            "incomplete" -> "Проверка конфигурации неполная"
            "unavailable" -> "Проверка конфигурации недоступна"
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

    private fun showConfigCheckScreen() {
        enterScreen("config_check")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Конфигурация BMS",
                selectedDeviceName.ifBlank { selectedAddress ?: "" },
                showBack = true
            )
        )
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        val result = currentTemplateCheck()
        val template = activeServerTemplate
        val meta = buildString {
            append("Версия шаблона: ")
            append(template?.version?.toString() ?: result?.templateVersion?.takeIf { it > 0 }?.toString() ?: "—")
            append("\nОбновлён: ")
            val updated = (template?.updatedAt ?: result?.templateUpdatedAt ?: 0L).takeIf { it > 0 }
            append(
                updated?.let {
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                } ?: "—"
            )
            append("\nПроверено: ")
            append(
                result?.checkedAt?.takeIf { it > 0 }?.let {
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                } ?: "—"
            )
        }
        content.addView(TextView(this).apply {
            text = meta
            textSize = 13f
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(600)
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        val writableKeys = activeServerTemplate?.parameters
            ?.filter { it.writable && it.enabled }
            ?.map { it.key }
            ?.toSet()
            .orEmpty()
        val writableMismatches = (result?.items
            ?.filter { it.status == "mismatch" || it.status == "missing" }
            .orEmpty()
            .ifEmpty { result?.mismatches.orEmpty() + result?.missing.orEmpty() })
            .filter { writableKeys.isEmpty() || it.key in writableKeys }
        val canApply = !isServiceApp() &&
            activeServerTemplate != null &&
            result?.status in setOf("mismatch", "incomplete") &&
            writableMismatches.isNotEmpty() &&
            bluetoothGatt != null &&
            !serviceWriteActive

        if (serviceWriteActive && clientTemplateApplyMode) {
            content.addView(TextView(this).apply {
                text = "Идёт запись параметров: $serviceWriteDone / $serviceWriteTotal"
                textSize = 14f
                typeface = interFont(700)
                setTextColor(Color.rgb(16, 17, 20))
                setPadding(dp(4), 0, dp(4), dp(10))
            })
        } else if (canApply) {
            content.addView(TextView(this).apply {
                text = "Настроить BMS"
                textSize = 16f
                typeface = interFont(760)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = round(red, dp(14), Color.TRANSPARENT, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { startClientTemplateApply() }
            }, marginLp(-1, -2, 0, 0, 0, 12))
            content.addView(TextView(this).apply {
                text = "Будут записаны только отличающиеся параметры шаблона. После каждой записи выполняется повторное чтение."
                textSize = 12f
                setTextColor(Color.rgb(111, 119, 129))
                setPadding(dp(4), 0, dp(4), dp(12))
            })
        }

        when {
            result?.status == "unavailable" ||
                (serverTemplateFetchStatus == "error" && template == null) -> {
                content.addView(
                    manageSectionCard(
                        "Статус",
                        listOf(
                            "Состояние" to (serverTemplateFetchError
                                ?: "Не удалось получить актуальный шаблон конфигурации")
                        )
                    ),
                    marginLp(-1, -2, 0, 0, 0, 12)
                )
            }
            result == null || result.status == "checking" -> {
                content.addView(
                    manageSectionCard(
                        "Статус",
                        listOf("Состояние" to "Проверка выполняется…")
                    ),
                    marginLp(-1, -2, 0, 0, 0, 12)
                )
            }
            else -> {
                val rows = (if (result.items.isNotEmpty()) {
                    result.items
                } else {
                    result.mismatches.map { it.copy(status = "mismatch") } +
                        result.missing.map { it.copy(status = "missing") }
                }).filter { it.status != "disabled" }
                for (item in rows) {
                    content.addView(
                        configCheckDetailCard(item),
                        marginLp(-1, -2, 0, 0, 0, 10)
                    )
                }
                if (rows.isEmpty()) {
                    content.addView(
                        manageSectionCard(
                            "Статус",
                            listOf("Состояние" to "Нет параметров для отображения")
                        ),
                        marginLp(-1, -2, 0, 0, 0, 12)
                    )
                }
            }
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun configCheckDetailCard(item: TemplateCheckItem): LinearLayout {
        val (mark, color, statusText) = when (item.status) {
            "ok" -> Triple("✓", Color.rgb(31, 179, 90), "соответствует")
            "mismatch" -> Triple("✗", Color.rgb(211, 47, 47), "не соответствует")
            "missing" -> Triple("?", Color.rgb(224, 150, 0), "не удалось прочитать")
            "skipped" -> Triple("—", Color.rgb(111, 119, 129), "не применяется")
            "disabled" -> Triple("—", Color.rgb(111, 119, 129), "проверка отключена")
            else -> Triple("•", Color.rgb(111, 119, 129), item.status)
        }
        val unit = item.unit.trim()
        fun fmt(v: Double?): String {
            if (v == null) return "—"
            val num = formatTemplateNumber(v)
            return if (unit.isBlank()) num else "$num $unit"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            addView(TextView(this@MainActivity).apply {
                text = "$mark  ${item.label}"
                textSize = 15f
                typeface = interFont(720)
                setTextColor(Color.rgb(16, 17, 20))
            })
            addView(TextView(this@MainActivity).apply {
                text = statusText
                textSize = 12f
                typeface = interFont(650)
                setTextColor(color)
                setPadding(0, dp(2), 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Ожидалось: ${fmt(item.expected)}\nФактически: ${fmt(item.actual)}"
                textSize = 13f
                setTextColor(Color.rgb(75, 79, 84))
            })
        }
    }

    private fun String.trimTrailingZeros(): String {
        return this.replace(Regex("0+$"), "").replace(Regex("[.,]$"), "")
    }

    private fun batteryTypeText(): String {
        val raw = configRegisters[0x0100] ?: configRegisters[0x0113]
        return when (raw) {
            null -> "LiFePO4"
            0, 1 -> "LiFePO4"
            2 -> "Li-ion"
            3 -> "LTO"
            else -> "LiFePO4"
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
        enterScreen("support")
        currentTab = "support"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(
            header(
                "Поддержка",
                selectedDeviceName.ifBlank { selectedAddress ?: "" },
                showBack = true
            )
        )

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
            text = "ЛИФЕРЫЧ"
            textSize = 16f
            typeface = interFont(760)
            setTextColor(Color.rgb(16, 17, 20))
        })
        contactCard.addView(TextView(this).apply {
            text = "+7 (932) 078-10-11"
            textSize = 16f
            typeface = interFont(720)
            setTextColor(redDark)
            setPadding(0, dp(8), 0, dp(4))
            isClickable = true
            isFocusable = true
            contentDescription = "Позвонить в поддержку"
            setOnClickListener { dialPhone("+79320781011") }
        })
        listOf(
            "Telegram" to "https://t.me/liferych",
            "MAX" to "https://max.ru/u/f9LHodD0cOIwPdSddb5TLuiLTMRCIkIpTzgUzr_f2iEj89DXpt_Mh2zXvcc",
            "ВКонтакте" to "https://vk.ru/liferych"
        ).forEach { (title, url) ->
            contactCard.addView(TextView(this).apply {
                text = title
                textSize = 15f
                typeface = interFont(700)
                setTextColor(Color.rgb(25, 118, 210))
                setPadding(0, dp(6), 0, 0)
                isClickable = true
                isFocusable = true
                contentDescription = "Открыть $title"
                setOnClickListener { openExternalUrl(url) }
            })
        }
        contactCard.addView(TextView(this).apply {
            text = "График: пн–пт 09:00–18:00"
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, dp(10), 0, 0)
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
        enterScreen("auth")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header("Вход", showBack = true))
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
        if (isServiceApp()) {
            return normalizeAdminServerBaseUrl(BmsApiConfig.BASE_URL)
        }
        val saved = serverPrefs.getString("base_url", BmsApiConfig.BASE_URL).orEmpty()
        val normalized = normalizeAdminServerBaseUrl(saved)
        if (normalized != saved.trim().trimEnd('/')) {
            serverPrefs.edit().putString("base_url", normalized).apply()
        }
        return normalized
    }

    private fun saveAdminServerBaseUrl(input: String) {
        if (isServiceApp()) return
        serverPrefs.edit()
            .putString("base_url", normalizeAdminServerBaseUrl(input))
            .apply()
    }

    private fun adminServerUrl(path: String): String {
        return adminServerBaseUrl().trimEnd('/') + path
    }

    private fun applyBmsApiAuth(conn: HttpURLConnection) {
        conn.setRequestProperty("x-api-key", BmsApiConfig.API_KEY)
    }

    private fun putBmsApiKey(body: JSONObject) {
        body.put("api_key", BmsApiConfig.API_KEY)
    }

    private fun isLegacyLocalAdminServerUrl(value: String): Boolean {
        val normalized = value.trim().trimEnd('/').lowercase()
        return normalized.isBlank() ||
            normalized == "http://192.168.70.142:3000" ||
            normalized == "http://localhost:3000" ||
            normalized == "http://127.0.0.1:3000"
    }

    private fun normalizeAdminServerBaseUrl(input: String): String {
        var value = input.trim()
        if (value.isBlank() || isLegacyLocalAdminServerUrl(value)) {
            value = BmsApiConfig.BASE_URL
        }
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
        enterScreen("profile")
        currentTab = "profile"
        val prefs = userProfilePrefs()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header("Профиль", showBack = true))
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
        val name = field("ФИО *", prefs.getString("name", "") ?: "", "Иванов Иван Иванович")
        val savedPhone = prefs.getString("phone", "")?.trim().orEmpty()
        val phone = field(
            "Номер телефона *",
            if (savedPhone.isBlank()) "+7" else formatRuPhoneMask(savedPhone),
            "+7 (___) ___-__-__"
        )
        attachRuPhoneMask(phone)
        val email = field("Email", prefs.getString("email", "") ?: "", "name@example.ru")
        val birth = field("Дата рождения", prefs.getString("birth", "") ?: "", "ДД.ММ.ГГГГ")
        profileCard.addView(TextView(this).apply {
            text = "Поля ФИО и телефон обязательны для добавления АКБ."
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
                val nameText = name.text.toString().trim()
                val phoneText = formatRuPhoneMask(phone.text.toString())
                if (nameText.isBlank()) {
                    toast("Укажите ФИО")
                    return@setOnClickListener
                }
                if (!isValidRuPhone(phoneText)) {
                    toast("Укажите полный номер телефона")
                    return@setOnClickListener
                }
                prefs.edit()
                    .putString("name", nameText)
                    .putString("phone", phoneText)
                    .putString("email", email.text.toString().trim())
                    .putString("birth", birth.text.toString().trim())
                    .apply()
                phone.setText(phoneText)
                toast("Профиль сохранён")
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("profile"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showServiceProfileScreen() {
        enterScreen("profile")
        currentTab = "profile"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header("Профиль сборщика", showBack = true))
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
            text = "Имя сборщика нужно только при записи шаблона 12В/24В. Телеметрия на сервер уходит и без имени."
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
                toast(
                    if (assembler.isBlank()) {
                        "Имя сборщика нужно только для записи шаблона."
                    } else {
                        "Профиль сборщика сохранён"
                    }
                )
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))
        content.addView(TextView(this).apply {
            text = "Версия ${BuildConfig.VERSION_NAME}"
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = interFont(600)
            setTextColor(Color.rgb(111, 119, 129))
        }, marginLp(-1, -2, 0, 16, 0, 0))
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
            val comment = item.optString("admin_comment").trim()
            text = buildString {
                append("Статус: ${warrantyStatusRu(status)}\n")
                append("Дата: ${item.optString("created_at")}\n")
                append("BMS: ${item.optString("bms_uid")}\n")
                append("Модель: ${item.optString("model")}\n")
                append("Проблема: ${item.optString("problem").take(120)}")
                if (comment.isNotBlank()) append("\nОтвет: ${comment.take(160)}")
            }
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
        val defaultFio = existing?.optString("fio")?.takeIf { it.isNotBlank() } ?: profileFullName()
        warrantyNameEdit?.setText(defaultFio)
        formCard.addView(warrantyNameEdit)

        warrantyPhoneEdit = supportInput("Телефон", "+7 (___) ___-__-__")
        val defaultPhone = existing?.optString("phone")?.takeIf { it.isNotBlank() }
            ?: formatRuPhoneMask(profilePhoneStored())
        warrantyPhoneEdit?.setText(defaultPhone)
        warrantyPhoneEdit?.let { attachRuPhoneMask(it) }
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
                    putBmsApiKey(this)
                    put("bms_uid", uid)
                    put("all_by_bms", true)
                }
                Log.i(
                    BLE_LOG_TAG,
                    "SUPPORT LIST REQUEST url=${adminServerUrl(WARRANTY_LIST_PATH)} " +
                        "auth=${!BmsApiConfig.API_KEY.isNullOrBlank()} bms_uid=$uid"
                )
                val conn = (URL(adminServerUrl(WARRANTY_LIST_PATH)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyBmsApiAuth(this)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val response = try {
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                conn.disconnect()
                Log.i(
                    BLE_LOG_TAG,
                    "SUPPORT LIST RESPONSE http=$code body=${response.take(300)}"
                )

                val obj = try {
                    JSONObject(response)
                } catch (_: Exception) {
                    null
                }
                if (code in 200..299 && obj?.optBoolean("ok") == true) {
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
                    val err = obj?.optString("error").orEmpty()
                    Log.w(BLE_LOG_TAG, "SUPPORT LIST FAILED http=$code error=$err")
                    runOnUiThread {
                        if (showToast) {
                            toast(
                                when {
                                    code == 401 || err == "unauthorized" ->
                                        "Не удалось обновить статусы: нет доступа к серверу"
                                    code == 0 || response.isBlank() ->
                                        "Не удалось связаться с сервером. Проверьте подключение к интернету."
                                    else ->
                                        "Не удалось обновить статусы обращений. Попробуйте ещё раз."
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(BLE_LOG_TAG, "SUPPORT LIST EXCEPTION: ${e.message}")
                runOnUiThread {
                    if (showToast) {
                        toast("Не удалось связаться с сервером. Проверьте подключение к интернету.")
                    }
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

        if (fio.isBlank() || !isValidRuPhone(phone) || problem.isBlank()) {
            toast("Заполните ФИО, полный телефон и описание проблемы")
            return
        }

        val localId = editingWarrantyLocalId ?: "local_${System.currentTimeMillis()}"
        val existing = warrantyRequestByLocalId(localId)

        val payload = JSONObject()
        putBmsApiKey(payload)
        payload.put("client_fio", fio)
        payload.put("client_phone", phone)
        payload.put("battery_model", model)
        payload.put("problem_text", problem)
        payload.put("bms_uid", bmsUid())
        payload.put("bluetooth_name", dalyBluetoothDeviceId())
        payload.put("bluetooth_address", selectedAddress ?: "")
        val sn = bmsSn()
        if (sn.isNotBlank()) payload.put("bms_sn", sn)
        payload.put("app_version", APP_VERSION)
        payload.put("battery_snapshot", buildUploadJson())
        if (configRegisters.isNotEmpty()) payload.put("config_snapshot", buildConfigUploadJson())
        if (warrantyMediaUris.isNotEmpty()) {
            val media = JSONArray()
            warrantyMediaUris.take(5).forEachIndexed { idx, uri ->
                media.put(
                    JSONObject().apply {
                        put("filename", warrantyFileName(uri, idx))
                        put("mime", contentResolver.getType(uri) ?: "application/octet-stream")
                    }
                )
            }
            payload.put("media", media)
        }

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
                    // Без toast «Успешно…» — статус виден на экране.
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
        // Единый auth с телеметрией: x-api-key + api_key из BmsApiConfig.
        // JSON вместо старого multipart — новый backend принимает application/json.
        return try {
            putBmsApiKey(payload)
            val url = adminServerUrl(WARRANTY_SUBMIT_PATH)
            Log.i(
                BLE_LOG_TAG,
                "SUPPORT CREATE REQUEST url=$url method=POST " +
                    "auth=${!BmsApiConfig.API_KEY.isNullOrBlank()} " +
                    "fio_len=${payload.optString("client_fio").length} " +
                    "phone_len=${payload.optString("client_phone").length} " +
                    "media=${files.size}"
            )
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                applyBmsApiAuth(this)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val response = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            } catch (_: Exception) {
                ""
            }
            conn.disconnect()
            Log.i(BLE_LOG_TAG, "SUPPORT CREATE RESPONSE http=$code body=${response.take(300)}")

            val obj = try {
                JSONObject(response)
            } catch (_: Exception) {
                null
            }
            val err = obj?.optString("error").orEmpty()
            when {
                code in 200..299 && obj?.optBoolean("ok") == true -> {
                    val id = obj.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: Regex("\"id\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.getOrNull(1)
                    "Обращение отправлено${id?.let { " №$it" } ?: ""}"
                }
                code == 401 || err == "unauthorized" ->
                    "Не удалось отправить обращение: нет доступа к серверу"
                code == 0 || response.isBlank() ->
                    "Не удалось связаться с сервером. Проверьте подключение к интернету."
                else ->
                    "Не удалось отправить обращение. Попробуйте ещё раз."
            }
        } catch (e: Exception) {
            Log.w(BLE_LOG_TAG, "SUPPORT CREATE EXCEPTION: ${e.message}")
            "Не удалось связаться с сервером. Проверьте подключение к интернету."
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
        enterScreen("journal")
        currentTab = "journal"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        root.addView(
            header(
                "Журнал",
                selectedDeviceName.ifBlank { selectedAddress ?: "" },
                showBack = true
            )
        )

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
        val count = when {
            data.cellCount != null && data.cellCount!! > 0 -> data.cellCount!!
            data.cells.isNotEmpty() -> data.cells.keys.maxOrNull() ?: data.cells.size
            else -> 0
        }.coerceIn(0, 24)
        if (count == 0) {
            cellsLayout.addView(TextView(this).apply {
                text = "Нет данных по ячейкам"
                textSize = 13f
                setTextColor(Color.rgb(111, 119, 129))
            })
            return
        }
        // 4S — в одну строку; больше — как в старом UI (2 или 4 колонки)
        val columns = when {
            count <= 4 -> count
            count <= 8 -> 2
            else -> 4
        }.coerceAtLeast(1)
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
                minimumHeight = if (count <= 8) dp(88) else dp(76)
                background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)
            }
            box.addView(TextView(this).apply {
                text = "Ячейка $i"
                textSize = if (count <= 8) 11f else 10f
                typeface = interFont(600)
                setTextColor(Color.rgb(122, 132, 144))
            })
            box.addView(TextView(this).apply {
                text = v?.let { "%.3f В".format(it) } ?: "--"
                textSize = if (count <= 8) 16f else 13f
                typeface = interFont(760)
                setTextColor(Color.rgb(16, 17, 20))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            box.addView(ProgressBar(
                this,
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                max = 3650
                progress = ((v ?: 0.0) * 1000).toInt().coerceIn(0, 3650)
                progressTintList =
                    android.content.res.ColorStateList.valueOf(Color.rgb(31, 179, 90))
                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.rgb(223, 229, 235))
            }, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(12) })
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
            if (screenState == "loading") showBatteriesScreen(asRootHome = true)
            if (::statusText.isInitialized) {
                statusText.text = "BLE scanner недоступен. Проверь Bluetooth."
            }
            return
        }

        scanner.startScan(scanCallback)

        mainHandler.postDelayed({
            try { scanner.stopScan(scanCallback) } catch (_: Exception) {}
            if (screenState == "loading") showBatteriesScreen(asRootHome = true)
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
        resetRemoteWriteState()
        qtcDbJobToken++
        if (isServiceApp()) {
            qtcDbStatus = QtcDbStatus.WAITING_BMS
            qtcDbError = ""
        }
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
                resetConnectionEventState()
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
                    if (screenState == "loading") {
                        showBatteriesScreen(asRootHome = true)
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
                    showBatteriesScreen(asRootHome = true)
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
                    showBatteriesScreen(asRootHome = true)
                } else {
                    showDashboardScreen(asRootHome = true)
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
        val gatt = bluetoothGatt ?: return
        val ch = writeCharacteristic ?: return
        runtimeCommands = buildList {
            add(DALY_HW_VERSION_CMD)
            add(DALY_BATTERY_CODE_CMD)
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
                val previousCount = data.cellCount
                data.cellCount = p[0].toInt() and 0xFF
                data.tempCount = p[1].toInt() and 0xFF
                data.chargerConnected = (p[2].toInt() and 0xFF) != 0
                data.loadConnected = (p[3].toInt() and 0xFF) != 0
                data.cycles = u16(p, 5)
                pruneCellsToCount()
                val check = currentTemplateCheck()
                if (
                    previousCount != data.cellCount &&
                    configRegisters.isNotEmpty() &&
                    (check?.seriesCount == null ||
                        check.missing.any {
                            it.reason == "unsupported_series" || it.key == "series_cell_count"
                        })
                ) {
                    setTemplateCheckResult(evaluateTemplateCheck())
                    uploadConfigSnapshot(force = true)
                }
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
        socProgress?.progress = data.soc?.toInt() ?: 0
        voltageValue.text = data.voltage?.let { "%.2f В".format(it) } ?: "-- В"
        currentValue.text = data.current?.let { "%.1f А".format(it) } ?: "-- А"
        currentSubValue?.let { sub ->
            val label = currentDirectionLabel(data.current)
            sub.text = label
            sub.visibility = View.VISIBLE
        }

        val fullAh = data.estimatedFullAh
        fullCapacityValue?.text = fullAh?.let {
            if (kotlin.math.abs(it - it.toLong()) < 0.05) "%.0f А·ч".format(it) else "%.1f А·ч".format(it)
        } ?: "-- А·ч"
        remainingValue.text = data.remainingAh?.let {
            if (kotlin.math.abs(it - it.toLong()) < 0.05) "%.0f А·ч".format(it) else "%.1f А·ч".format(it)
        } ?: "-- А·ч"

        val (socLabel, socColor) = socStatusLabel(data.soc)
        socStatusText?.let {
            it.text = socLabel
            it.setTextColor(socColor)
        }

        fun applyStatusDot(dot: View?, ok: Boolean?, warn: Boolean = false) {
            when {
                ok == true -> dot?.background = round(Color.rgb(45, 176, 69), dp(5), Color.TRANSPARENT, 0)
                warn -> dot?.background = round(Color.rgb(215, 160, 35), dp(5), Color.TRANSPARENT, 0)
                ok == false -> dot?.background = round(Color.rgb(210, 70, 70), dp(5), Color.TRANSPARENT, 0)
                else -> dot?.background = round(Color.rgb(180, 186, 194), dp(5), Color.TRANSPARENT, 0)
            }
        }

        fun applyMos(valueView: TextView, dot: View?, on: Boolean?) {
            when (on) {
                true -> {
                    valueView.text = "ВКЛ"
                    valueView.setTextColor(Color.rgb(31, 179, 90))
                    applyStatusDot(dot, true)
                }
                false -> {
                    valueView.text = "ВЫКЛ"
                    valueView.setTextColor(Color.rgb(111, 119, 129))
                    applyStatusDot(dot, null)
                }
                null -> {
                    valueView.text = "—"
                    valueView.setTextColor(Color.rgb(111, 119, 129))
                    applyStatusDot(dot, null)
                }
            }
        }
        if (::chargeMosValue.isInitialized) applyMos(chargeMosValue, chargeMosDot, data.chargeMos)
        if (::dischargeMosValue.isInitialized) applyMos(dischargeMosValue, dischargeMosDot, data.dischargeMos)

        if (hasTemperatureSensorError()) {
            t1Text.text = "Нет датчика"
            t2Text.text = "Нет датчика"
        } else {
            val t1 = data.temps[1] ?: data.minTemp
            val t2 = data.temps[2] ?: data.maxTemp
            val shown = t2 ?: t1
            t1Text.text = shown?.let { "$it °C" } ?: "-- °C"
            t2Text.text = "${t2?.toString() ?: "--"} °C"
        }

        cellCountValue?.text = (data.cellCount ?: data.cells.size.takeIf { it > 0 })?.toString() ?: "--"

        if (::balanceValue.isInitialized) {
            if (data.errors.isNotEmpty()) {
                balanceValue.text = "Ошибка"
                balanceValue.setTextColor(Color.rgb(239, 83, 80))
                applyStatusDot(balanceDot, false)
            } else {
                balanceValue.text = "Норма"
                balanceValue.setTextColor(Color.rgb(31, 179, 90))
                applyStatusDot(balanceDot, true)
            }
        }
        stateValue?.let { state ->
            when {
                data.errors.isNotEmpty() -> {
                    state.text = "Защита"
                    state.setTextColor(Color.rgb(239, 83, 80))
                    applyStatusDot(stateDot, false)
                }
                data.balancingCells.isNotEmpty() -> {
                    state.text = "Балансировка"
                    state.setTextColor(Color.rgb(255, 153, 0))
                    applyStatusDot(stateDot, null, warn = true)
                }
                polling || bluetoothGatt != null -> {
                    state.text = "Работает"
                    state.setTextColor(Color.rgb(31, 179, 90))
                    applyStatusDot(stateDot, true)
                }
                else -> {
                    state.text = "Нет связи"
                    state.setTextColor(Color.rgb(111, 119, 129))
                    applyStatusDot(stateDot, null)
                }
            }
        }

        val device = selectedDeviceName.ifBlank { selectedAddress ?: "не выбрано" }
        if (::deviceNameValue.isInitialized) {
            deviceNameValue.text = device
            fitTextToWidth(deviceNameValue, 6f, 13f)
        }
        bluetoothIdValue?.text = bluetoothId().ifBlank { "--" }
        bmsSnValue?.let { sn ->
            sn.text = displayFactorySn().ifBlank { "--" }
            fitTextToWidth(sn, 6f, 13f)
        }
        bmsVersionValue?.let { ver ->
            ver.text = displayBmsVersion().ifBlank { "--" }
            fitTextToWidth(ver, 6f, 13f)
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

        cellDiffHeaderValue?.text = data.cellDiffV?.let { "Разбег: %.0f мВ".format(it * 1000.0) }.orEmpty()

        overallStatusBanner?.let { banner ->
            val title = overallStatusTitle
            val sub = overallStatusSub
            val check = currentTemplateCheck()
            when {
                data.errors.isNotEmpty() -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "!  Требуется внимание"
                    title?.setTextColor(Color.rgb(239, 83, 80))
                    sub?.text = data.errors.joinToString(", ")
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "unavailable" ||
                    (serverTemplateFetchStatus == "error" && activeServerTemplate == null) -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "⚠  Проверка конфигурации недоступна"
                    title?.setTextColor(Color.rgb(224, 150, 0))
                    sub?.text = serverTemplateFetchError
                        ?: "Не удалось получить актуальный шаблон с сервера"
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "checking" || serverTemplateFetchStatus == "fetching" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "…  Проверка конфигурации"
                    title?.setTextColor(Color.rgb(111, 119, 129))
                    sub?.text = "Сверяем параметры BMS с серверным шаблоном"
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "mismatch" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "⚠  Есть отклонения конфигурации"
                    title?.setTextColor(Color.rgb(224, 150, 0))
                    val count = check.mismatches.size
                    sub?.text = if (count == 1) {
                        "1 параметр требует проверки"
                    } else {
                        "$count параметра требуют проверки"
                    }
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "incomplete" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "⚠  Проверка конфигурации неполная"
                    title?.setTextColor(Color.rgb(224, 150, 0))
                    sub?.text = "Не все параметры удалось прочитать или сравнить"
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "ok" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "✓  Батарея в норме"
                    title?.setTextColor(Color.rgb(31, 179, 90))
                    sub?.text = "Все параметры соответствуют"
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                else -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "✓  Батарея в норме"
                    title?.setTextColor(Color.rgb(31, 179, 90))
                    sub?.text = "Ожидается проверка конфигурации"
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
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
        val forceFirst = pendingFirstTelemetryUpload
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

    private fun displayBmsVersion(): String {
        return bmsHwVersionText().ifBlank { bmsHardwareVersion() }
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
        val fromHw = parseBmsHardwareVersion(bmsHwVersionText())
        if (fromHw.isNotBlank()) return fromHw
        val fromSn = versionFromFactorySn()
        if (fromSn.isNotBlank()) return fromSn
        val fromCode = parseBmsHardwareVersion(bmsBatteryCode())
        if (fromCode.isNotBlank()) return fromCode
        val address = selectedAddress ?: return ""
        return identityPrefs().getString("ver_$address", "")?.trim().orEmpty()
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
        if (parseBmsHardwareVersion(bmsHwVersionText()).isBlank()) {
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
        if (parseBmsHardwareVersion(bmsHwVersionText()).isBlank()) {
            val version = parseBmsHardwareVersion(code)
            if (version.isNotBlank()) editor.putString("ver_$address", version)
        }
        editor.apply()
    }

    private fun rememberHwVersion() {
        val hw = liveHwVersion()
        val address = selectedAddress
        if (hw.isBlank() || address.isNullOrBlank()) return
        val editor = identityPrefs().edit().putString("hw_$address", hw)
        val version = parseBmsHardwareVersion(hw)
        if (version.isNotBlank()) editor.putString("ver_$address", version)
        editor.apply()
    }

    private fun factorySerialFromRegisters(): String {
        val sn = factorySerialRaw()
        return if (isValidBmsSn(sn)) sn else ""
    }

    private fun factorySerialRaw(): String {
        if (configRegisters.isEmpty()) return ""
        return factorySerialCandidates().firstOrNull { parseBmsHardwareVersion(it).isNotBlank() }
            ?: factorySerialCandidates().firstOrNull().orEmpty()
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

    private fun capacityMatchesTarget(
        targetAh: Double,
        tolerance: Double = SERVICE_CAPACITY_TOLERANCE_AH
    ): Boolean {
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

    private fun remainingMatchesTarget(targetAh: Double): Boolean {
        val rem = data.remainingAh ?: return false
        return kotlin.math.abs(rem - targetAh) <= SERVICE_CAPACITY_TOLERANCE_AH
    }

    private fun runtimeSocIsFull(): Boolean {
        val soc = data.soc ?: return false
        return soc >= SERVICE_PACK_SOC_PERCENT - SERVICE_SOC_TOLERANCE_PERCENT
    }

    private fun packGaugeAlreadyProgrammed(targetAh: Double): Boolean {
        return remainingMatchesTarget(targetAh) && runtimeSocIsFull()
    }

    private fun capacityAlreadyMatches(targetAh: Double): Boolean {
        fun close(v: Double?) = v != null && kotlin.math.abs(v - targetAh) <= SERVICE_CAPACITY_TOLERANCE_AH
        return close(configCapacityAhFromHiLo(DALY_REMAINING_CAPACITY_HI_REG, DALY_REMAINING_CAPACITY_LO_REG)) &&
            close(configCapacityAhFromHiLo(DALY_NOMINAL_CAPACITY_HI_REG, DALY_NOMINAL_CAPACITY_LO_REG))
    }

    private fun remoteWriteAlreadyMatches(command: RemoteWriteCommand): Boolean {
        if (command.key == "series_cell_count") {
            return data.cellCount == command.rawValue
        }
        if (command.key == "nominal_capacity") {
            val target = command.displayValue ?: serviceWriteCapacityAh ?: return false
            return packGaugeAlreadyProgrammed(target) && capacityAlreadyMatches(target)
        }
    if (command.key == "runtime_soc") {
            if (command.localOnly) {
                val target = serviceWriteCapacityAh ?: return false
                return packGaugeAlreadyProgrammed(target)
            }
            val live = data.soc
            if (live != null && kotlin.math.abs(live - command.value) <= 1.0) return true
            val raw = configRegisters[command.register] ?: return false
            if (raw == command.rawValue) return true
            val actual = (raw.toDouble() / command.scale) + command.offset
            return kotlin.math.abs(actual - command.value) <= 1.0
        }
        val raw = configRegisters[command.register] ?: return false
        if (raw == command.rawValue) return true
        val actual = (raw.toDouble() / command.scale) + command.offset
        return kotlin.math.abs(actual - command.value) < 0.005
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

    private fun isR24Hardware(): Boolean {
        val hw = bmsHwVersionText()
        if (hw.isNotBlank()) return hw.contains("R24", ignoreCase = true)
        return bmsHardwareVersion().contains("R24", ignoreCase = true)
    }

    private fun skipsLimitedTemplateParams(): Boolean = !isR24Hardware()

    private fun currentHardwareFamily(): String {
        return if (isR24Hardware()) HARDWARE_FAMILY_STANDARD else HARDWARE_FAMILY_R10K
    }

    private fun shouldSkipTemplateParameter(parameter: BmsTemplateParameter, hardwareFamily: String): Boolean {
        if (skipsLimitedTemplateParams() && parameter.key in R10K_SKIPPED_TEMPLATE_KEYS) return true
        return hardwareFamily in parameter.skipFor
    }

    private fun dlUnsupportedOr(value: String): String {
        return if (skipsLimitedTemplateParams()) "не настраивается" else value
    }

    private fun addTk10NoticeIfNeeded(text: String) {
        if (!skipsLimitedTemplateParams() || !::manageContentLayout.isInitialized) return
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
        obj.put("bms_version", displayBmsVersion())
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

    private fun resetConnectionEventState() {
        localEvents.clear()
        localEventDetails.clear()
        dalyHistoricalEvents.clear()
        historicalRawResponses.clear()
        lastErrorSignature = ""
        lastChargeMos = null
        lastDischargeMos = null
        lastKnownSoc = null
        lastKnownChargeMosTextText = null
        lastKnownDischargeMosTextText = null
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
        return if (isServiceApp()) lastUploadStatus
        else "$lastUploadStatus\n${adminServerBaseUrl()}$UPLOAD_PATH"
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
                if (isServiceApp()) "Нет связи с сервером"
                else "Нет связи с ${adminServerBaseUrl()}: ${e.message ?: e.toString()}"
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
        if (!hasStableBmsIdentityForUpload()) {
            Log.w(BLE_LOG_TAG, "telemetry upload skipped: unstable bms identity uid=${bmsUid()}")
            if (force) {
                lastUploadStatus = "Нет стабильного ID BMS"
                refreshUploadStatusUi()
            }
            return
        }
        if (data.voltage == null && data.soc == null && !force) return

        val now = System.currentTimeMillis()
        if (!force && now - lastUploadAt < UPLOAD_INTERVAL_MS) return

        uploading = true
        lastUploadStatus = "Отправка..."
        refreshUploadStatusUi()

        thread {
            val result = postCurrentTelemetry()
            uploading = false
            lastUploadStatus = result.message
            runOnUiThread {
                refreshUploadStatusUi()
                // Успех — тихо (только Logcat); ошибку пользователю показываем.
                if (!result.ok && (force || isServiceApp())) {
                    toast(lastUploadStatus)
                }
                if (result.ok) {
                    Log.i(BLE_LOG_TAG, "telemetry upload OK: $lastUploadStatus")
                }
                if (screenState == "journal") showJournalScreen()
            }
        }
    }

    private fun hasStableBmsIdentityForUpload(): Boolean {
        val uid = bmsUid().trim()
        if (isDalyBluetoothDeviceId(uid)) return true
        return !selectedAddress.isNullOrBlank() && uid.isNotBlank() && uid != "unknown_bms"
    }

    private data class TelemetryPostResult(val ok: Boolean, val message: String)

    private fun postCurrentTelemetry(): TelemetryPostResult {
        val json = adminJsonRequest("POST", UPLOAD_PATH, buildUploadJson())
        if (json != null && json.optBoolean("ok")) {
            lastUploadAt = System.currentTimeMillis()
            val logId = json.optInt("log_id", 0).takeIf { it > 0 }
            lastLogId = logId
            return TelemetryPostResult(true, "Успешно отправлено${logId?.let { ", log_id=$it" } ?: ""}")
        }
        val error = json?.optString("error").orEmpty()
        val message = when {
            json == null -> "Нет связи с сервером"
            error == "unauthorized" -> "Нет связи с сервером"
            error.isNotBlank() -> "Ошибка сервера: $error"
            else -> "Нет связи с сервером"
        }
        return TelemetryPostResult(false, message)
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
            ConfigReadRequest("dl_soc0_0227", 0x81, DALY_SOC_CALIBRATION_0_REG, 0x01),
            ConfigReadRequest("dl_soc100_0229", 0x81, DALY_SOC_CALIBRATION_100_REG, 0x01),
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
        if (command.localOnly) {
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
            val actual = when (command.key) {
                "nominal_capacity" -> currentNominalCapacityAh()
                "runtime_soc" -> data.soc
                    ?: scaledRegisterValue(command.register, command.scale, command.offset)
                else -> scaledRegisterValue(command.register, command.scale, command.offset)
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
                    command.verifyAttempt = 0
                    configRegisters.remove(command.register)
                    Log.i(
                        BLE_LOG_TAG,
                        "WRITE ${command.key}: requested=${command.value}${command.unit} " +
                            "raw=${command.rawValue} reg=0x%04X".format(command.register)
                    )
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
        if (
            remoteWriteAlreadyMatches(command) &&
            command.key in setOf("nominal_capacity", "runtime_soc", "settings_password", "series_cell_count")
        ) {
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
        toast("Запись: ${command.label} = ${formatTemplateNumber(command.displayValue ?: command.value)} ${command.unit}".trim())

        val timeFrame = buildDalyTimeFrame()
        val openFrame = buildModbusWriteSingleRequest(0x81, 0x0174, 0x00A2)
        val writeFrames = command.writeFrames ?: listOf(
            buildModbusWriteSingleRequest(0x81, command.register, command.rawValue)
        )

        fun afterFramesWritten() {
            if (command.key == "nominal_capacity" || command.key == "runtime_soc") {
                probeRuntimeGauge {
                    val expected = command.displayValue ?: command.value
                    val actual = if (command.key == "runtime_soc") data.soc else data.remainingAh
                    val ok = when (command.key) {
                        "runtime_soc" -> runtimeSocIsFull()
                        else -> {
                            val target = serviceWriteCapacityAh ?: expected
                            remainingMatchesTarget(target) ||
                                (capacityMatchesTarget(target) && runtimeSocIsFull())
                        }
                    }
                    Log.i(
                        BLE_LOG_TAG,
                        if (ok) {
                            "VERIFY OK ${command.key}: expected=$expected actual=$actual"
                        } else {
                            "VERIFY FAILED ${command.key}: expected=$expected actual=$actual"
                        }
                    )
                    remoteWriteInProgress = false
                    pendingRemoteWrite = null
                    completeServiceWriteStep(
                        command,
                        ok,
                        actual,
                        if (ok) null else "not_confirmed"
                    )
                }
                return
            }
            if (command.key == "settings_password") {
                command.verifyAttempt = 0
                Log.i(BLE_LOG_TAG, "WRITE settings_password: requested=$SERVICE_SETTINGS_PASSWORD")
                remoteWriteAwaitingVerify = true
                mainHandler.postDelayed({
                    startConfigReadIfNeeded(force = true)
                    if (!configReadInProgress) {
                        mainHandler.postDelayed({
                            if (remoteWriteAwaitingVerify && !configReadInProgress) {
                                failRemoteWrite("config_read_not_started")
                            }
                        }, 400)
                    }
                }, 700)
                return
            }

            // READ → WRITE → READ BACK → COMPARE для шаблонных регистров
            command.verifyAttempt = 0
            configRegisters.remove(command.register)
            Log.i(
                BLE_LOG_TAG,
                "WRITE ${command.key}: requested=${command.value}${command.unit} raw=${command.rawValue} reg=0x%04X"
                    .format(command.register)
            )
            remoteWriteAwaitingVerify = true
            mainHandler.postDelayed({
                startConfigReadIfNeeded(force = true)
                if (!configReadInProgress) {
                    mainHandler.postDelayed({
                        if (remoteWriteAwaitingVerify && !configReadInProgress) {
                            failRemoteWrite("config_read_not_started")
                        }
                    }, 400)
                }
            }, 700)
        }

        fun writeFrameAt(index: Int) {
            if (index >= writeFrames.size) {
                writeBleFrame(openFrame)
                mainHandler.postDelayed({ afterFramesWritten() }, 200L)
                return
            }
            val frame = writeFrames[index]
            Log.d(BLE_LOG_TAG, "TX WRITE ${command.key}[$index]: ${bytesToHex(frame)}")
            writeBleFrame(timeFrame)
            mainHandler.postDelayed({
                writeBleFrame(openFrame)
                mainHandler.postDelayed({
                    if (!writeBleFrame(frame)) {
                        remoteWriteInProgress = false
                        pendingRemoteWrite = null
                        remoteWriteAwaitingVerify = false
                        completeServiceWriteStep(command, false, null, "ble_write_failed")
                        return@postDelayed
                    }
                    mainHandler.postDelayed({ writeFrameAt(index + 1) }, 200L)
                }, 250L)
            }, 140L)
        }

        writeFrameAt(0)
    }

    private fun probeRuntimeGauge(done: () -> Unit) {
        writeBleFrame(buildRequest(0x90))
        mainHandler.postDelayed({
            writeBleFrame(buildRequest(0x93))
            mainHandler.postDelayed({
                updateDerived()
                done()
            }, 450L)
        }, 220L)
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
                    Log.i(
                        BLE_LOG_TAG,
                        if (ok) "VERIFY OK series_cell_count actual=$actual"
                        else "VERIFY FAILED series_cell_count: expected=${command.rawValue} actual=$actual"
                    )
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
        val actualFromRegister = raw?.let { (it.toDouble() / command.scale) + command.offset }
        val passwordActual = if (command.key == "settings_password") {
            passwordFromBmsConfig()
        } else null
        val actual = when (command.key) {
            "settings_password" -> passwordActual?.toDoubleOrNull()
            "runtime_soc" -> data.soc ?: actualFromRegister
            else -> actualFromRegister
        }

        // Сравниваем прежде всего нормализованный raw регистра (без float-шума).
        val ok = when (command.key) {
            "settings_password" -> passwordActual == SERVICE_SETTINGS_PASSWORD
            "runtime_soc" -> {
                when {
                    raw != null && raw == command.rawValue -> true
                    actualFromRegister != null &&
                        kotlin.math.abs(actualFromRegister - command.value) <= 1.0 -> true
                    data.soc != null &&
                        kotlin.math.abs(data.soc!! - command.value) <= 1.0 -> true
                    else -> false
                }
            }
            else -> {
                when {
                    raw == null -> false
                    raw == command.rawValue -> true
                    else -> {
                        // Допуск только по разрешению регистра (половина LSB в единицах value).
                        val lsb = if (command.scale != 0.0) 1.0 / kotlin.math.abs(command.scale) else 1.0
                        val tolerance = (lsb / 2.0).coerceAtMost(0.02)
                        actualFromRegister != null &&
                            kotlin.math.abs(actualFromRegister - command.value) <= tolerance
                    }
                }
            }
        }

        Log.i(
            BLE_LOG_TAG,
            "READBACK ${command.key}: actual=${actual ?: "null"} raw=${raw ?: "null"} " +
                "expected=${command.value} expected_raw=${command.rawValue}"
        )
        configRaw["remote_write_verify"] = if (ok) {
            "OK raw=$raw actual=$actual"
        } else {
            "NOT_CONFIRMED raw=${raw ?: "null"} actual=${actual ?: "null"} expected_raw=${command.rawValue}"
        }

        if (!ok && command.verifyAttempt < SERVICE_WRITE_VERIFY_MAX_ATTEMPTS) {
            command.verifyAttempt++
            Log.w(
                BLE_LOG_TAG,
                "VERIFY RETRY ${command.key} attempt=${command.verifyAttempt}: " +
                    "expected=${command.value} actual=${actual ?: "null"}"
            )
            configRegisters.remove(command.register)
            remoteWriteAwaitingVerify = true
            mainHandler.postDelayed({
                startConfigReadIfNeeded(force = true)
                if (!configReadInProgress) {
                    mainHandler.postDelayed({
                        if (remoteWriteAwaitingVerify && !configReadInProgress) {
                            failRemoteWrite("config_read_not_started")
                        }
                    }, 400)
                }
            }, SERVICE_WRITE_VERIFY_RETRY_DELAY_MS)
            return
        }

        if (ok) {
            Log.i(BLE_LOG_TAG, "VERIFY OK ${command.key}")
        } else {
            Log.w(
                BLE_LOG_TAG,
                "VERIFY FAILED ${command.key}: expected=${command.value}${command.unit} " +
                    "actual=${actual ?: "null"}${command.unit} expected_raw=${command.rawValue} raw=${raw ?: "null"}"
            )
        }

        if (command.localOnly) {
            pendingRemoteWrite = null
            remoteWriteInProgress = false
            rememberCurrentBmsState()
            completeServiceWriteStep(command, ok, actual, if (ok) null else "not_confirmed")
            if (ok) {
                toast("✓ ${command.label}: ${formatTemplateNumber(actual)} ${command.unit}".trim())
            } else {
                toast(
                    "✗ ${command.label}: требовалось ${formatTemplateNumber(command.value)} ${command.unit}, " +
                        "фактически ${formatTemplateNumber(actual)} ${command.unit}".trim()
                )
            }
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
                put("api_key", BmsApiConfig.API_KEY)
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
                applyBmsApiAuth(this)
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
        fetchServerConfigTemplate(force = true)
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
                probeRuntimeGauge {
                    if (enqueueServiceTemplateRetryIfNeeded()) return@probeRuntimeGauge
                    finishServiceBatchWrite()
                    mainHandler.postDelayed({
                        rememberCurrentBmsState()
                        uploadConfigSnapshot(force = true)
                    }, 1200)
                    mainHandler.postDelayed({ pollOnce() }, 800)
                }
                return
            }

            if (remoteWriteAwaitingVerify) {
                finishRemoteWriteVerification()
                mainHandler.postDelayed({
                    rememberCurrentBmsState()
                    uploadConfigSnapshot(force = true)
                }, 1200)
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
                val conn = (URL(adminServerUrl(CONFIG_UPLOAD_PATH)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    applyBmsApiAuth(this)
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
            put("bms_version", displayBmsVersion())
        }
    }

    private fun buildConfigUploadJson(): JSONObject {
        ensureConfigForUpload()

        val obj = JSONObject()
        obj.put("api_key", BmsApiConfig.API_KEY)
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
        general.put("Калибр. SOC 0", regText(DALY_SOC_CALIBRATION_0_REG, 1000.0, "V"))
        general.put("Калибр. SOC 100", regText(DALY_SOC_CALIBRATION_100_REG, 1000.0, "V"))
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
        general.put("soc_calibration_0_v", regText(DALY_SOC_CALIBRATION_0_REG, 1000.0, "V"))
        general.put("soc_calibration_100_v", regText(DALY_SOC_CALIBRATION_100_REG, 1000.0, "V"))
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
        // SOC 0% — 0x0227 (завод 2.7 В), SOC 100% — 0x0229. 0x01C7 — отключение (~2.2 В), не калибровка SOC.
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
        cellParams.put("Калибр. SOC 0", regText(DALY_SOC_CALIBRATION_0_REG, 1000.0, "V"))
        cellParams.put("Калибр. SOC 100", regText(DALY_SOC_CALIBRATION_100_REG, 1000.0, "V"))
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
        cellParams.put("soc_calibration_0_v", regText(DALY_SOC_CALIBRATION_0_REG, 1000.0, "V"))
        cellParams.put("soc_calibration_100_v", regText(DALY_SOC_CALIBRATION_100_REG, 1000.0, "V"))
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
        note.put("write_commands_enabled", !isServiceApp())
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
        obj.put("api_key", BmsApiConfig.API_KEY)
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
            "qr_scan" -> {
                stopQrCamera()
                showQrInputScreen()
            }
            "qr_result" -> showQrInputScreen()
            "qr_input" -> showSplashScreen()
            "splash" -> super.onBackPressed()
            "search", "loading" -> {
                stopBleScanQuietly()
                disconnectGatt()
                clearUiBackStack()
                navigatingBack = true
                try {
                    showBatteriesScreen(asRootHome = true)
                } finally {
                    navigatingBack = false
                }
            }
            else -> {
                if (!navigateBackUi()) {
                    super.onBackPressed()
                }
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
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 176, 69)
        style = Paint.Style.STROKE
        strokeWidth = 22f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(16, 17, 20)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isFakeBoldText = true
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 119, 129)
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
        val size = min(w, h)
        val stroke = (size * 0.095f).coerceIn(18f, 26f)
        bgPaint.strokeWidth = stroke
        valuePaint.strokeWidth = stroke
        val pad = stroke / 2f + 4f
        val left = (w - size) / 2f + pad
        val top = (h - size) / 2f + pad
        val rect = RectF(left, top, left + size - 2f * pad, top + size - 2f * pad)

        canvas.drawArc(rect, -90f, 360f, false, bgPaint)

        val s = max(0.0, min(100.0, soc ?: 0.0))
        val accent = when {
            s <= 20.0 -> Color.rgb(210, 70, 70)
            s < 70.0 -> Color.rgb(215, 160, 35)
            else -> Color.rgb(45, 176, 69)
        }
        valuePaint.color = accent
        if (soc != null && s > 0.0) {
            canvas.drawArc(rect, -90f, (360.0 * (s / 100.0)).toFloat(), false, valuePaint)
        }

        val cx = w / 2f
        val cy = h / 2f
        // Главный акцент — процент (крупнее и жирнее), SOC вторично снизу
        textPaint.color = Color.rgb(16, 17, 20)
        textPaint.textSize = size * 0.28f
        val percentText = if (soc == null) "--%" else "%.0f%%".format(s)
        canvas.drawText(percentText, cx, cy + size * 0.02f, textPaint)
        smallPaint.textSize = size * 0.10f
        canvas.drawText("SOC", cx, cy + size * 0.18f, smallPaint)
    }
}
