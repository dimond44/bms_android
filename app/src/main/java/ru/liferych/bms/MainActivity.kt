package ru.liferych.bms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import ru.liferych.bms.cellcode.CellCodeDecoder
import ru.liferych.bms.cellcode.CellCodeRecognition
import ru.liferych.bms.cellcode.CellQrDecodeResult
import ru.liferych.bms.image.OrientedBitmapLoader
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

private const val FRAME_START: Byte = 0xA5.toByte()
private const val REQUEST_ADDRESS: Byte = 0x40
private const val DATA_LEN: Byte = 0x08
private const val BLE_LOG_TAG = "LiferychBmsBle"
private const val BMS_BLE_TAG = "BMS-BLE"
private const val BMS_WAKE_TAG = "BMS-WAKE"
/** Targeted scan for a previously saved BMS (Android 10+ friendly). */
private const val TARGETED_SCAN_TIMEOUT_MS = 10000L
/**
 * Окно BLE presence-scan на экране «Мои батареи».
 * После окна без MAC → UNAVAILABLE («Не в сети»), не «Спящий режим».
 */
private const val PRESENCE_SCAN_WINDOW_MS = 15000L
/** Свежесть телеметрии: ответ Daly в этом окне подтверждает ONLINE при активном GATT. */
private const val PRESENCE_TELEMETRY_FRESH_MS = 30000L
private const val BATTERY_AVAILABILITY_TAG = "BatteryAvailability"
private const val WAKE_MAX_ATTEMPTS = 5
private val WAKE_RETRY_DELAYS_MS = longArrayOf(400L, 400L, 500L, 500L, 500L)

private const val DEFAULT_ADMIN_SERVER_BASE_URL = BmsApiConfig.BASE_URL
private const val UPLOAD_PATH = "/api/upload.php"
private const val CONFIG_UPLOAD_PATH = "/api/config_upload.php"
private const val SERVICE_REPORT_PATH = "/api/v1/service-report"
private const val WARRANTY_SUBMIT_PATH = "/api/warranty_submit.php"
private const val WARRANTY_LIST_PATH = "/api/warranty_list.php"
private const val USER_LOGIN_PATH = "/api/v1/users/login"
private const val USER_REGISTER_PATH = "/api/v1/users/register"
private const val USER_PROFILE_PATH = "/api/v1/users/profile"
private const val USER_LINK_BATTERY_PATH = "/api/v1/users/batteries/link"
private const val USER_UNLINK_BATTERY_PATH = "/api/v1/users/batteries/unlink"
private const val USER_AVATAR_PATH = "/api/v1/users/avatar"
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
private const val METRIC_ICON_BOX_DP = 48
private const val METRIC_ICON_DP = 34
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

/** Точка исторической телеметрии для графиков (server received_at + V/I). */
data class TelemetryHistoryPoint(
    val timestamp: Long,
    val voltage: Double?,
    val current: Double?
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

/** Присутствие сохранённой BMS на экране «Мои батареи» (не путать с lastSeen / sleep_timeout). */
private enum class BatteryPresenceState {
    CHECKING,
    ONLINE,
    /** BLE не рекламируется / нет ответа — «Не в сети». Не означает аппаратный sleep BMS. */
    UNAVAILABLE
}

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
    private val WARRANTY_CAMERA_REQUEST_CODE = 4503
    private val PROFILE_AVATAR_REQUEST_CODE = 4502
    private val PROFILE_CAMERA_PERMISSION_REQUEST_CODE = 1004
    private val CAMERA_PERMISSION_REQUEST_CODE = 1002
    private val WARRANTY_CAMERA_PERMISSION_REQUEST_CODE = 1003
    /** Временный URI для камеры аватара. */
    private var profilePendingCameraUri: Uri? = null
    /** Upload аватара на сервер: Idle / Uploading / Success / Error. */
    private var avatarUploadState: String = "Idle"
    private var loginLoading: Boolean = false
    private var loginStatusText: TextView? = null
    private var profileAvatarStatusText: TextView? = null
    /** Отмена устаревших ответов login при logout / повторном входе. */
    private var loginGeneration: Int = 0
    /** Национальная часть номера, переносимая Login → Register. */
    private var authPendingNationalPhone: String = ""
    /**
     * Действие после успешного Login/Register.
     * Сейчас: "add_battery" — продолжить добавление АКБ; null — на «Мои батареи».
     */
    private var pendingAuthAction: String? = null
    private val WARRANTY_PAGE_SIZE = 5
    private val warrantyMediaUris: MutableList<Uri> = mutableListOf()
    /** Временный URI для системной камеры (ACTION_IMAGE_CAPTURE). */
    private var warrantyPendingCameraUri: Uri? = null
    private var warrantyListPage: Int = 1
    private var warrantyListTotal: Int = 0
    private var warrantyListTotalPages: Int = 1
    private var warrantyListLoading: Boolean = false
    private var warrantyPaginationRow: LinearLayout? = null
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
    private var warrantyMediaListLayout: LinearLayout? = null
    private var warrantyStatusText: TextView? = null
    private var warrantyListLayout: LinearLayout? = null
    private var supportPrefs: SharedPreferences? = null
    private var editingWarrantyLocalId: String? = null
    private var supportMode: String = "home"
    private var currentTab: String = "main"
    private var manageSection: String = "general"
    private var screenState: String = "splash"

    /** Режим периода графиков: day | week | month | custom. */
    private var chartsPeriodMode: String = "day"
    /** Начало выбранного дня (локальная timezone), для day/week/month навигации. */
    private var chartsAnchorDayStartMs: Long = 0L
    private var chartsCustomFromDayStartMs: Long = 0L
    private var chartsCustomToDayStartMs: Long = 0L
    private var chartsLoadToken: Int = 0
    private var chartsStatus: String = "idle" // loading | ok | empty | error
    private var chartsErrorText: String = ""
    private var chartsPoints: List<TelemetryHistoryPoint> = emptyList()
    private var chartsMinVoltage: Double? = null
    private var chartsMaxVoltage: Double? = null
    private var chartsMinCurrent: Double? = null
    private var chartsMaxCurrent: Double? = null
    private var chartsLoadedForUid: String = ""
    private var chartsVoltageTooltipText: TextView? = null
    private var chartsCurrentTooltipText: TextView? = null

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
    /** Targeted reconnect: scan only for this BLE MAC (SavedBattery.address). */
    private var targetedScanAddress: String? = null
    private var targetedScanToken = 0
    /** Presence scan for «Мои батареи»: visible MAC ≠ sleeping. */
    private var batteriesPresenceScanActive = false
    private var batteriesPresenceScanToken = 0
    private var batteriesPresenceScanCompleted = false
    private val batteriesSeenInScan = mutableSetOf<String>()
    private val batteryCardStatusHosts = linkedMapOf<String, LinearLayout>()
    private val batteryCardPresenceByAddress = linkedMapOf<String, BatteryPresenceState>()
    /** Post-GATT wake via safe Daly 0x90 read before normal poll loop. */
    private var wakeInProgress = false
    private var wakeToken = 0
    private var wakeAttempt = 0
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
    private var chargeMosSwitch: Switch? = null
    private var dischargeMosSwitch: Switch? = null
    private var suppressMosSwitchCallback: Boolean = false
    private lateinit var balanceValue: TextView
    private var balanceIconHost: FrameLayout? = null
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

    private val profileGalleryLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) persistProfileAvatarFromUri(uri)
        }

    private val profileCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = profilePendingCameraUri
            profilePendingCameraUri = null
            if (success && uri != null) {
                persistProfileAvatarFromUri(uri)
            }
        }

    private val profileCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchProfileCamera()
            } else {
                toast("Для съёмки фото необходимо разрешение на камеру")
            }
        }

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
        if (isServiceApp()) {
            showSplashScreen()
        } else {
            // Авторизация не блокирует старт: всегда «Мои батареи» (гость или сессия).
            showBatteriesScreen(asRootHome = true)
        }
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

        // Текстовая кнопка: тот же BLE-flow (loading + scan), без иконки и подписи «по Bluetooth».
        val connect = TextView(this).apply {
            text = "Подключить батарею"
            textSize = 17f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(720)
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
        launchTop.addView(connect, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(62)
        ).apply {
            setMargins(dp(14), dp(26), dp(14), 0)
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
        enterScreen("qr_input")
        currentTab = "qr"
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
            text = "Проверить QR-код"
            textSize = 22f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(750)
        }, marginLp(-1, -2, 0, 4, 0, 14))
        content.addView(TextView(this).apply {
            text = "Наведите камеру на QR/Data Matrix код элемента или введите код вручную."
            textSize = 13f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.rgb(246, 247, 249), dp(18), Color.rgb(223, 229, 235), 1)
        })

        content.addView(TextView(this).apply {
            text = "Код элемента"
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
            contentDescription = "Сканировать"
            setOnClickListener { showQrScannerScreen() }
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        val input = EditText(this).apply {
            setText(qrCodeValue)
            hint = "Ввести код вручную"
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
            text = "Допускается код из двух частей — пробелы и переносы будут убраны."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
        }, marginLp(-1, -2, 0, 10, 0, 0))
        content.addView(TextView(this).apply {
            text = "ПРОВЕРИТЬ"
            textSize = 15f
            typeface = interFont(780)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                qrCodeValue = input.text.toString()
                if (qrCodeValue.isBlank()) {
                    toast("Введите или отсканируйте код")
                } else {
                    showQrResultScreen(qrCodeValue)
                }
            }
        }, marginLp(-1, dp(52), 0, 16, 0, 0))
        content.addView(TextView(this).apply {
            text = "Открыть сканер камеры"
            textSize = 14f
            typeface = interFont(700)
            gravity = Gravity.CENTER
            setTextColor(redDark)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { showQrScannerScreen() }
        }, marginLp(-1, -2, 0, 8, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("qr"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun showQrScannerScreen() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            return
        }
        enterScreen("qr_scan")
        currentTab = "qr"
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
            text = "Ввести код вручную"
            textSize = 14f
            typeface = interFont(700)
            gravity = Gravity.CENTER
            setTextColor(redDark)
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener {
                stopQrCamera()
                showQrInputScreen()
            }
        }, marginLp(-1, -2, 0, 12, 0, 0))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("qr"), LinearLayout.LayoutParams(-1, dp(70)))
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
                            // Оставляем qrProcessing=true, чтобы не ловить повторные кадры.
                            qrCodeValue = value
                            runOnUiThread {
                                stopQrCamera()
                                showQrResultScreen(value)
                            }
                        } else if (screenState == "qr_scan") {
                            qrProcessing = false
                        }
                    }
                    .addOnFailureListener {
                        if (screenState == "qr_scan") qrProcessing = false
                        runOnUiThread { toast("Не удалось распознать код") }
                    }
                    .addOnCompleteListener {
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
        enterScreen("qr_result")
        currentTab = "qr"
        val decoded: CellQrDecodeResult = CellCodeDecoder.decode(code)
        qrCodeValue = decoded.normalizedCode.ifBlank { code }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        root.addView(header("Проверка элемента", showBack = true))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(14))
        }
        content.addView(TextView(this).apply {
            text = "Проверка элемента"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 14))

        val (statusText, statusColor, statusBg) = when (decoded.recognition) {
            CellCodeRecognition.FULL -> Triple(
                "Код распознан",
                Color.rgb(31, 179, 90),
                Color.rgb(237, 250, 242)
            )
            CellCodeRecognition.PARTIAL -> Triple(
                "Код распознан частично",
                Color.rgb(224, 150, 0),
                Color.rgb(255, 248, 218)
            )
            CellCodeRecognition.FAILED -> Triple(
                "Не удалось распознать код элемента",
                Color.rgb(111, 119, 129),
                Color.rgb(241, 243, 245)
            )
        }
        content.addView(TextView(this).apply {
            text = statusText
            textSize = 14f
            typeface = interFont(760)
            setTextColor(statusColor)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = round(statusBg, dp(16), statusColor, 1)
        })

        content.addView(TextView(this).apply {
            text = "Код элемента"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
        }, marginLp(-1, -2, 0, 12, 0, 4))
        content.addView(TextView(this).apply {
            text = decoded.normalizedCode.ifBlank { decoded.rawCode }
            textSize = 14f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.rgb(246, 247, 249), dp(14), Color.rgb(223, 229, 235), 1)
        })

        fun displayOrDash(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "Не определено"
        fun capacityText(): String = decoded.nominalCapacityAh?.let { "%.0f Ач".format(it) }
            ?: "Не определено"
        fun voltageText(): String = decoded.nominalVoltageV?.let { "%.1f В".format(it) }
            ?: "Не определено"

        val rows = buildList {
            add("Производитель" to displayOrDash(decoded.manufacturer))
            add("Тип продукта" to displayOrDash(decoded.productType))
            add("Тип аккумулятора" to displayOrDash(decoded.batteryType))
            add("Номинальная ёмкость" to capacityText())
            add("Номинальное напряжение" to voltageText())
            add("Дата производства" to displayOrDash(decoded.productionDateDisplay))
            add("Серия" to displayOrDash(decoded.productSeries))
            if (decoded.productAttribute != null) {
                add("Атрибут" to decoded.productAttribute)
            }
            if (decoded.subsidiary != null) {
                add("Дочерняя компания" to decoded.subsidiary)
            }
        }

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
                    gravity = Gravity.END
                }, LinearLayout.LayoutParams(0, -2, 1.2f))
            })
        }
        content.addView(report, marginLp(-1, -2, 0, 12, 0, 0))

        content.addView(TextView(this).apply {
            text = "СКАНИРОВАТЬ ЕЩЁ"
            textSize = 15f
            typeface = interFont(780)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                qrCodeValue = ""
                showQrScannerScreen()
            }
        }, marginLp(-1, dp(52), 0, 14, 0, 0))
        content.addView(TextView(this).apply {
            text = "Ввести код вручную"
            textSize = 14f
            typeface = interFont(700)
            gravity = Gravity.CENTER
            setTextColor(redDark)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { showQrInputScreen() }
        }, marginLp(-1, -2, 0, 4, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("qr"), LinearLayout.LayoutParams(-1, dp(70)))
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
        if (!isServiceApp() && !isUserSessionActive()) {
            // Гостевой режим: чужие/устаревшие локальные АКБ не показываем.
            if (loadSavedBatteries().isNotEmpty()) {
                saveBatteries(emptyList())
            }
        }
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
        top.addView(brand, LinearLayout.LayoutParams(-1, dp(56)))
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

        // Только текст: иконка убрана, click → beginAddBatteryFlow() без изменений.
        val addButton = TextView(this).apply {
            text = if (isServiceApp()) "+ ДОБАВИТЬ АКБ" else "ДОБАВИТЬ БАТАРЕЮ"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(780)
            isClickable = true
            isFocusable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(255, 217, 87), Color.rgb(255, 196, 0))
            ).apply { cornerRadius = dp(15).toFloat() }
            elevation = dp(4).toFloat()
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
        batteryCardStatusHosts.clear()
        batteryCardPresenceByAddress.clear()
        saved.forEach { battery ->
            content.addView(savedBatteryCard(battery), marginLp(-1, -2, 0, 0, 0, 10))
        }

        if (saved.isEmpty()) {
            content.addView(TextView(this).apply {
                text = if (isServiceApp()) {
                    "В этой сессии АКБ ещё не добавлены"
                } else if (!isUserSessionActive()) {
                    "У вас пока нет добавленных батарей.\nВойдите, чтобы добавить АКБ и синхронизировать список."
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
        if (saved.isNotEmpty()) {
            startBatteriesPresenceScan(resetSeen = true)
        } else {
            stopBatteriesPresenceScan()
        }
    }

    private fun normalizeBleAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }

    private fun isBatteryBleConnected(battery: SavedBattery): Boolean {
        if (battery.address == TEST_BATTERY_ADDRESS) return false
        return polling &&
            selectedAddress != null &&
            selectedAddress.equals(battery.address, ignoreCase = true) &&
            bluetoothGatt != null
    }

    private fun wasBatterySeenInScan(battery: SavedBattery): Boolean {
        return batteriesSeenInScan.contains(normalizeBleAddress(battery.address))
    }

    /**
     * ONLINE:
     * - активный GATT и свежий/ожидаемый ответ телеметрии, или
     * - MAC виден в текущем presence-scan (устройство рекламируется → можно подключаться).
     *
     * UNAVAILABLE: окно скана завершено, MAC не найден и нет GATT.
     * Не путать с sleep_timeout / аппаратным sleep — протокол presence этого не отдаёт.
     */
    private fun resolveBatteryPresence(battery: SavedBattery): BatteryPresenceState {
        if (battery.address == TEST_BATTERY_ADDRESS) return BatteryPresenceState.ONLINE
        if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
            return BatteryPresenceState.UNAVAILABLE
        }
        if (!hasBlePermissions()) {
            return BatteryPresenceState.UNAVAILABLE
        }
        if (isBatteryBleConnected(battery)) {
            // GATT: ONLINE только после валидного ответа; иначе CHECKING (wake/poll).
            return if (hasFreshTelemetryForSelected(battery)) {
                BatteryPresenceState.ONLINE
            } else {
                BatteryPresenceState.CHECKING
            }
        }
        if (wasBatterySeenInScan(battery)) {
            return BatteryPresenceState.ONLINE
        }
        if (!batteriesPresenceScanCompleted) {
            return BatteryPresenceState.CHECKING
        }
        return BatteryPresenceState.UNAVAILABLE
    }

    /**
     * Есть свежий ответ Daly по выбранной (подключённой) АКБ.
     */
    private fun hasFreshTelemetryForSelected(battery: SavedBattery): Boolean {
        if (selectedAddress == null ||
            !selectedAddress.equals(battery.address, ignoreCase = true)
        ) {
            return false
        }
        val updated = data.lastUpdatedAt ?: return false
        return System.currentTimeMillis() - updated <= PRESENCE_TELEMETRY_FRESH_MS
    }

    /**
     * Фиксирует ONLINE после валидного ответа BMS (не после одной отправки write).
     */
    private fun markBatteryOnlineFromValidResponse(address: String?, source: String) {
        val addr = address?.trim().orEmpty()
        if (addr.isBlank() || addr == TEST_BATTERY_ADDRESS) return
        val key = normalizeBleAddress(addr)
        batteriesSeenInScan.add(key)
        Log.d(
            BATTERY_AVAILABILITY_TAG,
            "battery=$key responseReceived=true source=$source status=ONLINE",
        )
        if (screenState == "batteries") {
            runOnUiThread { refreshBatteryPresenceCard(addr) }
        }
    }

    /**
     * Presence текущей выбранной BMS (null — батарея не выбрана).
     */
    private fun resolveCurrentBmsPresence(): BatteryPresenceState? {
        val battery = currentSavedBatteryOrNull() ?: return null
        return resolveBatteryPresence(battery)
    }

    /**
     * Живой fault feed: активный GATT + poll. Без него client не показывает cached errors.
     */
    private fun hasLiveBmsFaultFeed(): Boolean {
        return bluetoothGatt != null && polling
    }

    /**
     * Единый источник ACTIVE ошибок для Главной и Журнала.
     * Только ONLINE + живой 0x98; иначе пусто (без stale cache).
     */
    private fun currentActiveBmsErrors(): List<String> {
        if (resolveCurrentBmsPresence() != BatteryPresenceState.ONLINE) return emptyList()
        if (!hasLiveBmsFaultFeed()) return emptyList()
        return data.errors
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Открывает Журнал только при ONLINE BMS; иначе toast и без смены экрана.
     */
    private fun openJournalIfAllowed() {
        when (resolveCurrentBmsPresence()) {
            BatteryPresenceState.ONLINE -> showJournalScreen()
            BatteryPresenceState.CHECKING ->
                toast("Проверяем состояние батареи…")
            BatteryPresenceState.UNAVAILABLE,
            null ->
                toast("Журнал доступен только когда батарея в сети")
        }
    }

    /**
     * Если Журнал открыт, а BMS больше не ONLINE — убрать список ошибок.
     */
    private fun refreshJournalIfPresenceLost() {
        if (screenState == "journal") {
            showJournalScreen()
        }
    }

    private fun fillBatteryPresenceStatus(
        host: LinearLayout,
        battery: SavedBattery,
        state: BatteryPresenceState
    ) {
        host.removeAllViews()
        host.setPadding(dp(12), dp(10), dp(12), dp(10))
        when {
            battery.address == TEST_BATTERY_ADDRESS -> {
                host.background = round(Color.rgb(255, 248, 218), dp(13), Color.TRANSPARENT, 0)
                host.addView(TextView(this).apply {
                    text = "✓  Тестовый режим"
                    textSize = 13f
                    setTextColor(redDark)
                    typeface = interFont(760)
                })
            }
            state == BatteryPresenceState.ONLINE -> {
                host.background = round(Color.rgb(237, 250, 242), dp(13), Color.TRANSPARENT, 0)
                host.addView(TextView(this).apply {
                    text = "Батарея в сети"
                    textSize = 13f
                    setTextColor(Color.rgb(31, 179, 90))
                    typeface = interFont(760)
                })
            }
            state == BatteryPresenceState.CHECKING -> {
                host.background = round(Color.rgb(241, 243, 245), dp(13), Color.TRANSPARENT, 0)
                host.addView(TextView(this).apply {
                    text = "Проверяем состояние батареи…"
                    textSize = 13f
                    setTextColor(Color.rgb(111, 119, 129))
                    typeface = interFont(700)
                })
            }
            state == BatteryPresenceState.UNAVAILABLE -> {
                host.background = round(Color.rgb(241, 243, 245), dp(13), Color.TRANSPARENT, 0)
                val title = when {
                    !::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled ->
                        "Bluetooth выключен"
                    !hasBlePermissions() ->
                        "Нет доступа к Bluetooth"
                    else ->
                        "Не в сети"
                }
                host.addView(TextView(this).apply {
                    text = title
                    textSize = 13f
                    setTextColor(Color.rgb(75, 79, 84))
                    typeface = interFont(760)
                })
                if (title == "Не в сети") {
                    host.addView(TextView(this).apply {
                        text = "BMS не отвечает в эфире. Подойдите ближе или нажмите на карточку для подключения."
                        textSize = 12f
                        setTextColor(Color.rgb(111, 119, 129))
                        typeface = interFont(600)
                    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                }
            }
            else -> {
                // Запасной путь (не должен срабатывать после удаления SLEEPING).
                host.background = round(Color.rgb(241, 243, 245), dp(13), Color.TRANSPARENT, 0)
                host.addView(TextView(this).apply {
                    text = "Проверяем состояние батареи…"
                    textSize = 13f
                    setTextColor(Color.rgb(111, 119, 129))
                    typeface = interFont(700)
                })
            }
        }
    }

    private fun refreshBatteryPresenceCard(address: String) {
        if (screenState != "batteries") return
        val battery = loadSavedBatteries().firstOrNull {
            it.address.equals(address, ignoreCase = true)
        } ?: return
        val key = normalizeBleAddress(battery.address)
        val host = batteryCardStatusHosts[key] ?: return
        val state = resolveBatteryPresence(battery)
        val previous = batteryCardPresenceByAddress[key]
        if (previous == state) return
        batteryCardPresenceByAddress[key] = state
        fillBatteryPresenceStatus(host, battery, state)
    }

    private fun refreshAllBatteryPresenceCards() {
        if (screenState == "batteries") {
            for (battery in loadSavedBatteries()) {
                refreshBatteryPresenceCard(battery.address)
            }
        }
        refreshJournalIfPresenceLost()
    }

    @SuppressLint("MissingPermission")
    private fun startBatteriesPresenceScan(resetSeen: Boolean) {
        if (screenState != "batteries") return
        if (targetedScanAddress != null) return

        if (!::bluetoothAdapter.isInitialized || !bluetoothAdapter.isEnabled) {
            batteriesPresenceScanActive = false
            batteriesPresenceScanCompleted = true
            if (resetSeen) batteriesSeenInScan.clear()
            refreshAllBatteryPresenceCards()
            return
        }
        if (!hasBlePermissions()) {
            batteriesPresenceScanActive = false
            batteriesPresenceScanCompleted = true
            if (resetSeen) batteriesSeenInScan.clear()
            refreshAllBatteryPresenceCards()
            requestBlePermissions()
            return
        }

        val token = ++batteriesPresenceScanToken
        batteriesPresenceScanActive = true
        batteriesPresenceScanCompleted = false
        if (resetSeen) {
            batteriesSeenInScan.clear()
        }
        // Connected BMS already ONLINE without waiting for advertisements.
        loadSavedBatteries().forEach { battery ->
            if (isBatteryBleConnected(battery)) {
                batteriesSeenInScan.add(normalizeBleAddress(battery.address))
            }
        }
        refreshAllBatteryPresenceCards()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            batteriesPresenceScanActive = false
            batteriesPresenceScanCompleted = true
            refreshAllBatteryPresenceCards()
            return
        }

        stopBleScanQuietly()
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(emptyList(), settings, scanCallback)
            Log.i(BMS_BLE_TAG, "Batteries presence scan started windowMs=$PRESENCE_SCAN_WINDOW_MS")
            Log.d(
                BATTERY_AVAILABILITY_TAG,
                "checkStarted transport=ble_scan windowMs=$PRESENCE_SCAN_WINDOW_MS resetSeen=$resetSeen",
            )
        } catch (e: Exception) {
            Log.w(BMS_BLE_TAG, "Presence scan failed: ${e.message}")
            batteriesPresenceScanActive = false
            batteriesPresenceScanCompleted = true
            refreshAllBatteryPresenceCards()
            return
        }

        // После окна — UNAVAILABLE для ненайденных; scan оставляем, чтобы позже стать ONLINE.
        mainHandler.postDelayed({
            if (token != batteriesPresenceScanToken) return@postDelayed
            if (screenState != "batteries") return@postDelayed
            batteriesPresenceScanCompleted = true
            Log.i(BMS_BLE_TAG, "Presence scan window done seen=${batteriesSeenInScan.size}")
            Log.d(
                BATTERY_AVAILABILITY_TAG,
                "scanWindowDone seen=${batteriesSeenInScan.size} finalUnset=UNAVAILABLE",
            )
            refreshAllBatteryPresenceCards()
        }, PRESENCE_SCAN_WINDOW_MS)
    }

    private fun stopBatteriesPresenceScan() {
        batteriesPresenceScanToken++
        batteriesPresenceScanActive = false
        if (targetedScanAddress == null && screenState != "search" && screenState != "loading") {
            stopBleScanQuietly()
        }
    }

    private fun onSavedBatterySeenInPresenceScan(address: String, device: BluetoothDevice, rssi: Int, name: String?) {
        val key = normalizeBleAddress(address)
        val isSaved = loadSavedBatteries().any { normalizeBleAddress(it.address) == key }
        if (!isSaved) return
        devices[address] = device
        scanRssi[address] = rssi
        if (!name.isNullOrBlank()) {
            scanNames[address] = name
        }
        val added = batteriesSeenInScan.add(key)
        if (added || batteryCardPresenceByAddress[key] != BatteryPresenceState.ONLINE) {
            Log.i(BMS_BLE_TAG, "Presence: saved BMS visible $address")
            runOnUiThread { refreshBatteryPresenceCard(address) }
        }
    }

    /**
     * Карточка сохранённой батареи на экране «Мои батареи».
     * Presence: CONNECTED+telemetry или FOUND IN SCAN → ONLINE; иначе после scan → UNAVAILABLE.
     */
    private fun savedBatteryCard(battery: SavedBattery): View {
        val displayName = battery.customName.ifBlank {
            battery.bluetoothName.ifBlank { battery.address }
        }
        val capacityText = battery.capacityAh
            ?.takeIf { it > 0.0 }
            ?.let { "%.0f Ач".format(it) }
        val presence = resolveBatteryPresence(battery)
        val key = normalizeBleAddress(battery.address)
        batteryCardPresenceByAddress[key] = presence

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(
                Color.WHITE,
                dp(18),
                Color.rgb(223, 229, 235),
                1
            )
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true

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
                }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    rightMargin = dp(8)
                })
                addView(TextView(this@MainActivity).apply {
                    text = capacityText ?: "— Ач"
                    textSize = 14f
                    setTextColor(Color.rgb(111, 119, 129))
                    typeface = interFont(700)
                    gravity = Gravity.END
                    maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2))
            }
            addView(titleRow, LinearLayout.LayoutParams(-1, -2))

            val status = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            fillBatteryPresenceStatus(status, battery, presence)
            batteryCardStatusHosts[key] = status
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            setOnClickListener {
                if (battery.address == TEST_BATTERY_ADDRESS) {
                    openTestBattery(battery, displayName)
                } else if (isBatteryBleConnected(battery)) {
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

    /** Перерисовать список, если открыт экран «Мои батареи». */
    private fun refreshBatteriesScreenIfVisible() {
        if (screenState != "batteries") return
        val refresh = { showBatteriesScreen() }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refresh()
        } else {
            runOnUiThread { refresh() }
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
                // Отвязка от профиля на сервере: иначе после login АКБ вернётся.
                // Саму запись АКБ в админке не удаляем.
                unlinkBatteryFromUserAsync(battery)
                if (wasSelected) {
                    disconnectGatt()
                    selectedAddress = null
                    selectedDeviceName = ""
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
        // Сохранённый идентификатор — BLE MAC (device.address), без новой схемы ID.
        val address = battery.address.trim()
        if (address.isBlank() || address == TEST_BATTERY_ADDRESS) {
            toast("Некорректный Bluetooth ID батареи")
            return
        }
        Log.i(BMS_BLE_TAG, "Saved device ID: $address")
        try {
            devices[address] = bluetoothAdapter.getRemoteDevice(address)
        } catch (_: Exception) {
            // Device object may still appear from targeted scan advertising.
        }
        selectedAddress = address
        selectedDeviceName = displayName
        returnToBatteriesAfterConnect = false
        clearUiBackStack()
        showLoadingScreen("Идёт инициализация BMS")
        startTargetedScanForSavedBattery(address)
    }

    /**
     * Ищет в эфире только сохранённую BMS по MAC.
     * Если BLE-модуль не рекламируется — батарея «Не в сети» (не аппаратный sleep).
     */
    @SuppressLint("MissingPermission")
    private fun startTargetedScanForSavedBattery(address: String) {
        stopBatteriesPresenceScan()
        stopBleScanQuietly()
        cancelWakeSequence()
        val token = ++targetedScanToken
        targetedScanAddress = address
        Log.i(BMS_BLE_TAG, "Starting targeted scan for $address")

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(BMS_BLE_TAG, "BLE scanner unavailable")
            targetedScanAddress = null
            runOnUiThread {
                toast("BLE scanner недоступен. Проверьте Bluetooth.")
                showBatteriesScreen(asRootHome = true)
            }
            return
        }

        val filter = try {
            ScanFilter.Builder().setDeviceAddress(address).build()
        } catch (e: IllegalArgumentException) {
            Log.w(BMS_BLE_TAG, "Invalid saved MAC for filter: $address (${e.message})")
            null
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            if (filter != null) {
                scanner.startScan(listOf(filter), settings, scanCallback)
            } else {
                scanner.startScan(scanCallback)
            }
        } catch (e: Exception) {
            Log.w(BMS_BLE_TAG, "targeted scan start failed: ${e.message}")
            targetedScanAddress = null
            runOnUiThread {
                toast("Не удалось начать поиск BMS")
                showBatteriesScreen(asRootHome = true)
            }
            return
        }

        mainHandler.postDelayed({
            if (token != targetedScanToken) return@postDelayed
            if (targetedScanAddress == null) return@postDelayed
            Log.i(BMS_BLE_TAG, "Saved BMS not found during scan")
            Log.i(BMS_BLE_TAG, "BMS considered offline/unavailable")
            Log.d(
                BATTERY_AVAILABILITY_TAG,
                "battery=$address targetedScan timeout status=UNAVAILABLE",
            )
            stopBleScanQuietly()
            targetedScanAddress = null
            runOnUiThread {
                if (screenState == "loading") {
                    toast("Батарея не в сети")
                    showBatteriesScreen(asRootHome = true)
                }
            }
        }, TARGETED_SCAN_TIMEOUT_MS)
    }

    private fun cancelTargetedScan() {
        targetedScanToken++
        targetedScanAddress = null
        stopBleScanQuietly()
    }

    private fun cancelWakeSequence() {
        wakeToken++
        wakeInProgress = false
        wakeAttempt = 0
    }

    /**
     * Пробуждение MCU Daly безопасным чтением 0x90 (напряжение/ток/SOC).
     * Без записи настроек / MOS / SOC / reset.
     * Вызывать только после Notifications/CCCD ready.
     */
    @SuppressLint("MissingPermission")
    private fun startBmsWakeSequence() {
        cancelWakeSequence()
        wakeInProgress = true
        val token = ++wakeToken
        wakeAttempt = 0
        Log.i(BMS_WAKE_TAG, "Starting wake/read sequence")
        sendWakeReadAttempt(token)
    }

    @SuppressLint("MissingPermission")
    private fun sendWakeReadAttempt(token: Int) {
        if (token != wakeToken || !wakeInProgress) return
        if (!polling || bluetoothGatt == null || writeCharacteristic == null) {
            cancelWakeSequence()
            return
        }
        wakeAttempt++
        if (wakeAttempt > WAKE_MAX_ATTEMPTS) {
            Log.w(BMS_WAKE_TAG, "No response after $WAKE_MAX_ATTEMPTS attempts — start normal poll")
            finishWakeSequence(gotResponse = false)
            return
        }
        val frame = buildRequest(0x90)
        Log.i(
            BMS_WAKE_TAG,
            "Sending wake/read request #$wakeAttempt: ${bytesToHex(frame)}"
        )
        val ok = writeBleFrame(frame)
        if (!ok) {
            Log.w(BMS_WAKE_TAG, "wake write failed attempt=$wakeAttempt")
        }
        val delay = WAKE_RETRY_DELAYS_MS.getOrElse(wakeAttempt - 1) { 500L }
        mainHandler.postDelayed({
            if (token != wakeToken || !wakeInProgress) return@postDelayed
            Log.i(BMS_WAKE_TAG, "No response")
            sendWakeReadAttempt(token)
        }, delay)
    }

    private fun onWakeResponseReceived() {
        if (!wakeInProgress) return
        Log.i(BMS_WAKE_TAG, "Response received")
        Log.i(BMS_BLE_TAG, "BMS ONLINE")
        markBatteryOnlineFromValidResponse(selectedAddress, source = "wake_0x90")
        finishWakeSequence(gotResponse = true)
    }

    private fun finishWakeSequence(gotResponse: Boolean) {
        wakeInProgress = false
        wakeToken++
        if (!polling) return
        startRemoteWritePolling()
        mainHandler.postDelayed({
            if (polling) pollOnce()
        }, if (gotResponse) 120L else 300L)
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

    /**
     * Извлекает национальные 10 цифр RU-номера (без кода страны).
     * Не подставляет «7» в пустое поле.
     */
    private fun extractRuNationalDigits(phone: String): String {
        var digits = phone.filter { it.isDigit() }
        if (digits.startsWith("8") && digits.length == 11) {
            digits = digits.drop(1)
        } else if (digits.startsWith("7") && digits.length == 11) {
            digits = digits.drop(1)
        } else if (digits.startsWith("7") && digits.length > 10) {
            digits = digits.drop(1)
        }
        return digits.take(10)
    }

    private fun profilePhoneDigits(phone: String = profilePhoneStored()): String {
        val national = extractRuNationalDigits(phone)
        return if (national.length == 10) "7$national" else ""
    }

    /** E.164 для RU: +7XXXXXXXXXX. */
    private fun normalizePhoneE164(phone: String = profilePhoneStored()): String {
        val digits = profilePhoneDigits(phone)
        return if (digits.length == 11 && digits.startsWith("7")) "+$digits" else ""
    }

    private fun isValidRuPhone(phone: String): Boolean {
        return normalizePhoneE164(phone).isNotBlank()
    }

    /** Активная локальная сессия пользователя (не путать с удалением данных на сервере). */
    private fun isUserSessionActive(): Boolean {
        if (isServiceApp()) return true
        return userProfilePrefs().getBoolean("logged_in", false) &&
            isValidRuPhone(profilePhoneStored()) &&
            profileFullName().isNotBlank()
    }

    /** Единая проверка заполненности профиля пользователя (ФИО + телефон). */
    private fun isProfileComplete(): Boolean {
        if (isServiceApp()) return true
        return isUserSessionActive()
    }

    /**
     * Форматирует только национальную часть: 999 123-45-67.
     * Пустая строка остаётся пустой — без автоподстановки «+7» / «7».
     */
    private fun formatRuNationalMask(input: String): String {
        val n = extractRuNationalDigits(input)
        if (n.isEmpty()) return ""
        return buildString {
            append(n.take(3))
            if (n.length <= 3) return@buildString
            append(" ")
            append(n.drop(3).take(3))
            if (n.length <= 6) return@buildString
            append("-")
            append(n.drop(6).take(2))
            if (n.length <= 8) return@buildString
            append("-")
            append(n.drop(8).take(2))
        }
    }

    /** Полный отображаемый номер для профиля/гарантии: +7 (999) 123-45-67. */
    private fun formatRuPhoneMask(input: String): String {
        val n = extractRuNationalDigits(input)
        if (n.isEmpty()) return ""
        return buildString {
            append("+7")
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

    private fun attachRuNationalPhoneMask(edit: EditText) {
        edit.inputType = android.text.InputType.TYPE_CLASS_PHONE
        var selfChange = false
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (selfChange) return
                val formatted = formatRuNationalMask(s?.toString().orEmpty())
                if (formatted == s?.toString()) return
                selfChange = true
                edit.setText(formatted)
                edit.setSelection(formatted.length.coerceAtMost(edit.text?.length ?: 0))
                selfChange = false
            }
        })
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

    /** Строка «+7 | номер» без editable кода страны. */
    private fun buildRuPhoneInputRow(initialPhone: String = ""): Pair<LinearLayout, EditText> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = "+7"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.rgb(236, 238, 241), dp(12), Color.rgb(223, 229, 235), 1)
            setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(dp(56), dp(52)).apply { rightMargin = dp(8) })
        val national = EditText(this).apply {
            setText(formatRuNationalMask(initialPhone))
            hint = "999 123-45-67"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
        }
        attachRuNationalPhoneMask(national)
        row.addView(national, LinearLayout.LayoutParams(0, dp(52), 1f))
        return row to national
    }

    /**
     * Старт добавления АКБ. Без авторизации — Login/Register с pending «add_battery».
     * Гость не может сохранить АКБ локально без профиля.
     */
    private fun beginAddBatteryFlow() {
        if (!isServiceApp() && !isUserSessionActive()) {
            pendingAuthAction = "add_battery"
            showLoginScreen(allowBackToBatteries = true)
            return
        }
        pendingAuthAction = null
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
        if (!isServiceApp()) linkCurrentBatteryToUserAsync()
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

    /** Компактная info-плитка: иконка + заголовок и значение в 1 строку, без обрезки. */
    private fun infoOneLineTile(
        label: String,
        initial: String,
        @DrawableRes iconRes: Int? = null
    ): Pair<LinearLayout, TextView> {
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
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (iconRes != null) {
                addView(
                    tileIconView(iconRes, 20),
                    LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(4) }
                )
            }
            addView(titleView, LinearLayout.LayoutParams(0, dp(16), 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            minimumHeight = dp(78)
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            addView(titleRow, LinearLayout.LayoutParams(-1, -2))
            addView(valueView, LinearLayout.LayoutParams(-1, dp(24)).apply { topMargin = dp(4) })
        }
        return box to valueView
    }

    private fun capacityInnerTile(
        label: String,
        valueView: TextView,
        @DrawableRes iconRes: Int? = null
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = round(Color.rgb(248, 249, 251), dp(12), Color.rgb(223, 229, 235), 1)
            val titleView = TextView(this@MainActivity).apply {
                text = label
                textSize = 12f
                setTextColor(Color.rgb(111, 119, 129))
                typeface = interFont(650)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }
            addView(titleView, LinearLayout.LayoutParams(-1, -2))
            if (iconRes != null) {
                val valueRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        tileIconView(iconRes, 28),
                        LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(8) }
                    )
                    addView(valueView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                addView(valueRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            } else {
                addView(valueView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            enableWidthFit(titleView, 8f, 12f)
        }
    }

    /**
     * Маленькая иконка плитки из drawable-nodpi.
     * Без tint: PNG уже содержат нужный цвет; scale FIT в фиксированном контейнере.
     */
    private fun tileIconView(@DrawableRes iconRes: Int, sizeDp: Int): ImageView {
        return ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = null
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }
    }

    /** Единый контейнер иконки нижних metric-плиток. */
    private fun metricIconBlock(
        @DrawableRes iconRes: Int? = null,
        glyph: String = "",
        glyphColor: Int = Color.rgb(70, 85, 105)
    ): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(METRIC_ICON_BOX_DP), dp(METRIC_ICON_BOX_DP))
            fillMetricIconBlock(this, iconRes, glyph, glyphColor)
        }
    }

    private fun fillMetricIconBlock(
        host: FrameLayout,
        @DrawableRes iconRes: Int? = null,
        glyph: String = "",
        glyphColor: Int = Color.rgb(70, 85, 105)
    ) {
        host.removeAllViews()
        if (iconRes != null) {
            host.addView(
                tileIconView(iconRes, METRIC_ICON_DP),
                FrameLayout.LayoutParams(dp(METRIC_ICON_DP), dp(METRIC_ICON_DP), Gravity.CENTER)
            )
        } else if (glyph.isNotBlank()) {
            host.addView(
                TextView(this).apply {
                    text = glyph
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(glyphColor)
                    includeFontPadding = false
                    typeface = interFont(700)
                },
                FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            )
        }
    }

    /**
     * Нижняя metric-плитка:
     * строка 1 — название (целиком в плитку),
     * строка 2 — иконка + значение.
     */
    private fun classicMetricBox(
        icon: String,
        initial: String,
        label: String,
        @Suppress("UNUSED_PARAMETER") sub: String = "",
        valueSize: Float = 20f,
        titleSize: Float = 13f,
        truncate: Boolean = false,
        fixedHeight: Boolean = true,
        compact: Boolean = false,
        fitOneLine: Boolean = false,
        @DrawableRes iconRes: Int? = null,
        glyphColor: Int = Color.rgb(70, 85, 105)
    ): Triple<LinearLayout, TextView, FrameLayout> {
        val value = TextView(this).apply {
            text = initial
            textSize = valueSize
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(if (compact) 720 else 760)
            maxLines = if (truncate) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleView = TextView(this).apply {
            text = label
            textSize = titleSize
            setTextColor(Color.rgb(111, 119, 129))
            typeface = interFont(650)
            maxLines = 1
            isSingleLine = true
            ellipsize = null
            setLineSpacing(0f, 1.05f)
            includeFontPadding = false
        }
        val iconHost = metricIconBlock(
            iconRes = iconRes,
            glyph = if (iconRes == null) icon else "",
            glyphColor = glyphColor
        )
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconHost)
            addView(
                value,
                LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) }
            )
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 10 else 12), dp(10), dp(if (compact) 10 else 12), dp(10))
            if (fixedHeight) minimumHeight = dp(if (compact) 86 else 90)
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            addView(titleView, LinearLayout.LayoutParams(-1, -2))
            addView(valueRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        // Подгоняем title под ширину плитки, чтобы длинные надписи входили.
        enableWidthFit(titleView, 8f, titleSize.coerceAtLeast(8f))
        if (fitOneLine) {
            enableWidthFit(value, 6f, valueSize.coerceAtLeast(6f))
        }
        box.tag = null
        return Triple(box, value, iconHost)
    }

    /**
     * MOS-плитка: только название слева и read-only Switch справа (без иконки и без ВКЛ/ВЫКЛ).
     * Состояние берётся из BMS; переключение пользователем отключено.
     */
    private fun mosMetricBox(label: String): Pair<LinearLayout, Switch> {
        val titleView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.rgb(16, 17, 20))
            typeface = interFont(700)
            maxLines = 1
            isSingleLine = true
            ellipsize = null
            includeFontPadding = false
        }
        val sw = Switch(this).apply {
            isChecked = false
            isClickable = false
            isFocusable = false
            isEnabled = false
            styleMosSwitch(this)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            minimumHeight = dp(86)
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            isClickable = false
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        enableWidthFit(titleView, 9f, 14f)
        return box to sw
    }

    /** Зелёный ON / нейтральный серый OFF для Switch MOS. */
    private fun styleMosSwitch(sw: Switch) {
        val on = Color.rgb(31, 179, 90)
        val offThumb = Color.rgb(200, 205, 210)
        val offTrack = Color.rgb(220, 224, 228)
        sw.thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(on, offThumb)
        )
        sw.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.argb(120, 31, 179, 90), offTrack)
        )
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
            setPadding(hPad(), dp(8), hPad(), dp(22))
        }

        // Имя | Серийный номер | Версия
        val nameMetric = infoOneLineTile(
            "Имя устройства",
            selectedDeviceName.ifBlank { selectedAddress ?: "--" },
            R.drawable.icon_device
        )
        deviceNameValue = nameMetric.second
        val snMetric = infoOneLineTile(
            "Серийный номер",
            displayFactorySn().ifBlank { "--" },
            R.drawable.icon_serial
        )
        bmsSnValue = snMetric.second
        val verMetric = infoOneLineTile(
            "Версия BMS",
            displayBmsVersion().ifBlank { "--" },
            R.drawable.icon_bms_version
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
        socCol.addView(TextView(this).apply {
            text = "📊 График"
            textSize = 13f
            typeface = interFont(700)
            setTextColor(Color.rgb(25, 118, 210))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
            isClickable = true
            isFocusable = true
            contentDescription = "Открыть графики заряда и разряда"
            setOnClickListener { openChartsScreen() }
        }, LinearLayout.LayoutParams(-1, -2))
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
            capacityInnerTile("Полная ёмкость", fullCapacityValue!!, R.drawable.icon_capacity),
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
            "", "-- В", "Напряжение",
            valueSize = 20f, titleSize = 13f, iconRes = R.drawable.icon_voltage
        )
        voltageValue = voltageMetric.second
        val currentMetric = classicMetricBox(
            "",
            "-- А",
            "Ток",
            valueSize = 20f,
            titleSize = 13f,
            iconRes = R.drawable.icon_current
        )
        currentValue = currentMetric.second
        currentSubValue = null
        addPair(voltageMetric.first, currentMetric.first)

        val tempMetric = classicMetricBox(
            "", "-- °C", "Температура",
            valueSize = 20f, titleSize = 13f, iconRes = R.drawable.icon_temperature
        )
        t1Text = tempMetric.second
        val cellsMetric = classicMetricBox(
            "",
            data.cellCount?.toString() ?: "--",
            "Количество ячеек",
            valueSize = 20f,
            titleSize = 12f,
            iconRes = R.drawable.icon_cells
        )
        cellCountValue = cellsMetric.second
        addPair(tempMetric.first, cellsMetric.first)

        // Скрытые TextView — совместимость с applyMos/updateDashboardUi (ВКЛ/ВЫКЛ на экране не показываем).
        chargeMosValue = TextView(this).apply { visibility = View.GONE }
        dischargeMosValue = TextView(this).apply { visibility = View.GONE }
        val chargeMetric = mosMetricBox("MOS зарядки")
        chargeMosSwitch = chargeMetric.second
        chargeMosDot = null
        val dischargeMetric = mosMetricBox("MOS разрядки")
        dischargeMosSwitch = dischargeMetric.second
        dischargeMosDot = null
        addPair(chargeMetric.first, dischargeMetric.first)

        val statusMetric = classicMetricBox(
            "✓", "Норма", "Статус BMS",
            valueSize = 18f, titleSize = 13f,
            glyphColor = Color.rgb(31, 179, 90)
        )
        balanceValue = statusMetric.second
        balanceIconHost = statusMetric.third
        balanceDot = null
        val stateMetric = classicMetricBox(
            "", "—", "Состояние",
            valueSize = 18f, titleSize = 13f, iconRes = R.drawable.icon_state
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
            text = ""
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            visibility = View.GONE
        }
        overallStatusBanner?.addView(overallStatusTitle)
        overallStatusBanner?.addView(
            overallStatusSub,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        overallStatusBanner?.isClickable = true
        overallStatusBanner?.isFocusable = true
        overallStatusBanner?.setOnClickListener {
            // Единый экран Диагностики (маршрут Поддержка → Диагностика).
            showSupportDiagnostics()
        }
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
            "batteries", "dashboard", "journal", "support", "profile", "login", "register",
            "manage", "service", "qtc", "auth", "search"
        )
    }

    private fun enterScreen(newState: String, track: Boolean = true) {
        val previous = screenState
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
        if (previous == "batteries" && newState != "batteries") {
            stopBatteriesPresenceScan()
        }
        if (previous.startsWith("qr") && !newState.startsWith("qr")) {
            stopQrCamera()
        }
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
            "journal" -> {
                if (resolveCurrentBmsPresence() == BatteryPresenceState.ONLINE) {
                    showJournalScreen()
                } else {
                    goHomeFromMenu()
                }
            }
            "support" -> showSupportScreen()
            "profile" -> showProfileScreen()
            "login" -> showLoginScreen()
            "register" -> showRegisterScreen()
            "manage" -> showManageScreen()
            "config_check" -> showConfigCheckScreen()
            "support_diagnostics" -> showSupportDiagnostics()
            "charts" -> showChartsScreen(reload = false)
            "service" -> showServiceScreen()
            "qtc" -> showQtcScreen()
            "auth" -> showProfileScreen()
            "search" -> showSearchScreen()
            else -> goHomeFromMenu()
        }
    }

    /** Возврат по UI-стеку. true — обработано; false — на корне, можно выйти из приложения. */
    private fun navigateBackUi(): Boolean {
        when (screenState) {
            "login" -> {
                pendingAuthAction = null
                showBatteriesScreen(asRootHome = true)
                return true
            }
            "register" -> {
                showLoginScreen(allowBackToBatteries = true)
                return true
            }
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
            "support" -> {
                // Просмотр/редактирование обращения → Диагностика (не Главная).
                if (supportMode == "edit") {
                    editingWarrantyLocalId = null
                    clearWarrantyFormState()
                    supportMode = "home"
                    showSupportDiagnostics()
                    return true
                }
                if (supportMode == "new" || supportMode == "list") {
                    editingWarrantyLocalId = null
                    clearWarrantyFormState()
                    supportMode = "home"
                    showSupportScreen()
                    return true
                }
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
                    // Пункт скрыт из нижнего меню клиента; route/логика Settings сохранена.
                    if (screenState == "manage") return@setOnClickListener
                    showManageScreen()
                } else if (text.contains("QR")) {
                    if (screenState == "qr_input" || screenState == "qr_scan" || screenState == "qr_result") {
                        return@setOnClickListener
                    }
                    showQrInputScreen()
                } else if (text.contains("Журнал")) {
                    if (screenState == "journal") {
                        if (resolveCurrentBmsPresence() == BatteryPresenceState.ONLINE) {
                            return@setOnClickListener
                        }
                        // Не оставляем «выбранным» журнал, если BMS уже неактивна.
                        openJournalIfAllowed()
                        return@setOnClickListener
                    }
                    openJournalIfAllowed()
                } else if (text.contains("Поддержка") || text.contains("Техподдержка")) {
                    if (screenState == "support" && supportMode == "home") return@setOnClickListener
                    supportMode = "home"
                    editingWarrantyLocalId = null
                    clearWarrantyFormState()
                    showSupportScreen()
                } else if (text.contains("Профиль")) {
                    if (screenState == "profile") return@setOnClickListener
                    showProfileScreen()
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
                // Вместо «Настройки»: существующий QR-экран (showQrInputScreen).
                navItem(this, "▦\nQR-код", selectedTab == "qr")
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
            "checking" -> "Идёт инициализация BMS"
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

    /** Клиент: записать только writable MISMATCH параметры шаблона (не READ_FAILED/missing). */
    private fun startClientTemplateApply() {
        if (serviceWriteActive) {
            toast("Запись уже выполняется")
            return
        }
        if (bluetoothGatt == null || writeCharacteristic == null) {
            toast("Нет подключения к BMS")
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
        Log.i(BLE_LOG_TAG, "CONFIG FIX START id=${template.id} version=${template.version} series=$series family=$family")
        for (parameter in orderedTemplateParameters(template.parameters)) {
            if (!parameter.writable) continue
            if (!parameter.enabled) continue
            if (shouldSkipTemplateParameter(parameter, family)) continue
            if (parameter.key == "series_cell_count") continue
            val expected = parameter.expected ?: parameter.expectedBySeries[series] ?: continue
            val actual = templateParameterActual(parameter)
            // READ_FAILED / missing: actual неизвестен — не пишем.
            if (!templateParameterHasActual(parameter) || actual == null) {
                Log.i(
                    BLE_LOG_TAG,
                    "CONFIG FIX SKIP ${parameter.key}: no actual (READ_FAILED/missing)"
                )
                continue
            }
            if (templateParameterMatches(parameter, expected)) {
                upsertServiceWriteResult(
                    ServiceWriteResult(
                        key = parameter.key,
                        label = parameter.label,
                        expected = expected,
                        actual = actual,
                        unit = parameter.unit,
                        ok = true
                    )
                )
                continue
            }
            Log.i(
                BLE_LOG_TAG,
                "CONFIG FIX parameter=${parameter.key} actual=$actual expected=$expected ${parameter.unit}"
            )
            val command = templateParameterWriteCommand(id, parameter, expected) ?: continue
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
            refreshDiagnosticsScreenAfterClientFix()
            return
        }
        serviceWriteQueue = queue
        serviceWriteTotal = queue.size
        serviceWriteDone = 0
        serviceWriteActive = true
        toast("Исправляем конфигурацию…")
        refreshDiagnosticsScreenAfterClientFix()
        val first = serviceWriteQueue.poll()
        if (first != null) startRemoteWrite(first)
    }

    /** Обновить открытый экран Диагностики после client fix. */
    private fun refreshDiagnosticsScreenAfterClientFix() {
        when (screenState) {
            "support_diagnostics" -> showSupportDiagnostics()
            "config_check" -> showConfigCheckScreen()
            else -> showConfigCheckScreen()
        }
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
            if (clientTemplateApplyMode &&
                (screenState == "config_check" || screenState == "support_diagnostics")
            ) {
                refreshDiagnosticsScreenAfterClientFix()
            }
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
            Log.i(BLE_LOG_TAG, "CONFIG FIX END failed=$failed total=${serviceWriteResults.size}")
            if (serviceWriteResults.isNotEmpty() && failed == 0) {
                toast("Конфигурация исправлена")
            } else if (failed > 0) {
                toast("Не все параметры удалось исправить")
            } else {
                toast("Запись завершена")
            }
            if (screenState == "dashboard") updateDashboardUi()
            refreshDiagnosticsScreenAfterClientFix()
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
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val tabs = listOf(
            "general" to "Основные",
            "voltage_current" to "Напряжение",
            "temperature" to "Температура",
            "balancing" to "Настройка балансировки",
            "cell" to "Параметры элемента"
        )
        var selectedView: View? = null
        for ((key, title) in tabs) {
            val btn = manageTabButton(key, title)
            row.addView(btn, marginLp(-2, dp(42), 0, 0, 8, 0))
            if (manageSection == key) selectedView = btn
        }
        scroll.addView(row)
        // Автоматически сдвигаем выбранную плитку в видимую область.
        selectedView?.let { target ->
            scroll.post {
                val left = target.left - dp(16)
                val right = target.right + dp(16)
                val visibleLeft = scroll.scrollX
                val visibleRight = visibleLeft + scroll.width
                when {
                    left < visibleLeft -> scroll.smoothScrollTo(left.coerceAtLeast(0), 0)
                    right > visibleRight -> scroll.smoothScrollTo((right - scroll.width).coerceAtLeast(0), 0)
                    else -> {
                        // Центрируем выбранный пункт, если места достаточно.
                        val center = target.left + target.width / 2 - scroll.width / 2
                        scroll.smoothScrollTo(center.coerceAtLeast(0), 0)
                    }
                }
            }
        }
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
                if (screenState == "support_diagnostics") showSupportDiagnostics()
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
        refreshConfigCheckScreensIfVisible()
    }

    private fun refreshConfigCheckScreensIfVisible() {
        val refresh = {
            when (screenState) {
                "config_check" -> showConfigCheckScreen()
                "support_diagnostics" -> showSupportDiagnostics()
                "dashboard" -> updateDashboardUi()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refresh()
        } else {
            runOnUiThread { refresh() }
        }
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
            "checking" -> "Идёт инициализация BMS"
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

    /**
     * Экран по тапу «Батарея в норме» на Главной.
     * Содержимое совпадает с Поддержка → Диагностика; дополнительно — запись шаблона при отклонениях.
     */
    private fun showConfigCheckScreen() {
        enterScreen("config_check")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Диагностика",
                selectedDeviceName.ifBlank { selectedAddress ?: "" },
                showBack = true
            )
        )
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }
        // Тот же presentation-слой, что и Поддержка → Диагностика.
        appendDiagnosticsConfigPresentation(content)
        appendDiagnosticsActionButtons(content)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    /**
     * Кнопки Диагностики: «Исправить» при writable MISMATCH, иначе «ОК» → Главная.
     * Использует существующий startClientTemplateApply() / WRITE→READBACK→VERIFY.
     */
    private fun appendDiagnosticsActionButtons(content: LinearLayout) {
        val result = currentTemplateCheck()
        if (result == null ||
            result.status == "checking" ||
            result.status == "unavailable" ||
            serverTemplateFetchStatus == "fetching"
        ) {
            return
        }

        if (serviceWriteActive && clientTemplateApplyMode) {
            content.addView(TextView(this).apply {
                text = "Исправляем конфигурацию…  $serviceWriteDone / $serviceWriteTotal"
                textSize = 15f
                typeface = interFont(700)
                setTextColor(Color.rgb(16, 17, 20))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = round(Color.rgb(241, 243, 245), dp(14), Color.rgb(223, 229, 235), 1)
            }, marginLp(-1, -2, 0, 14, 0, 8))
            return
        }

        val writableKeys = activeServerTemplate?.parameters
            ?.filter { it.writable && it.enabled }
            ?.map { it.key }
            ?.toSet()
            .orEmpty()
        val mismatchItems = (if (result.items.isNotEmpty()) result.items else result.mismatches)
            .filter {
                it.status == "mismatch" &&
                    !isHiddenDiagnosticsParamKey(it.key) &&
                    (writableKeys.isEmpty() || it.key in writableKeys)
            }
        val hasWritableMismatch = mismatchItems.isNotEmpty()

        if (hasWritableMismatch) {
            content.addView(TextView(this).apply {
                text = "Исправить"
                textSize = 16f
                typeface = interFont(780)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = round(
                    if (!serviceWriteActive) red else Color.rgb(160, 160, 160),
                    dp(14),
                    Color.TRANSPARENT,
                    0
                )
                isEnabled = !serviceWriteActive
                isClickable = !serviceWriteActive
                isFocusable = !serviceWriteActive
                setOnClickListener {
                    if (bluetoothGatt == null || writeCharacteristic == null) {
                        toast("Нет подключения к BMS")
                        return@setOnClickListener
                    }
                    startClientTemplateApply()
                }
            }, marginLp(-1, -2, 0, 14, 0, 8))
            return
        }

        // Нет writable MISMATCH — кнопка ОК на Главную текущей BMS.
        content.addView(TextView(this).apply {
            text = "ОК"
            textSize = 16f
            typeface = interFont(780)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                showDashboardScreen(asRootHome = true)
            }
        }, marginLp(-1, -2, 0, 14, 0, 8))
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
        when (supportMode) {
            "new" -> renderWarrantyForm(content, null)
            "edit" -> renderWarrantyForm(content, editingWarrantyLocalId)
            "list" -> renderWarrantyList(content)
            else -> {
                // Базовое состояние: только контакты + диагностика.
                content.addView(contactCard, marginLp(-1, -2, 0, 0, 0, 10))
                content.addView(TextView(this).apply {
                    text = "ДИАГНОСТИКА"
                    textSize = 15f
                    typeface = interFont(760)
                    setTextColor(Color.rgb(16, 17, 20))
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                    background = round(Color.WHITE, dp(14), red, 1)
                    setOnClickListener { showSupportDiagnostics() }
                }, marginLp(-1, -2, 0, 0, 0, 10))
            }
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("support"), LinearLayout.LayoutParams(-1, dp(70)))

        setContentView(root)
        updateWarrantyMediaText()
        if (supportMode == "list") refreshWarrantyStatuses(false)
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



    /**
     * Экран входа: только поиск пользователя по телефону.
     * Не создаёт профиль — для этого showRegisterScreen.
     */
    private fun showLoginScreen(
        prefillPhone: String = authPendingNationalPhone,
        allowBackToBatteries: Boolean = true,
    ) {
        if (isServiceApp()) {
            showSplashScreen()
            return
        }
        // Не делаем Login корнем приложения: Back → «Мои батареи».
        enterScreen("login", track = false)
        currentTab = "profile"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header("Вход", showBack = allowBackToBatteries, showBrand = true))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "Вход"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 8))
        content.addView(TextView(this).apply {
            text = "Введите номер телефона. Если профиль ещё не создан — зарегистрируйтесь."
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, 0, 0, dp(12))
        })

        val card = card()
        card.addView(TextView(this).apply {
            text = "Номер телефона *"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, 0, 0, dp(5))
        })
        val initial = prefillPhone.ifBlank { profilePhoneStored() }
        val (phoneRow, phoneNational) = buildRuPhoneInputRow(initial)
        card.addView(phoneRow, LinearLayout.LayoutParams(-1, -2))
        content.addView(card)

        loginStatusText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(loginStatusText)

        content.addView(TextView(this).apply {
            text = "ВОЙТИ"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                val national = extractRuNationalDigits(phoneNational.text.toString())
                authPendingNationalPhone = national
                if (national.length != 10) {
                    toast("Укажите 10 цифр номера")
                    return@setOnClickListener
                }
                val e164 = normalizePhoneE164(national)
                if (e164.isBlank()) {
                    toast("Укажите полный номер телефона")
                    return@setOnClickListener
                }
                performUserLogin(e164)
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))

        content.addView(TextView(this).apply {
            text = "Зарегистрироваться"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                authPendingNationalPhone = extractRuNationalDigits(phoneNational.text.toString())
                showRegisterScreen()
            }
        }, marginLp(-1, -2, 0, 8, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    /**
     * Экран регистрации: создаёт пользователя на backend (phone UNIQUE).
     */
    private fun showRegisterScreen(prefillPhone: String = authPendingNationalPhone) {
        if (isServiceApp()) {
            showSplashScreen()
            return
        }
        enterScreen("register", track = false)
        currentTab = "profile"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(header("Регистрация", showBack = true, showBrand = true))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        content.addView(TextView(this).apply {
            text = "Регистрация"
            textSize = 22f
            typeface = interFont(750)
            setTextColor(Color.rgb(16, 17, 20))
        }, marginLp(-1, -2, 0, 4, 0, 8))
        content.addView(TextView(this).apply {
            text = "Создайте профиль по номеру телефона. Номер будет уникальным идентификатором."
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, 0, 0, dp(12))
        })

        val card = card()
        card.addView(TextView(this).apply {
            text = "ФИО *"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, 0, 0, dp(5))
        })
        val name = EditText(this).apply {
            setText(profileFullName())
            hint = "Иванов Иван Иванович"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = round(Color.rgb(246, 247, 249), dp(12), Color.rgb(223, 229, 235), 1)
        }
        card.addView(name, LinearLayout.LayoutParams(-1, dp(52)))
        card.addView(TextView(this).apply {
            text = "Номер телефона *"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, dp(10), 0, dp(5))
        })
        val initial = prefillPhone.ifBlank { profilePhoneStored() }
        val (phoneRow, phoneNational) = buildRuPhoneInputRow(initial)
        card.addView(phoneRow, LinearLayout.LayoutParams(-1, -2))
        content.addView(card)

        loginStatusText = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, dp(10), 0, 0)
        }
        content.addView(loginStatusText)

        content.addView(TextView(this).apply {
            text = "ЗАРЕГИСТРИРОВАТЬСЯ"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                val nameText = name.text.toString().trim()
                val national = extractRuNationalDigits(phoneNational.text.toString())
                authPendingNationalPhone = national
                if (nameText.isBlank()) {
                    toast("Укажите ФИО")
                    return@setOnClickListener
                }
                if (national.length != 10) {
                    toast("Укажите 10 цифр номера")
                    return@setOnClickListener
                }
                val e164 = normalizePhoneE164(national)
                if (e164.isBlank()) {
                    toast("Укажите полный номер телефона")
                    return@setOnClickListener
                }
                performUserRegister(
                    name = nameText,
                    phone = e164,
                    email = userProfilePrefs().getString("email", "")?.trim().orEmpty(),
                    birth = userProfilePrefs().getString("birth", "")?.trim().orEmpty(),
                )
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))

        content.addView(TextView(this).apply {
            text = "Уже есть профиль? Войти"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                authPendingNationalPhone = extractRuNationalDigits(phoneNational.text.toString())
                showLoginScreen()
            }
        }, marginLp(-1, -2, 0, 8, 0, 0))

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    /**
     * Login: поиск пользователя. Не создаёт запись в БД.
     */
    private fun performUserLogin(phoneE164: String) {
        if (loginLoading) return
        loginLoading = true
        val generation = ++loginGeneration
        loginStatusText?.text = "Вход…"
        loginStatusText?.setTextColor(Color.rgb(90, 90, 90))
        saveBatteries(emptyList())
        selectedAddress = null
        selectedDeviceName = ""
        disconnectGatt()

        thread {
            val result = requestUserAuth(USER_LOGIN_PATH, phone = phoneE164, name = null, email = null, birth = null)
            runOnUiThread {
                if (generation != loginGeneration) return@runOnUiThread
                loginLoading = false
                val (httpCode, body) = result ?: (0 to null)
                if (body == null) {
                    loginStatusText?.text = "Не удалось связаться с сервером. Проверьте интернет и повторите."
                    toast("Ошибка входа. Попробуйте ещё раз.")
                    return@runOnUiThread
                }
                val error = body.optString("error")
                if (httpCode == 404 || error == "user_not_found" || !body.optBoolean("ok")) {
                    if (error == "user_not_found" || httpCode == 404) {
                        loginStatusText?.setTextColor(Color.rgb(180, 35, 45))
                        loginStatusText?.text =
                            "Пользователь с таким номером не найден.\nЗарегистрируйтесь, чтобы создать профиль."
                        toast("Пользователь не найден")
                        // Кнопка перехода уже есть на экране; дополнительно предложим диалог.
                        AlertDialog.Builder(this)
                            .setTitle("Пользователь не найден")
                            .setMessage("Зарегистрируйтесь, чтобы создать профиль.")
                            .setPositiveButton("Зарегистрироваться") { _, _ -> showRegisterScreen() }
                            .setNegativeButton("Отмена", null)
                            .show()
                        return@runOnUiThread
                    }
                    loginStatusText?.text = "Ошибка входа. Проверьте номер телефона."
                    toast("Ошибка входа")
                    return@runOnUiThread
                }
                applyAuthSuccess(body, fallbackPhone = phoneE164, fallbackName = "")
            }
        }
    }

    /**
     * Registration: создание пользователя. Не делает silent login upsert.
     */
    private fun performUserRegister(name: String, phone: String, email: String, birth: String) {
        if (loginLoading) return
        loginLoading = true
        val generation = ++loginGeneration
        loginStatusText?.text = "Регистрация…"
        loginStatusText?.setTextColor(Color.rgb(90, 90, 90))
        saveBatteries(emptyList())
        selectedAddress = null
        selectedDeviceName = ""
        disconnectGatt()

        thread {
            val result = requestUserAuth(
                USER_REGISTER_PATH,
                phone = phone,
                name = name,
                email = email,
                birth = birth,
            )
            runOnUiThread {
                if (generation != loginGeneration) return@runOnUiThread
                loginLoading = false
                val (httpCode, body) = result ?: (0 to null)
                if (body == null) {
                    showRegisterFailure(
                        "Не удалось подключиться к серверу. Проверьте интернет и повторите.",
                        toastText = "Нет связи с сервером",
                    )
                    return@runOnUiThread
                }
                val error = body.optString("error")
                if (httpCode == 409 || error == "phone_already_exists") {
                    loginStatusText?.setTextColor(Color.rgb(180, 35, 45))
                    loginStatusText?.text =
                        "Пользователь с таким номером уже зарегистрирован.\nВыполните вход."
                    AlertDialog.Builder(this)
                        .setTitle("Уже зарегистрирован")
                        .setMessage("Пользователь с таким номером уже зарегистрирован. Выполните вход.")
                        .setPositiveButton("Войти") { _, _ -> showLoginScreen() }
                        .setNegativeButton("Отмена", null)
                        .show()
                    return@runOnUiThread
                }
                if (httpCode in 200..299 && body.optBoolean("ok")) {
                    applyAuthSuccess(body, fallbackPhone = phone, fallbackName = name)
                    return@runOnUiThread
                }
                val message = registerFailureMessage(httpCode, error)
                Log.e(
                    "AuthRegister",
                    "Registration failed http=$httpCode error=$error message=$message",
                )
                showRegisterFailure(message)
            }
        }
    }

    /** Понятное сообщение по HTTP/error коду регистрации (без stack trace). */
    private fun registerFailureMessage(httpCode: Int, error: String): String {
        return when {
            error == "invalid_phone" || httpCode == 400 && error.contains("phone") ->
                "Некорректный номер телефона"
            error == "name_required" ->
                "Укажите ФИО для регистрации"
            error == "unauthorized" || httpCode == 401 ->
                "Ошибка авторизации приложения на сервере"
            error == "not_found" || httpCode == 404 ->
                "Сервер не поддерживает регистрацию (endpoint недоступен). Обновите backend."
            httpCode in 500..599 ->
                "Ошибка сервера. Попробуйте позже."
            error.isNotBlank() ->
                "Не удалось зарегистрироваться ($error)"
            else ->
                "Не удалось зарегистрироваться (HTTP $httpCode)"
        }
    }

    private fun showRegisterFailure(message: String, toastText: String = "Ошибка регистрации") {
        loginStatusText?.setTextColor(Color.rgb(180, 35, 45))
        loginStatusText?.text = message
        toast(toastText)
    }

    private fun applyAuthSuccess(body: JSONObject, fallbackPhone: String, fallbackName: String) {
        val user = body.optJSONObject("user")
        val remotePhone = normalizePhoneE164(user?.optString("phone").orEmpty().ifBlank { fallbackPhone })
        val remoteName = user?.optString("name").orEmpty().ifBlank { fallbackName }
        val avatarUrl = user?.optString("avatar_url").orEmpty().trim()
        val localAvatar = profileAvatarFile(remotePhone)
        userProfilePrefs().edit()
            .putBoolean("logged_in", true)
            .putString("name", remoteName)
            .putString("phone", remotePhone)
            .putString("email", user?.optString("email").orEmpty())
            .putString("birth", user?.optString("birth").orEmpty())
            .putString("avatar_url", avatarUrl)
            .putString(
                "avatar_uri",
                if (localAvatar.exists()) localAvatar.absolutePath else "",
            )
            .apply()
        authPendingNationalPhone = extractRuNationalDigits(remotePhone)
        // Восстанавливаем аватар с backend (не полагаемся только на локальный URI).
        restoreProfileAvatarAfterLogin(remotePhone, avatarUrl)
        val batteries = mapServerBatteriesToSaved(body.optJSONArray("batteries"))
        saveBatteries(batteries)
        val pending = pendingAuthAction
        pendingAuthAction = null
        clearUiBackStack()
        if (pending == "add_battery") {
            if (batteries.isNotEmpty()) {
                toast("АКБ: ${batteries.size}")
            }
            beginAddBatteryFlow()
            return
        }
        showBatteriesScreen(asRootHome = true)
        if (batteries.isEmpty()) {
            toast("Готово")
        } else {
            toast("АКБ: ${batteries.size}")
        }
    }

    /**
     * HTTP к users login/register/profile.
     * @return Pair(httpCode, JSON) или null при сетевой ошибке.
     */
    private fun requestUserAuth(
        path: String,
        phone: String,
        name: String?,
        email: String?,
        birth: String?,
    ): Pair<Int, JSONObject>? {
        return try {
            val body = JSONObject().apply {
                putBmsApiKey(this)
                put("phone", phone)
                if (name != null) put("name", name)
                if (email != null) put("email", email)
                if (birth != null) put("birth", birth)
            }
            val url = adminServerUrl(path)
            val maskedPhone = maskPhoneForLog(phone)
            if (BuildConfig.DEBUG) {
                val safeKeys = body.keys().asSequence().toList().filter { it != "api_key" }
                Log.i(
                    "AuthRegister",
                    "REQUEST method=POST url=$url phone=$maskedPhone " +
                        "has_name=${name != null} has_email=${!email.isNullOrBlank()} " +
                        "body_keys=$safeKeys",
                )
            }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
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
            Log.i(BLE_LOG_TAG, "USER AUTH $path http=$code body=${response.take(300)}")
            if (BuildConfig.DEBUG) {
                Log.i("AuthRegister", "RESPONSE http=$code body=${response.take(400)}")
            }
            if (response.isBlank()) null else code to JSONObject(response)
        } catch (e: Exception) {
            Log.e("AuthRegister", "Registration/network exception: ${e.javaClass.simpleName}: ${e.message}", e)
            Log.w(BLE_LOG_TAG, "USER AUTH $path EXCEPTION: ${e.message}")
            null
        }
    }

    /** Маскирует телефон для логов: +7999***4567. */
    private fun maskPhoneForLog(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 6) return "***"
        return "+${digits.take(4)}***${digits.takeLast(4)}"
    }

    private fun mapServerBatteriesToSaved(arr: JSONArray?): List<SavedBattery> {
        if (arr == null) return emptyList()
        val out = mutableListOf<SavedBattery>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val address = item.optString("bluetooth_address").trim()
                .ifBlank { item.optString("bluetooth_id").trim() }
                .ifBlank { item.optString("bms_uid").trim() }
            if (address.isBlank()) continue
            val key = address.uppercase()
            if (!seen.add(key)) continue
            val name = item.optString("advertised_name").ifBlank {
                item.optString("bluetooth_name")
            }
            out += SavedBattery(
                address = address,
                bluetoothName = name,
                customName = "",
                soc = if (item.has("soc") && !item.isNull("soc")) item.optDouble("soc") else null,
                capacityAh = if (item.has("capacity_ah") && !item.isNull("capacity_ah")) {
                    item.optDouble("capacity_ah")
                } else null,
                lastSeenAt = item.optLong("last_seen_at", 0L)
            )
        }
        return out.sortedByDescending { it.lastSeenAt }
    }

    /**
     * Logout: только локальная сессия.
     * Не удаляет профиль/аватар/АКБ на backend и не стирает per-user cache аватара.
     */
    private fun logoutUserSession() {
        if (isServiceApp()) return
        loginGeneration += 1
        loginLoading = false
        avatarUploadState = "Idle"
        disconnectGatt()
        selectedAddress = null
        selectedDeviceName = ""
        saveBatteries(emptyList())
        clearUiBackStack()
        stopBatteriesPresenceScan()
        // Очищаем runtime-сессию. Серверный avatar/profile/АКБ не трогаем.
        userProfilePrefs().edit().clear().apply()
        supportPrefs?.edit()?.clear()?.apply()
        loginLoading = false
        pendingAuthAction = null
        authPendingNationalPhone = ""
        showBatteriesScreen(asRootHome = true)
        toast("Вы вышли из профиля")
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Выход")
            .setMessage("Вы действительно хотите выйти из профиля?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Выйти") { _, _ -> logoutUserSession() }
            .show()
    }

    /**
     * Локальный cache-файл аватара, привязанный к телефону пользователя (E.164 digits).
     * Не использует один глобальный avatar.jpg для всех аккаунтов.
     */
    private fun profileAvatarFile(phone: String = normalizePhoneE164()): File {
        val dir = File(filesDir, "profile_avatars").apply { mkdirs() }
        val digits = phone.filter { it.isDigit() }
        val name = if (digits.isNotBlank()) "avatar_$digits.jpg" else "avatar_pending.jpg"
        return File(dir, name)
    }

    private fun showProfileAvatarChooser() {
        AlertDialog.Builder(this)
            .setTitle("Фото профиля")
            .setItems(arrayOf("Сделать фото", "Выбрать из галереи", "Отмена")) { dialog, which ->
                when (which) {
                    0 -> takeProfilePhoto()
                    1 -> pickProfileAvatarFromGallery()
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun takeProfilePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            profileCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchProfileCamera()
    }

    private fun launchProfileCamera() {
        try {
            val file = File(File(filesDir, "profile_avatars").apply { mkdirs() }, "avatar_capture.jpg")
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            profilePendingCameraUri = uri
            profileCameraLauncher.launch(uri)
        } catch (e: Exception) {
            profilePendingCameraUri = null
            Log.w(BLE_LOG_TAG, "PROFILE CAMERA launch failed: ${e.message}")
            toast("Не удалось открыть камеру")
        }
    }

    private fun pickProfileAvatarFromGallery() {
        profileGalleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * EXIF → resize/compress → локальный cache по user phone → upload на backend.
     * При ошибке upload предыдущий аватар не удаляется.
     */
    private fun persistProfileAvatarFromUri(source: Uri) {
        if (avatarUploadState == "Uploading") {
            toast("Фото уже загружается…")
            return
        }
        val phone = normalizePhoneE164()
        if (phone.isBlank() || !isUserSessionActive()) {
            toast("Сначала войдите в профиль")
            return
        }
        avatarUploadState = "Uploading"
        updateProfileAvatarStatusUi()
        thread {
            val outFile = profileAvatarFile(phone)
            val previousBytes = if (outFile.exists()) outFile.readBytes() else null
            try {
                val bitmap = OrientedBitmapLoader.loadOrientedBitmap(
                    context = this,
                    uri = source,
                    maxSide = 1024,
                )
                if (bitmap == null) {
                    avatarUploadState = "Error"
                    runOnUiThread {
                        updateProfileAvatarStatusUi()
                        toast("Не удалось прочитать изображение")
                    }
                    return@thread
                }
                val jpegBytes = ByteArrayOutputStream().use { baos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 88, baos)
                    bitmap.recycle()
                    baos.toByteArray()
                }
                // Сначала локальный preview; при ошибке upload откатим к previousBytes.
                outFile.outputStream().use { it.write(jpegBytes) }
                userProfilePrefs().edit()
                    .putString("avatar_uri", outFile.absolutePath)
                    .apply()
                runOnUiThread {
                    if (screenState == "profile") showProfileScreen()
                    else updateProfileAvatarStatusUi()
                }

                val uploaded = uploadProfileAvatarJpeg(phone, jpegBytes)
                if (uploaded == null) {
                    if (previousBytes != null) {
                        outFile.outputStream().use { it.write(previousBytes) }
                        userProfilePrefs().edit()
                            .putString("avatar_uri", outFile.absolutePath)
                            .apply()
                    } else if (outFile.exists()) {
                        // Новый файл без успешного upload не считаем подтверждённым сервером.
                        outFile.delete()
                        userProfilePrefs().edit().remove("avatar_uri").apply()
                    }
                    avatarUploadState = "Error"
                    runOnUiThread {
                        updateProfileAvatarStatusUi()
                        toast("Не удалось загрузить фото. Попробуйте еще раз.")
                        if (screenState == "profile") showProfileScreen()
                    }
                    return@thread
                }
                val avatarUrl = uploaded.optString("avatar_url").orEmpty()
                userProfilePrefs().edit()
                    .putString("avatar_uri", outFile.absolutePath)
                    .putString("avatar_url", avatarUrl)
                    .apply()
                avatarUploadState = "Success"
                runOnUiThread {
                    updateProfileAvatarStatusUi()
                    toast("Фото профиля сохранено")
                    if (screenState == "profile") showProfileScreen()
                }
            } catch (e: Exception) {
                Log.w(BLE_LOG_TAG, "PROFILE AVATAR persist failed: ${e.message}")
                if (previousBytes != null) {
                    try {
                        outFile.outputStream().use { it.write(previousBytes) }
                    } catch (_: Exception) { /* ignore */ }
                }
                avatarUploadState = "Error"
                runOnUiThread {
                    updateProfileAvatarStatusUi()
                    toast("Не удалось загрузить фото. Попробуйте еще раз.")
                    if (screenState == "profile") showProfileScreen()
                }
            }
        }
    }

    /**
     * Upload JPEG аватара на backend.
     * @return user JSONObject при успехе, иначе null.
     */
    private fun uploadProfileAvatarJpeg(phone: String, jpegBytes: ByteArray): JSONObject? {
        return try {
            val body = JSONObject().apply {
                putBmsApiKey(this)
                put("phone", phone)
                put("image_base64", Base64.encodeToString(jpegBytes, Base64.NO_WRAP))
            }
            val conn = (URL(adminServerUrl(USER_AVATAR_PATH)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                applyBmsApiAuth(this)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val raw = try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
            } finally {
                conn.disconnect()
            }
            val json = try {
                JSONObject(raw.ifBlank { "{}" })
            } catch (_: Exception) {
                JSONObject()
            }
            if (code in 200..299 && json.optBoolean("ok")) {
                json.optJSONObject("user")
            } else {
                Log.w(BLE_LOG_TAG, "PROFILE AVATAR upload failed http=$code error=${json.optString("error")}")
                null
            }
        } catch (e: Exception) {
            Log.w(BLE_LOG_TAG, "PROFILE AVATAR upload error: ${e.message}")
            null
        }
    }

    /**
     * После login/register: показать локальный cache (если есть), затем скачать с backend.
     */
    private fun restoreProfileAvatarAfterLogin(phone: String, avatarUrl: String) {
        if (phone.isBlank()) return
        val local = profileAvatarFile(phone)
        if (local.exists()) {
            userProfilePrefs().edit()
                .putString("avatar_uri", local.absolutePath)
                .apply()
        }
        if (avatarUrl.isBlank()) return
        thread {
            try {
                val url = if (avatarUrl.startsWith("http", ignoreCase = true)) {
                    avatarUrl
                } else {
                    adminServerUrl(avatarUrl)
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 20000
                    setRequestProperty("Accept", "image/jpeg,image/*")
                    applyBmsApiAuth(this)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    return@thread
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.isEmpty() || bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
                    return@thread
                }
                local.outputStream().use { it.write(bytes) }
                userProfilePrefs().edit()
                    .putString("avatar_uri", local.absolutePath)
                    .putString("avatar_url", avatarUrl)
                    .apply()
                runOnUiThread {
                    if (screenState == "profile") showProfileScreen()
                }
            } catch (e: Exception) {
                Log.w(BLE_LOG_TAG, "PROFILE AVATAR restore failed: ${e.message}")
            }
        }
    }

    private fun updateProfileAvatarStatusUi() {
        val view = profileAvatarStatusText ?: return
        when (avatarUploadState) {
            "Uploading" -> {
                view.visibility = View.VISIBLE
                view.setTextColor(Color.rgb(90, 90, 90))
                view.text = "Загрузка фото…"
            }
            "Error" -> {
                view.visibility = View.VISIBLE
                view.setTextColor(Color.rgb(180, 35, 45))
                view.text = "Не удалось загрузить фото. Попробуйте еще раз."
            }
            "Success" -> {
                view.visibility = View.GONE
                view.text = ""
            }
            else -> {
                view.visibility = View.GONE
                view.text = ""
            }
        }
    }

    private fun loadProfileAvatarInto(imageView: ImageView) {
        val phone = normalizePhoneE164()
        val preferred = profileAvatarFile(phone)
        val path = when {
            preferred.exists() -> preferred.absolutePath
            else -> userProfilePrefs().getString("avatar_uri", "").orEmpty()
        }
        if (path.isBlank()) {
            imageView.setImageResource(android.R.drawable.ic_menu_myplaces)
            return
        }
        try {
            val file = File(path)
            if (file.exists()) {
                imageView.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        } catch (_: Exception) {
            imageView.setImageResource(android.R.drawable.ic_menu_myplaces)
        }
    }

    /**
     * Привязка текущей АКБ к пользователю на backend (owner_phone).
     * Не удаляет АКБ — только обновляет владельца.
     */
    private fun linkCurrentBatteryToUserAsync() {
        if (isServiceApp() || !isUserSessionActive()) return
        val address = selectedAddress ?: return
        val phone = normalizePhoneE164()
        if (phone.isBlank()) return
        val name = profileFullName()
        val bluetoothName = sanitizeBleText(
            scanNames[address] ?: selectedDeviceName
        ).ifBlank { address }
        val bmsUid = bmsUid().takeIf { it.isNotBlank() && it != "unknown_bms" }.orEmpty()
        thread {
            try {
                val body = JSONObject().apply {
                    putBmsApiKey(this)
                    put("phone", phone)
                    put("name", name)
                    put("owner_name", name)
                    put("email", userProfilePrefs().getString("email", "")?.trim().orEmpty())
                    put("bluetooth_address", address)
                    put("bluetooth_name", bluetoothName)
                    put("advertised_name", bluetoothName)
                    if (bmsUid.isNotBlank()) put("bms_uid", bmsUid)
                }
                val conn = (URL(adminServerUrl(USER_LINK_BATTERY_PATH)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
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
                Log.i(BLE_LOG_TAG, "USER LINK BATTERY http=$code body=${response.take(200)}")
            } catch (e: Exception) {
                Log.w(BLE_LOG_TAG, "USER LINK BATTERY failed: ${e.message}")
            }
        }
    }

    /**
     * Отвязка АКБ от профиля пользователя на backend.
     * Сбрасывает owner_phone у записи батареи; телеметрию и саму АКБ не удаляет.
     *
     * @param battery локальная сохранённая батарея (идентификатор — bluetooth address).
     */
    private fun unlinkBatteryFromUserAsync(battery: SavedBattery) {
        if (isServiceApp() || !isUserSessionActive()) return
        val phone = normalizePhoneE164()
        val address = battery.address.trim()
        if (phone.isBlank() || address.isBlank()) return
        thread {
            try {
                val body = JSONObject().apply {
                    putBmsApiKey(this)
                    put("phone", phone)
                    put("bluetooth_address", address)
                }
                val conn = (URL(adminServerUrl(USER_UNLINK_BATTERY_PATH)).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
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
                Log.i(BLE_LOG_TAG, "USER UNLINK BATTERY http=$code body=${response.take(200)}")
                if (code !in 200..299) {
                    runOnUiThread {
                        toast("АКБ удалена локально. Синхронизация с сервером не удалась.")
                    }
                }
            } catch (e: Exception) {
                Log.w(BLE_LOG_TAG, "USER UNLINK BATTERY failed: ${e.message}")
                runOnUiThread {
                    toast("АКБ удалена локально. Синхронизация с сервером не удалась.")
                }
            }
        }
    }

    /**
     * Гостевой профиль: Login/Register только по явному действию.
     */
    private fun showGuestProfileScreen() {
        enterScreen("profile")
        currentTab = "profile"
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
        }, marginLp(-1, -2, 0, 4, 0, 8))
        content.addView(TextView(this).apply {
            text = "Вы не авторизованы.\nВойдите, чтобы добавлять АКБ и синхронизировать список между устройствами."
            textSize = 14f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, 0, 0, dp(16))
        })
        content.addView(TextView(this).apply {
            text = "ВОЙТИ"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(red, dp(14), Color.TRANSPARENT, 0)
            setOnClickListener {
                pendingAuthAction = null
                showLoginScreen(allowBackToBatteries = true)
            }
        }, marginLp(-1, dp(54), 0, 10, 0, 0))
        content.addView(TextView(this).apply {
            text = "ЗАРЕГИСТРИРОВАТЬСЯ"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)
            setOnClickListener {
                pendingAuthAction = null
                showRegisterScreen()
            }
        }, marginLp(-1, dp(54), 0, 10, 0, 0))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("profile"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    private fun showProfileScreen() {
        if (isServiceApp()) {
            showServiceProfileScreen()
            return
        }
        if (!isUserSessionActive()) {
            showGuestProfileScreen()
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
            setOnClickListener { showProfileAvatarChooser() }
        }
        loadProfileAvatarInto(avatar)
        avatarWrap.addView(avatar, LinearLayout.LayoutParams(dp(104), dp(104)))
        avatarWrap.addView(TextView(this).apply {
            text = if (avatarUploadState == "Uploading") "Загрузка…" else "✎  Изменить фото"
            textSize = 12f
            typeface = interFont(760)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(16, 17, 20))
            background = round(Color.WHITE, dp(12), Color.rgb(223, 229, 235), 1)
            isEnabled = avatarUploadState != "Uploading"
            setOnClickListener {
                if (avatarUploadState != "Uploading") showProfileAvatarChooser()
            }
        }, marginLp(-2, dp(40), 0, 10, 0, 10))
        val avatarStatus = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        profileAvatarStatusText = avatarStatus
        avatarWrap.addView(avatarStatus, LinearLayout.LayoutParams(-1, -2))
        updateProfileAvatarStatusUi()
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
        profileCard.addView(TextView(this).apply {
            text = "Номер телефона *"
            textSize = 12f
            typeface = interFont(700)
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, dp(10), 0, dp(5))
        })
        val (phoneRow, phoneNational) = buildRuPhoneInputRow(savedPhone)
        profileCard.addView(phoneRow, LinearLayout.LayoutParams(-1, -2))
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
                val national = extractRuNationalDigits(phoneNational.text.toString())
                if (nameText.isBlank()) {
                    toast("Укажите ФИО")
                    return@setOnClickListener
                }
                if (national.length != 10) {
                    toast("Укажите 10 цифр номера")
                    return@setOnClickListener
                }
                val nextPhone = normalizePhoneE164(national)
                if (nextPhone.isBlank()) {
                    toast("Укажите полный номер телефона")
                    return@setOnClickListener
                }
                val previousPhone = normalizePhoneE164()
                val emailText = email.text.toString().trim()
                val birthText = birth.text.toString().trim()
                prefs.edit()
                    .putBoolean("logged_in", true)
                    .putString("name", nameText)
                    .putString("phone", nextPhone)
                    .putString("email", emailText)
                    .putString("birth", birthText)
                    .apply()
                phoneNational.setText(formatRuNationalMask(national))
                // Синхронизация профиля (не login/register).
                thread {
                    val result = requestUserAuth(
                        USER_PROFILE_PATH,
                        phone = nextPhone,
                        name = nameText,
                        email = emailText,
                        birth = birthText,
                    )
                    runOnUiThread {
                        val body = result?.second
                        if (body == null || !body.optBoolean("ok")) {
                            toast("Профиль сохранён локально, сервер недоступен")
                            return@runOnUiThread
                        }
                        if (previousPhone.isNotBlank() && previousPhone != nextPhone) {
                            // Смена номера: войти заново, чтобы подтянуть АКБ нового профиля.
                            performUserLogin(nextPhone)
                            return@runOnUiThread
                        }
                        toast("Профиль сохранён")
                    }
                }
            }
        }, marginLp(-1, dp(54), 0, 14, 0, 0))

        content.addView(TextView(this).apply {
            text = "Выйти из профиля"
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = interFont(700)
            setTextColor(Color.WHITE)
            background = round(Color.rgb(180, 35, 45), dp(14), Color.TRANSPARENT, 0)
            setOnClickListener { confirmLogout() }
        }, marginLp(-1, dp(54), 0, 18, 0, 8))
        content.addView(TextView(this).apply {
            text = "При выходе локальные АКБ очищаются. На сервере ваши АКБ сохраняются и вернутся при повторном входе."
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            setPadding(0, 0, 0, dp(12))
        })

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
                    // Повторный тап по уже выбранному режиму возвращает в базовое состояние.
                    val next = if (supportMode == mode) "home" else mode
                    supportMode = next
                    if (supportMode == "new") {
                        editingWarrantyLocalId = null
                    }
                    if (supportMode == "list") {
                        warrantyListPage = 1
                    }
                    if (supportMode == "home") {
                        editingWarrantyLocalId = null
                        clearWarrantyFormState()
                    }
                    showSupportScreen()
                }
            }
        }
        // Порядок: Новое обращение → Ваши обращения. В режиме home ни один не выбран.
        row.addView(seg("Новое обращение", "new"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(seg("Ваши обращения", "list"), marginLp(0, -2, 8, 0, 0, 0).apply { weight = 1f })
        return row
    }

    private fun renderWarrantyList(content: LinearLayout) {
        val c = card()
        c.addView(sectionTitle("Ваши обращения", ""))
        warrantyListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(warrantyListLayout)
        warrantyPaginationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        c.addView(warrantyPaginationRow, marginLp(-1, -2, 0, 8, 0, 0))
        content.addView(c, marginLp(-1, -2, 0, 0, 0, 10))
        renderWarrantyListItems()
    }

    private fun renderWarrantyListItems() {
        val layout = warrantyListLayout ?: return
        layout.removeAllViews()
        val uid = bmsUid()
        val arr = warrantyRequestsArray()

        if (warrantyListLoading) {
            layout.addView(TextView(this).apply {
                text = "Загрузка обращений…"
                textSize = 14f
                setTextColor(Color.rgb(100, 100, 100))
                setPadding(0, dp(8), 0, dp(8))
            })
            updateWarrantyPaginationControls()
            return
        }

        // Текущая страница с сервера (уже смержена в локальный кэш с пометкой page_item).
        val pageItems = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val itemUid = item.optString("bms_uid")
            if (itemUid.isNotBlank() && itemUid != uid) continue
            if (item.optBoolean("list_page_item", false)) {
                pageItems.add(item)
            }
        }
        pageItems.sortWith(compareByDescending<JSONObject> {
            it.optString("server_id").toIntOrNull() ?: 0
        }.thenByDescending {
            it.optString("created_at")
        })

        // Локальные неотправленные черновики — только на первой странице.
        val drafts = mutableListOf<JSONObject>()
        if (warrantyListPage <= 1) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val itemUid = item.optString("bms_uid")
                if (itemUid.isNotBlank() && itemUid != uid) continue
                if (item.optString("server_id").isBlank()) {
                    drafts.add(item)
                }
            }
            drafts.sortByDescending { it.optString("created_at") }
        }

        val items = drafts + pageItems

        if (items.isEmpty() && warrantyListTotal <= 0) {
            layout.addView(TextView(this).apply {
                text = "Обращений пока нет. Выберите «Новое обращение», чтобы создать заявку."
                textSize = 14f
                setTextColor(Color.rgb(100, 100, 100))
                setPadding(0, dp(8), 0, dp(8))
            })
            updateWarrantyPaginationControls()
            return
        }

        layout.addView(TextView(this).apply {
            text = "Всего обращений по этой BMS: ${max(warrantyListTotal, items.size)}"
            textSize = 13f
            typeface = interFont(700)
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, 0, 0, dp(8))
        })

        for (item in items) {
            layout.addView(warrantyRequestCard(item), marginLp(-1, -2, 0, 0, 0, 10))
        }
        updateWarrantyPaginationControls()
    }

    /**
     * Пагинация списка обращений: ← Назад / N / M / Далее →.
     * Side effects: меняет warrantyListPage и запускает refreshWarrantyStatuses.
     */
    private fun updateWarrantyPaginationControls() {
        val row = warrantyPaginationRow ?: return
        row.removeAllViews()
        if (warrantyListTotalPages <= 1 && warrantyListTotal <= WARRANTY_PAGE_SIZE) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        val page = warrantyListPage.coerceIn(1, max(1, warrantyListTotalPages))
        warrantyListPage = page

        fun pageBtn(label: String, enabled: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 14f
                typeface = interFont(700)
                gravity = Gravity.CENTER
                setTextColor(if (enabled) redDark else Color.rgb(160, 160, 160))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = round(Color.WHITE, dp(10), if (enabled) red else Color.rgb(220, 220, 220), 1)
                isEnabled = enabled
                isClickable = enabled
                isFocusable = enabled
                if (enabled) setOnClickListener { onClick() }
            }
        }

        row.addView(
            pageBtn("← Назад", page > 1 && !warrantyListLoading) {
                warrantyListPage = page - 1
                refreshWarrantyStatuses(false)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        row.addView(TextView(this).apply {
            text = "$page / ${max(1, warrantyListTotalPages)}"
            textSize = 14f
            typeface = interFont(700)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(60, 60, 60))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            pageBtn("Далее →", page < warrantyListTotalPages && !warrantyListLoading) {
                warrantyListPage = page + 1
                refreshWarrantyStatuses(false)
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
    }

    private fun warrantyRequestCard(item: JSONObject): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(Color.rgb(247, 248, 250), dp(12), Color.rgb(225, 228, 235), 1)
        }

        val serverId = item.optString("server_id").ifBlank { "не отправлено" }
        val status = item.optString("status").ifBlank { "new" }
        val title = if (serverId == "не отправлено") "Локальный черновик" else "№$serverId"
        val statusLabel = warrantyStatusRu(status)
        val statusColor = warrantyStatusColor(status)

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = interFont(700)
            setTextColor(Color.rgb(35, 35, 35))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(TextView(this).apply {
            text = statusLabel
            textSize = 14f
            typeface = interFont(750)
            setTextColor(statusColor)
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(-2, -2))
        c.addView(headerRow)

        c.addView(TextView(this).apply {
            val comment = item.optString("admin_comment").trim()
            text = buildString {
                append("Дата: ${item.optString("created_at")}\n")
                append("BMS: ${item.optString("bms_uid")}\n")
                append("Модель: ${item.optString("model")}\n")
                append("Проблема: ${item.optString("problem").take(120)}")
                if (comment.isNotBlank()) append("\nОтвет: ${comment.take(160)}")
            }
            textSize = 13f
            setTextColor(Color.rgb(80, 80, 80))
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
            val status = existing.optString("status")
            formCard.addView(TextView(this).apply {
                text = warrantyStatusRu(status)
                textSize = 15f
                typeface = interFont(700)
                setTextColor(warrantyStatusColor(status))
                setPadding(0, 0, 0, dp(8))
            })
        }

        formCard.addView(TextView(this).apply {
            text = "При отправке приложение приложит текущие логи, ошибки и параметры АКБ, если BMS подключена."
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
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

        val mediaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(TextView(this).apply {
            text = "Сделать фото"
            textSize = 14f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = round(Color.WHITE, dp(12), Color.rgb(202, 211, 220), 1)
            setOnClickListener { takeWarrantyPhoto() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        mediaRow.addView(TextView(this).apply {
            text = "Из галереи"
            textSize = 14f
            typeface = interFont(700)
            setTextColor(Color.rgb(16, 17, 20))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = round(Color.WHITE, dp(12), Color.rgb(202, 211, 220), 1)
            setOnClickListener { pickWarrantyMedia() }
        }, marginLp(0, -2, 8, 0, 0, 0).apply { weight = 1f })
        formCard.addView(mediaRow, marginLp(-1, -2, 0, 10, 0, 0))

        formCard.addView(TextView(this).apply {
            text = "Прикреплённые файлы"
            textSize = 13f
            typeface = interFont(700)
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, dp(4), 0, dp(4))
        })
        warrantyMediaListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        formCard.addView(warrantyMediaListLayout)

        val consent = CheckBox(this).apply {
            text = "Согласен на обработку персональных данных"
            textSize = 12f
            setTextColor(Color.rgb(111, 119, 129))
            buttonTintList = ColorStateList.valueOf(redDark)
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

        warrantyStatusText = TextView(this).apply {
            text = existing?.let { "Текущий статус: ${warrantyStatusRu(it.optString("status"))}" } ?: ""
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 90))
        }
        formCard.addView(warrantyStatusText)

        content.addView(formCard, marginLp(-1, -2, 0, 0, 0, 10))
    }

    private fun showSupportDiagnostics() {
        enterScreen("support_diagnostics")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Диагностика",
                selectedDeviceName.ifBlank { selectedAddress ?: "BMS" },
                showBack = true
            )
        )
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }

        // Клиентская Диагностика: только результат проверки конфигурации (группированный UI).
        // Механизм ConfigCheck / TemplateCheckResult не меняется.
        appendDiagnosticsConfigPresentation(content)
        appendDiagnosticsActionButtons(content)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("support"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)
    }

    /**
     * Общий presentation-слой диагностики конфигурации:
     * Главная (тап «Батарея в норме») и Поддержка → Диагностика.
     * Использует TemplateCheckResult; без версии шаблона, expected values и глобального banner.
     */
    private fun appendDiagnosticsConfigPresentation(content: LinearLayout) {
        val result = currentTemplateCheck()
        val checkedText = result?.checkedAt?.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        }
        if (checkedText != null) {
            content.addView(TextView(this).apply {
                text = "Проверено: $checkedText"
                textSize = 13f
                typeface = interFont(600)
                setTextColor(Color.rgb(111, 119, 129))
                setPadding(dp(2), 0, dp(2), dp(10))
            })
        }

        when {
            result?.status == "unavailable" ||
                (serverTemplateFetchStatus == "error" && activeServerTemplate == null) -> {
                content.addView(
                    diagnosticsStatusCard(
                        "⚠",
                        "Проверка конфигурации недоступна",
                        Color.rgb(224, 150, 0)
                    ),
                    marginLp(-1, -2, 0, 0, 0, 8)
                )
            }
            result == null || result.status == "checking" || serverTemplateFetchStatus == "fetching" -> {
                content.addView(
                    diagnosticsStatusCard(
                        "…",
                        "Идёт инициализация BMS",
                        Color.rgb(111, 119, 129)
                    ),
                    marginLp(-1, -2, 0, 0, 0, 8)
                )
            }
            else -> {
                val items = (if (result.items.isNotEmpty()) {
                    result.items
                } else {
                    result.mismatches.map { it.copy(status = "mismatch") } +
                        result.missing.map { it.copy(status = "missing") }
                }).filter { it.status != "disabled" && !isHiddenDiagnosticsParamKey(it.key) }

                if (items.isEmpty()) {
                    content.addView(
                        diagnosticsStatusCard(
                            "•",
                            "Нет параметров для отображения",
                            Color.rgb(111, 119, 129)
                        ),
                        marginLp(-1, -2, 0, 0, 0, 8)
                    )
                    return
                }

                val byKey = items.associateBy { it.key }
                val used = mutableSetOf<String>()
                val groups = diagnosticsParamGroups()

                for (item in items) {
                    if (item.key in used) continue
                    val group = groups.firstOrNull { item.key in it.keys }
                    if (group != null) {
                        val children = group.keys.mapNotNull { byKey[it] }
                        children.forEach { used.add(it.key) }
                        val view = diagnosticsGroupedCard(group.title, children) ?: continue
                        content.addView(view, marginLp(-1, -2, 0, 0, 0, 7))
                    } else {
                        used.add(item.key)
                        content.addView(
                            diagnosticsStandaloneCard(item),
                            marginLp(-1, -2, 0, 0, 0, 7)
                        )
                    }
                }
            }
        }
    }

    private data class DiagnosticsParamGroup(
        val id: String,
        val title: String,
        val keys: Set<String>
    )

    private enum class DiagnosticsGroupStatus {
        MATCH,
        MISMATCH,
        PARTIAL,
        SKIPPED
    }

    /** UI-группы поверх отдельных TemplateCheckItem (backend keys не меняются). */
    private fun diagnosticsParamGroups(): List<DiagnosticsParamGroup> {
        return listOf(
            DiagnosticsParamGroup(
                id = "capacity_cal",
                title = "Калибровка ёмкости",
                keys = setOf("soc_calibration_0", "soc_calibration_100")
            ),
            DiagnosticsParamGroup(
                id = "cells",
                title = "Параметры ячеек",
                keys = setOf(
                    "cell_over_voltage",
                    "cell_under_voltage",
                    "pack_over_voltage",
                    "pack_under_voltage"
                )
            ),
            DiagnosticsParamGroup(
                id = "temps",
                title = "Настройки температуры",
                keys = setOf(
                    "charge_high_temp",
                    "charge_low_temp",
                    "discharge_high_temp",
                    "discharge_low_temp"
                )
            ),
            DiagnosticsParamGroup(
                id = "balance",
                title = "Настройки балансировки",
                keys = setOf("balance_start_voltage", "balance_stop_voltage")
            ),
            DiagnosticsParamGroup(
                id = "sleep",
                title = "Настройки спящего режима",
                keys = setOf("sleep_timeout")
            )
        )
    }

    /** Не показывать в Diagnostics (чтение/Главная не затрагиваются). */
    private fun isHiddenDiagnosticsParamKey(key: String): Boolean {
        return key == "series_cell_count"
    }

    /** Display label для standalone-строк Diagnostics. */
    private fun diagnosticsDisplayLabel(item: TemplateCheckItem): String {
        return when (item.key) {
            "sleep_timeout" -> "Настройки спящего режима"
            else -> item.label
        }
    }

    private fun diagnosticsGroupStatus(children: List<TemplateCheckItem>): DiagnosticsGroupStatus {
        val active = children.filter { it.status != "skipped" && it.status != "disabled" }
        if (active.isEmpty()) return DiagnosticsGroupStatus.SKIPPED
        if (active.any { it.status == "mismatch" }) return DiagnosticsGroupStatus.MISMATCH
        if (active.any { it.status == "missing" }) return DiagnosticsGroupStatus.PARTIAL
        if (active.all { it.status == "ok" }) return DiagnosticsGroupStatus.MATCH
        return DiagnosticsGroupStatus.PARTIAL
    }

    private fun diagnosticsProblemItems(children: List<TemplateCheckItem>): List<TemplateCheckItem> {
        return children.filter { it.status == "mismatch" || it.status == "missing" }
    }

    private fun diagnosticsFormatActual(item: TemplateCheckItem): String {
        val value = item.actual ?: return "—"
        val num = formatTemplateNumber(value)
        val unit = item.unit.trim()
        return if (unit.isBlank()) num else "$num $unit"
    }

    private fun diagnosticsStatusCard(mark: String, title: String, color: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = interFont(700)
                setTextColor(Color.rgb(16, 17, 20))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = mark
                textSize = 18f
                typeface = interFont(780)
                setTextColor(color)
            })
        }
    }

    private fun diagnosticsGroupedCard(
        title: String,
        children: List<TemplateCheckItem>
    ): LinearLayout? {
        val status = diagnosticsGroupStatus(children)
        if (status == DiagnosticsGroupStatus.SKIPPED) return null

        val (mark, markColor, subtitle) = when (status) {
            DiagnosticsGroupStatus.MATCH ->
                Triple("✓", Color.rgb(31, 179, 90), null as String?)
            DiagnosticsGroupStatus.MISMATCH ->
                Triple("✕", Color.rgb(211, 47, 47), "Не соответствует")
            DiagnosticsGroupStatus.PARTIAL ->
                Triple("⚠", Color.rgb(224, 150, 0), "Проверено не полностью")
            DiagnosticsGroupStatus.SKIPPED ->
                Triple("—", Color.rgb(111, 119, 129), null)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                typeface = interFont(720)
                setTextColor(Color.rgb(16, 17, 20))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            headerRow.addView(TextView(this@MainActivity).apply {
                text = mark
                textSize = 18f
                typeface = interFont(780)
                setTextColor(markColor)
            })
            addView(headerRow)

            if (subtitle != null) {
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    typeface = interFont(600)
                    setTextColor(markColor)
                    setPadding(0, dp(4), 0, 0)
                })
            }

            // Успешные child-параметры не разворачиваем — только проблемные.
            if (status != DiagnosticsGroupStatus.MATCH) {
                for (problem in diagnosticsProblemItems(children)) {
                    addView(diagnosticsProblemChildBlock(problem), marginLp(-1, -2, 0, dp(8), 0, 0))
                }
            }
        }
    }

    private fun diagnosticsStandaloneCard(item: TemplateCheckItem): LinearLayout {
        val status = when (item.status) {
            "ok" -> DiagnosticsGroupStatus.MATCH
            "mismatch" -> DiagnosticsGroupStatus.MISMATCH
            "missing" -> DiagnosticsGroupStatus.PARTIAL
            "skipped" -> DiagnosticsGroupStatus.SKIPPED
            else -> DiagnosticsGroupStatus.PARTIAL
        }
        val (mark, markColor, subtitle) = when (status) {
            DiagnosticsGroupStatus.MATCH ->
                Triple("✓", Color.rgb(31, 179, 90), null as String?)
            DiagnosticsGroupStatus.MISMATCH ->
                Triple("✕", Color.rgb(211, 47, 47), "Не соответствует")
            DiagnosticsGroupStatus.PARTIAL ->
                Triple("⚠", Color.rgb(224, 150, 0), "Проверено не полностью")
            DiagnosticsGroupStatus.SKIPPED ->
                Triple("—", Color.rgb(111, 119, 129), "Не поддерживается")
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(this@MainActivity).apply {
                text = diagnosticsDisplayLabel(item)
                textSize = 15f
                typeface = interFont(720)
                setTextColor(Color.rgb(16, 17, 20))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            headerRow.addView(TextView(this@MainActivity).apply {
                text = mark
                textSize = 18f
                typeface = interFont(780)
                setTextColor(markColor)
            })
            addView(headerRow)

            if (status == DiagnosticsGroupStatus.MISMATCH) {
                addView(TextView(this@MainActivity).apply {
                    text = diagnosticsFormatActual(item)
                    textSize = 15f
                    typeface = interFont(700)
                    setTextColor(Color.rgb(16, 17, 20))
                    setPadding(0, dp(4), 0, 0)
                })
            }
            if (subtitle != null) {
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    typeface = interFont(600)
                    setTextColor(markColor)
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }
    }

    private fun diagnosticsProblemChildBlock(item: TemplateCheckItem): LinearLayout {
        val isMissing = item.status == "missing"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = item.label
                textSize = 14f
                typeface = interFont(650)
                setTextColor(Color.rgb(90, 96, 104))
            })
            if (!isMissing) {
                addView(TextView(this@MainActivity).apply {
                    text = diagnosticsFormatActual(item)
                    textSize = 15f
                    typeface = interFont(700)
                    setTextColor(Color.rgb(16, 17, 20))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            addView(TextView(this@MainActivity).apply {
                text = if (isMissing) "Проверено не полностью" else "Не соответствует"
                textSize = 13f
                typeface = interFont(600)
                setTextColor(
                    if (isMissing) Color.rgb(224, 150, 0) else Color.rgb(211, 47, 47)
                )
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun clearWarrantyFormState() {
        warrantyMediaUris.clear()
        warrantyPendingCameraUri = null
        warrantyNameEdit = null
        warrantyPhoneEdit = null
        warrantyModelEdit = null
        warrantyProblemEdit = null
        warrantyMediaListLayout = null
        warrantyStatusText = null
    }

    /**
     * Пользовательский статус обращения: только «Открыто» / «Закрыто».
     * Backend может отдавать new/sent/in_work/done/draft/failed — маппим в два значения.
     */
    private fun warrantyStatusRu(status: String): String {
        return if (isWarrantyClosedStatus(status)) "Закрыто" else "Открыто"
    }

    private fun isWarrantyClosedStatus(status: String): Boolean {
        return status.trim().equals("done", ignoreCase = true) ||
            status.trim().equals("closed", ignoreCase = true)
    }

    private fun warrantyStatusColor(status: String): Int {
        return if (isWarrantyClosedStatus(status)) Color.rgb(120, 120, 120) else green
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

        warrantyListLoading = true
        renderWarrantyListItems()

        thread {
            try {
                val body = JSONObject().apply {
                    putBmsApiKey(this)
                    put("bms_uid", uid)
                    put("all_by_bms", true)
                    put("page", warrantyListPage)
                    put("limit", WARRANTY_PAGE_SIZE)
                }
                Log.i(
                    BLE_LOG_TAG,
                    "SUPPORT LIST REQUEST url=${adminServerUrl(WARRANTY_LIST_PATH)} " +
                        "auth=${!BmsApiConfig.API_KEY.isNullOrBlank()} bms_uid=$uid " +
                        "page=$warrantyListPage limit=$WARRANTY_PAGE_SIZE"
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
                    warrantyListTotal = obj.optInt("total", remoteList.length())
                    warrantyListTotalPages = max(1, obj.optInt("total_pages", 1))
                    warrantyListPage = obj.optInt("page", warrantyListPage).coerceIn(1, warrantyListTotalPages)

                    val local = warrantyRequestsArray()
                    // Сбрасываем пометки текущей страницы, сохраняя остальные локальные записи.
                    for (j in 0 until local.length()) {
                        local.optJSONObject(j)?.put("list_page_item", false)
                    }

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
                        item.put("list_page_item", true)

                        if (foundIndex >= 0) local.put(foundIndex, item) else local.put(item)
                    }

                    saveWarrantyRequestsArray(local)
                    runOnUiThread {
                        warrantyListLoading = false
                        renderWarrantyListItems()
                        if (showToast) toast("Список обращений обновлён")
                    }
                } else {
                    val err = obj?.optString("error").orEmpty()
                    Log.w(BLE_LOG_TAG, "SUPPORT LIST FAILED http=$code error=$err")
                    runOnUiThread {
                        warrantyListLoading = false
                        renderWarrantyListItems()
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
                    warrantyListLoading = false
                    renderWarrantyListItems()
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
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, WARRANTY_MEDIA_REQUEST_CODE)
    }

    /**
     * Съёмка фото через системную камеру (FileProvider + ACTION_IMAGE_CAPTURE).
     * Side effects: запрос CAMERA permission; запись во временный файл cache/warranty_photos.
     */
    private fun takeWarrantyPhoto() {
        if (warrantyMediaUris.size >= 5) {
            toast("Можно прикрепить не более 5 файлов")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                WARRANTY_CAMERA_PERMISSION_REQUEST_CODE
            )
            return
        }
        launchWarrantyCamera()
    }

    private fun launchWarrantyCamera() {
        try {
            val dir = File(cacheDir, "warranty_photos").apply { mkdirs() }
            val fileName = "Фото_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.jpg"
            val photoFile = File(dir, fileName)
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            warrantyPendingCameraUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (intent.resolveActivity(packageManager) == null && resInfoList.isEmpty()) {
                warrantyPendingCameraUri = null
                toast("Камера недоступна на этом устройстве")
                return
            }
            startActivityForResult(intent, WARRANTY_CAMERA_REQUEST_CODE)
        } catch (e: Exception) {
            warrantyPendingCameraUri = null
            Log.w(BLE_LOG_TAG, "WARRANTY CAMERA launch failed: ${e.message}")
            toast("Не удалось открыть камеру")
        }
    }

    /** @deprecated Используйте showProfileAvatarChooser / Photo Picker. */
    private fun pickProfileAvatar() {
        showProfileAvatarChooser()
    }

    /**
     * Обновляет список прикреплённых файлов: display name + кнопка удаления.
     * Side effects: перерисовка warrantyMediaListLayout.
     */
    private fun updateWarrantyMediaText() {
        val layout = warrantyMediaListLayout ?: return
        layout.removeAllViews()
        if (warrantyMediaUris.isEmpty()) {
            layout.addView(TextView(this).apply {
                text = "Файлы не выбраны"
                textSize = 13f
                setTextColor(Color.rgb(100, 100, 100))
                setPadding(0, dp(4), 0, dp(4))
            })
            return
        }
        warrantyMediaUris.forEachIndexed { idx, uri ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = "📎 ${warrantyDisplayName(uri, idx)}"
                textSize = 13f
                setTextColor(Color.rgb(35, 35, 35))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 16f
                typeface = interFont(700)
                setTextColor(Color.rgb(140, 140, 140))
                setPadding(dp(10), dp(4), dp(4), dp(4))
                contentDescription = "Удалить файл"
                setOnClickListener {
                    if (idx in warrantyMediaUris.indices) {
                        warrantyMediaUris.removeAt(idx)
                        updateWarrantyMediaText()
                    }
                }
            })
            layout.addView(row)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK) {
            if (requestCode == WARRANTY_CAMERA_REQUEST_CODE) {
                warrantyPendingCameraUri = null
            }
            return
        }
        // PROFILE_AVATAR_REQUEST_CODE: заменён на Activity Result / Photo Picker.
        if (requestCode == WARRANTY_CAMERA_REQUEST_CODE) {
            val uri = warrantyPendingCameraUri
            warrantyPendingCameraUri = null
            if (uri != null && warrantyMediaUris.size < 5) {
                warrantyMediaUris.add(uri)
                updateWarrantyMediaText()
                toast("Фото добавлено")
            }
            return
        }
        if (requestCode != WARRANTY_MEDIA_REQUEST_CODE) return
        if (resultData == null) return

        val clip = resultData.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                if (warrantyMediaUris.size < 5) warrantyMediaUris.add(uri)
            }
        } else {
            resultData.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
                if (warrantyMediaUris.size < 5) warrantyMediaUris.add(uri)
            }
        }
        updateWarrantyMediaText()
        toast("Файлы добавлены")
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
        val mediaUrisSnapshot = warrantyMediaUris.toList()

        warrantyStatusText?.text = "Отправка обращения..."
        thread {
            val mediaArray = JSONArray()
            var mediaEncodeFailed = false
            mediaUrisSnapshot.take(5).forEachIndexed { idx, uri ->
                val fileName = warrantyDisplayName(uri, idx)
                val encoded = encodeWarrantyMediaForUpload(uri)
                if (encoded == null) {
                    mediaEncodeFailed = true
                    return@forEachIndexed
                }
                mediaArray.put(
                    JSONObject().apply {
                        put("filename", fileName)
                        put("mime", encoded.second)
                        put("data_base64", encoded.first)
                    }
                )
            }
            if (mediaEncodeFailed && mediaUrisSnapshot.isNotEmpty()) {
                runOnUiThread {
                    warrantyStatusText?.text = "Не удалось прочитать вложение. Проверьте выбранные файлы."
                    toast("Не удалось прочитать вложение")
                }
                return@thread
            }

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
            payload.put("status", "new")
            payload.put("battery_snapshot", buildUploadJson())
            if (configRegisters.isNotEmpty()) payload.put("config_snapshot", buildConfigUploadJson())
            if (mediaArray.length() > 0) payload.put("media", mediaArray)

            existing?.optString("server_id")?.takeIf { it.isNotBlank() }?.let {
                payload.put("request_id", it)
            }

            val localItem = JSONObject().apply {
                put("local_id", localId)
                put("server_id", existing?.optString("server_id") ?: "")
                put("status", "new")
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

            val result = sendWarrantyMultipart(payload, mediaUrisSnapshot)
            runOnUiThread {
                val id = Regex("№(\\d+)").find(result)?.groupValues?.getOrNull(1)
                val updated = warrantyRequestByLocalId(localId) ?: localItem
                if (result.startsWith("Обращение отправлено")) {
                    if (!id.isNullOrBlank()) updated.put("server_id", id)
                    updated.put("status", "new")
                    updated.put("updated_at", nowText())
                    upsertWarrantyLocal(updated)
                    warrantyMediaUris.clear()
                    warrantyStatusText?.text =
                        "Обращение отправлено. Статус: ${warrantyStatusRu(updated.optString("status"))}"
                    supportMode = "list"
                    editingWarrantyLocalId = null
                    warrantyListPage = 1
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
        // JSON + media[].data_base64 — backend принимает application/json (лимит 12mb).
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

    /**
     * Display name для UI: OpenableColumns.DISPLAY_NAME или понятный fallback.
     * Не показывает content:// URI пользователю.
     */
    private fun warrantyDisplayName(uri: Uri, idx: Int): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {
        }
        val last = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() && !it.contains(':') }
        if (last != null) return last
        return warrantyFileName(uri, idx)
    }

    private fun warrantyFileName(uri: Uri, idx: Int): String {
        val mime = contentResolver.getType(uri) ?: ""
        val ext = when {
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("mp4") -> "mp4"
            mime.contains("quicktime") -> "mov"
            else -> "jpg"
        }
        return "Фото_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}_${idx + 1}.$ext"
    }

    /**
     * Читает вложение, сжимает изображения до разумного JPEG (с EXIF orientation)
     * и возвращает base64 + mime.
     * @return Pair(base64, mime) или null при ошибке чтения.
     */
    private fun encodeWarrantyMediaForUpload(uri: Uri): Pair<String, String>? {
        return try {
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"
            if (mime.startsWith("image/")) {
                // Предпочтительно: URI → EXIF → upright JPEG (без повторного EXIF rotate).
                val oriented = OrientedBitmapLoader.loadOrientedBitmap(
                    context = this,
                    uri = uri,
                    maxSide = 1600,
                )
                if (oriented != null) {
                    val out = ByteArrayOutputStream()
                    oriented.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    oriented.recycle()
                    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) to "image/jpeg"
                }
                val original = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
                val compressed = compressWarrantyImage(original)
                val bytes = compressed ?: original.take(4 * 1024 * 1024).toByteArray()
                val outMime = if (compressed != null) "image/jpeg" else mime
                Base64.encodeToString(bytes, Base64.NO_WRAP) to outMime
            } else {
                val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                    val buf = ByteArrayOutputStream()
                    val tmp = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = stream.read(tmp)
                        if (n <= 0) break
                        total += n
                        if (total > 6 * 1024 * 1024) return null
                        buf.write(tmp, 0, n)
                    }
                    buf.toByteArray()
                } ?: return null
                Base64.encodeToString(bytes, Base64.NO_WRAP) to mime
            }
        } catch (e: Exception) {
            Log.w(BLE_LOG_TAG, "WARRANTY MEDIA encode failed: ${e.message}")
            null
        }
    }

    /** Сжимает изображение с учётом EXIF до max 1600px, JPEG quality 82. */
    private fun compressWarrantyImage(bytes: ByteArray): ByteArray? {
        return OrientedBitmapLoader.compressOrientedJpeg(
            bytes = bytes,
            maxSide = 1600,
            quality = 82,
        )
    }

    private fun openChartsScreen() {
        ensureChartsAnchorsInitialized()
        chartsPeriodMode = "day"
        chartsStatus = "loading"
        chartsErrorText = ""
        chartsPoints = emptyList()
        chartsMinVoltage = null
        chartsMaxVoltage = null
        chartsMinCurrent = null
        chartsMaxCurrent = null
        chartsLoadedForUid = ""
        showChartsScreen(reload = true)
    }

    private fun ensureChartsAnchorsInitialized() {
        if (chartsAnchorDayStartMs > 0L) return
        val today = startOfLocalDay(System.currentTimeMillis())
        chartsAnchorDayStartMs = today
        chartsCustomToDayStartMs = today
        chartsCustomFromDayStartMs = today - 6L * 24L * 60L * 60L * 1000L
    }

    private fun startOfLocalDay(epochMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun endOfLocalDay(dayStartMs: Long): Long {
        return dayStartMs + 24L * 60L * 60L * 1000L - 1L
    }

    private fun maxChartsSelectableDayStart(): Long {
        return startOfLocalDay(System.currentTimeMillis())
    }

    /**
     * Назначение: границы выбранного периода графиков в локальной timezone.
     * @return Pair(fromMs inclusive, toMs inclusive)
     */
    private fun chartsSelectedRange(): Pair<Long, Long> {
        ensureChartsAnchorsInitialized()
        return when (chartsPeriodMode) {
            "week" -> {
                val cal = Calendar.getInstance().apply { timeInMillis = chartsAnchorDayStartMs }
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val offset = when (dayOfWeek) {
                    Calendar.SUNDAY -> -6
                    else -> Calendar.MONDAY - dayOfWeek
                }
                cal.add(Calendar.DAY_OF_MONTH, offset)
                val from = startOfLocalDay(cal.timeInMillis)
                from to endOfLocalDay(from + 6L * 24L * 60L * 60L * 1000L)
            }
            "month" -> {
                val cal = Calendar.getInstance().apply { timeInMillis = chartsAnchorDayStartMs }
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val from = startOfLocalDay(cal.timeInMillis)
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.DAY_OF_MONTH, -1)
                from to endOfLocalDay(startOfLocalDay(cal.timeInMillis))
            }
            "custom" -> {
                val from = minOf(chartsCustomFromDayStartMs, chartsCustomToDayStartMs)
                val toDay = maxOf(chartsCustomFromDayStartMs, chartsCustomToDayStartMs)
                from to endOfLocalDay(toDay)
            }
            else -> chartsAnchorDayStartMs to endOfLocalDay(chartsAnchorDayStartMs)
        }
    }

    private fun chartsPeriodLabel(): String {
        val (from, to) = chartsSelectedRange()
        val ru = Locale("ru")
        return when (chartsPeriodMode) {
            "day" -> SimpleDateFormat("d MMMM yyyy 'г.'", ru).format(Date(from))
            "week" -> {
                val a = SimpleDateFormat("dd.MM", ru).format(Date(from))
                val b = SimpleDateFormat("dd.MM", ru).format(Date(to))
                "$a — $b"
            }
            "month" -> SimpleDateFormat("LLLL yyyy", ru).format(Date(from))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(ru) else it.toString() }
            else -> {
                val a = SimpleDateFormat("dd.MM.yyyy", ru).format(Date(from))
                val b = SimpleDateFormat("dd.MM.yyyy", ru).format(Date(to))
                "$a — $b"
            }
        }
    }

    private fun shiftChartsPeriod(direction: Int) {
        ensureChartsAnchorsInitialized()
        val cal = Calendar.getInstance().apply { timeInMillis = chartsAnchorDayStartMs }
        when (chartsPeriodMode) {
            "week" -> cal.add(Calendar.WEEK_OF_YEAR, direction)
            "month" -> cal.add(Calendar.MONTH, direction)
            "custom" -> return
            else -> cal.add(Calendar.DAY_OF_MONTH, direction)
        }
        var next = startOfLocalDay(cal.timeInMillis)
        val maxDay = maxChartsSelectableDayStart()
        if (next > maxDay) next = maxDay
        // Не уводим якорь слишком далеко в прошлое без нужды — оставляем разумный горизонт ~2 года.
        val minDay = maxDay - 800L * 24L * 60L * 60L * 1000L
        if (next < minDay) next = minDay
        chartsAnchorDayStartMs = next
        showChartsScreen(reload = true)
    }

    private fun showChartsScreen(reload: Boolean) {
        enterScreen("charts")
        currentTab = "main"
        ensureChartsAnchorsInitialized()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.addView(
            header(
                "Графики",
                selectedDeviceName.ifBlank { bmsUid() },
                showBack = true
            )
        )

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(18))
        }

        content.addView(chartsPeriodSelector(), marginLp(-1, -2, 0, 0, 0, 10))
        content.addView(chartsDateNavigator(), marginLp(-1, -2, 0, 0, 0, 12))

        when (chartsStatus) {
            "loading" -> {
                content.addView(TextView(this).apply {
                    text = "Загрузка данных…"
                    textSize = 15f
                    typeface = interFont(650)
                    setTextColor(Color.rgb(111, 119, 129))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                })
            }
            "error" -> {
                content.addView(TextView(this).apply {
                    text = chartsErrorText.ifBlank { "Не удалось загрузить данные графика" }
                    textSize = 15f
                    typeface = interFont(650)
                    setTextColor(Color.rgb(180, 60, 60))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, dp(12))
                })
                content.addView(TextView(this).apply {
                    text = "Повторить"
                    textSize = 15f
                    typeface = interFont(760)
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(16, 17, 20))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = round(red, dp(14), Color.TRANSPARENT, 0)
                    setOnClickListener { showChartsScreen(reload = true) }
                }, marginLp(-1, -2, 0, 0, 0, 12))
            }
            "empty" -> {
                content.addView(TextView(this).apply {
                    text = "Нет данных за выбранный период"
                    textSize = 15f
                    typeface = interFont(650)
                    setTextColor(Color.rgb(111, 119, 129))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                })
            }
            else -> {
                val (from, to) = chartsSelectedRange()
                content.addView(
                    chartsMetricBlock(
                        title = "Напряжение АКБ",
                        minText = chartsMinVoltage?.let { "Мин: %.2f В".format(it) } ?: "Мин: —",
                        maxText = chartsMaxVoltage?.let { "Макс: %.2f В".format(it) } ?: "Макс: —",
                        color = Color.rgb(31, 179, 90),
                        values = chartsPoints.mapNotNull { p ->
                            val v = p.voltage ?: return@mapNotNull null
                            TelemetryChartView.Point(p.timestamp, v)
                        },
                        fromMs = from,
                        toMs = to,
                        showZero = false,
                        tooltipTarget = "voltage"
                    ),
                    marginLp(-1, -2, 0, 0, 0, 12)
                )
                content.addView(
                    chartsMetricBlock(
                        title = "Ток АКБ",
                        minText = chartsMinCurrent?.let { "Мин: %+.1f А".format(it) } ?: "Мин: —",
                        maxText = chartsMaxCurrent?.let { "Макс: %+.1f А".format(it) } ?: "Макс: —",
                        color = Color.rgb(66, 133, 244),
                        values = chartsPoints.mapNotNull { p ->
                            val c = p.current ?: return@mapNotNull null
                            TelemetryChartView.Point(p.timestamp, c)
                        },
                        fromMs = from,
                        toMs = to,
                        showZero = true,
                        tooltipTarget = "current"
                    ),
                    marginLp(-1, -2, 0, 0, 0, 8)
                )
                content.addView(TextView(this).apply {
                    // Семантика тока совпадает с currentDirectionLabel: <0 заряд, >0 разряд.
                    text = "Ток: отрицательный — заряд, положительный — разряд"
                    textSize = 12f
                    setTextColor(Color.rgb(111, 119, 129))
                    setPadding(dp(4), dp(4), dp(4), 0)
                })
            }
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(fixedBottomNav("main"), LinearLayout.LayoutParams(-1, dp(70)))
        setContentView(root)

        if (reload) loadChartsHistory()
    }

    private fun chartsPeriodSelector(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun chip(title: String, mode: String): TextView {
            val selected = chartsPeriodMode == mode
            return TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = interFont(if (selected) 760 else 650)
                setTextColor(if (selected) Color.WHITE else Color.rgb(16, 17, 20))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = round(
                    if (selected) green else Color.WHITE,
                    dp(12),
                    if (selected) green else Color.rgb(223, 229, 235),
                    1
                )
                setOnClickListener {
                    if (mode == "custom") {
                        pickChartsCustomPeriod()
                    } else {
                        chartsPeriodMode = mode
                        showChartsScreen(reload = true)
                    }
                }
            }
        }
        listOf(
            "День" to "day",
            "Неделя" to "week",
            "Месяц" to "month",
            "Период" to "custom"
        ).forEachIndexed { index, (title, mode) ->
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (index > 0) lp.marginStart = dp(6)
            row.addView(chip(title, mode), lp)
        }
        return row
    }

    private fun chartsDateNavigator(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = round(Color.WHITE, dp(14), Color.rgb(223, 229, 235), 1)

            val canNavigate = chartsPeriodMode != "custom"
            addView(TextView(this@MainActivity).apply {
                text = "‹"
                textSize = 28f
                typeface = interFont(700)
                setTextColor(if (canNavigate) Color.rgb(16, 17, 20) else Color.rgb(200, 200, 200))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                isEnabled = canNavigate
                setOnClickListener { if (canNavigate) shiftChartsPeriod(-1) }
            })
            addView(TextView(this@MainActivity).apply {
                text = chartsPeriodLabel()
                textSize = 15f
                typeface = interFont(720)
                setTextColor(Color.rgb(16, 17, 20))
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 28f
                typeface = interFont(700)
                setTextColor(if (canNavigate) Color.rgb(16, 17, 20) else Color.rgb(200, 200, 200))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                isEnabled = canNavigate
                setOnClickListener { if (canNavigate) shiftChartsPeriod(1) }
            })
        }
    }

    private fun pickChartsCustomPeriod() {
        ensureChartsAnchorsInitialized()
        val fromCal = Calendar.getInstance().apply { timeInMillis = chartsCustomFromDayStartMs }
        android.app.DatePickerDialog(
            this,
            { _, y1, m1, d1 ->
                val start = Calendar.getInstance().apply {
                    set(y1, m1, d1, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val toCal = Calendar.getInstance().apply { timeInMillis = chartsCustomToDayStartMs }
                android.app.DatePickerDialog(
                    this,
                    { _, y2, m2, d2 ->
                        var end = Calendar.getInstance().apply {
                            set(y2, m2, d2, 0, 0, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        val maxDay = maxChartsSelectableDayStart()
                        if (end > maxDay) end = maxDay
                        var from = start
                        if (from > maxDay) from = maxDay
                        if (from > end) {
                            toast("Дата начала не может быть позже даты окончания")
                            return@DatePickerDialog
                        }
                        chartsCustomFromDayStartMs = from
                        chartsCustomToDayStartMs = end
                        chartsPeriodMode = "custom"
                        showChartsScreen(reload = true)
                    },
                    toCal.get(Calendar.YEAR),
                    toCal.get(Calendar.MONTH),
                    toCal.get(Calendar.DAY_OF_MONTH)
                ).apply {
                    datePicker.maxDate = maxChartsSelectableDayStart()
                    setTitle("Дата окончания")
                    show()
                }
            },
            fromCal.get(Calendar.YEAR),
            fromCal.get(Calendar.MONTH),
            fromCal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = maxChartsSelectableDayStart()
            setTitle("Дата начала")
            show()
        }
    }

    private fun chartsMetricBlock(
        title: String,
        minText: String,
        maxText: String,
        color: Int,
        values: List<TelemetryChartView.Point>,
        fromMs: Long,
        toMs: Long,
        showZero: Boolean,
        tooltipTarget: String
    ): LinearLayout {
        val card = card()
        card.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = interFont(740)
            setTextColor(Color.rgb(16, 17, 20))
        })
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(4))
        }
        meta.addView(TextView(this).apply {
            text = minText
            textSize = 13f
            typeface = interFont(650)
            setTextColor(Color.rgb(90, 96, 104))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        meta.addView(TextView(this).apply {
            text = maxText
            textSize = 13f
            typeface = interFont(650)
            setTextColor(Color.rgb(90, 96, 104))
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(meta)

        val tooltip = TextView(this).apply {
            text = " "
            textSize = 13f
            typeface = interFont(700)
            setTextColor(color)
            setPadding(0, 0, 0, dp(4))
            minHeight = dp(20)
        }
        if (tooltipTarget == "voltage") chartsVoltageTooltipText = tooltip
        else chartsCurrentTooltipText = tooltip
        card.addView(tooltip)

        val chart = TelemetryChartView(this, showZeroLine = showZero)
        chart.setData(values, fromMs, toMs, color)
        chart.setTooltipListener { point ->
            val tv = if (tooltipTarget == "voltage") chartsVoltageTooltipText else chartsCurrentTooltipText
            if (point == null) {
                tv?.text = " "
                return@setTooltipListener
            }
            val valueText = if (tooltipTarget == "voltage") {
                "%.2f В".format(point.value)
            } else {
                "%+.1f А".format(point.value)
            }
            val (rangeFrom, rangeTo) = chartsSelectedRange()
            val span = rangeTo - rangeFrom
            val timeText = if (span <= 26L * 60L * 60L * 1000L) {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(point.timestamp))
            } else {
                SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(point.timestamp))
            }
            tv?.text = "$valueText\n$timeText"
        }
        card.addView(chart, LinearLayout.LayoutParams(-1, dp(220)))
        return card
    }

    /**
     * Загружает историю V/I с backend для текущей BMS и выбранного периода.
     * Side effects: обновляет charts* state и перерисовывает экран при успехе/ошибке.
     */
    private fun loadChartsHistory() {
        val uid = bmsUid()
        if (uid.isBlank() || uid == "unknown_bms") {
            chartsStatus = "error"
            chartsErrorText = "Не удалось определить BMS для загрузки графика"
            if (screenState == "charts") showChartsScreen(reload = false)
            return
        }
        val (from, to) = chartsSelectedRange()
        val token = ++chartsLoadToken
        chartsStatus = "loading"
        chartsPoints = emptyList()
        chartsLoadedForUid = ""
        if (screenState == "charts") showChartsScreen(reload = false)

        thread {
            val encoded = URLEncoder.encode(uid, "UTF-8")
            val path = "/api/v1/batteries/$encoded/telemetry?from=$from&to=$to&max_points=720"
            val json = adminJsonRequest(
                method = "GET",
                path = path,
                body = null,
                connectTimeoutMs = 10000,
                readTimeoutMs = 25000
            )
            runOnUiThread {
                if (token != chartsLoadToken || screenState != "charts") return@runOnUiThread
                if (json == null || !json.optBoolean("ok", false)) {
                    chartsStatus = "error"
                    chartsErrorText = when (json?.optString("error")) {
                        "unauthorized" -> "Не удалось загрузить данные графика: нет доступа к серверу"
                        "battery_not_found" -> "АКБ ещё нет на сервере. Нужна хотя бы одна отправка телеметрии."
                        "invalid_range" -> "Некорректный период"
                        else -> "Не удалось загрузить данные графика"
                    }
                    showChartsScreen(reload = false)
                    return@runOnUiThread
                }
                val arr = json.optJSONArray("points") ?: JSONArray()
                val points = mutableListOf<TelemetryHistoryPoint>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val ts = item.optLong("timestamp", 0L)
                    if (ts <= 0L) continue
                    val voltage = if (item.isNull("voltage")) null else item.optDouble("voltage")
                    val current = if (item.isNull("current")) null else item.optDouble("current")
                    points.add(TelemetryHistoryPoint(ts, voltage, current))
                }
                points.sortBy { it.timestamp }
                chartsPoints = points
                chartsMinVoltage = json.optDouble("min_voltage").takeIf { json.has("min_voltage") && !json.isNull("min_voltage") }
                chartsMaxVoltage = json.optDouble("max_voltage").takeIf { json.has("max_voltage") && !json.isNull("max_voltage") }
                chartsMinCurrent = json.optDouble("min_current").takeIf { json.has("min_current") && !json.isNull("min_current") }
                chartsMaxCurrent = json.optDouble("max_current").takeIf { json.has("max_current") && !json.isNull("max_current") }
                chartsLoadedForUid = uid
                chartsStatus = if (points.isEmpty()) "empty" else "ok"
                chartsErrorText = ""
                showChartsScreen(reload = false)
            }
        }
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

        val presence = resolveCurrentBmsPresence()
        if (presence != BatteryPresenceState.ONLINE) {
            val inactiveTitle = when (presence) {
                BatteryPresenceState.CHECKING -> "Проверяем состояние батареи…"
                BatteryPresenceState.UNAVAILABLE -> "Батарея не в сети"
                BatteryPresenceState.ONLINE, null -> "Батарея не в сети"
            }
            val inactiveSub = when (presence) {
                BatteryPresenceState.CHECKING -> "Журнал станет доступен после проверки"
                else -> "Журнал доступен только когда батарея в сети"
            }
            content.addView(TextView(this).apply {
                text = inactiveTitle
                textSize = 17f
                typeface = interFont(700)
                setTextColor(Color.rgb(100, 100, 100))
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(40), dp(16), dp(8))
                setBackgroundColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, -2))
            content.addView(TextView(this).apply {
                text = inactiveSub
                textSize = 14f
                setTextColor(Color.rgb(111, 119, 129))
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), dp(40))
                setBackgroundColor(Color.WHITE)
            }, LinearLayout.LayoutParams(-1, -2))
        } else {
            // Только текущие активные fault/alarm из 0x98 — без истории и без details.
            val activeErrors = currentActiveBmsErrors()
            if (activeErrors.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "Активных ошибок нет"
                    textSize = 17f
                    setTextColor(Color.rgb(100, 100, 100))
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(50), dp(16), dp(50))
                    setBackgroundColor(Color.WHITE)
                }
                content.addView(empty, LinearLayout.LayoutParams(-1, -2))
            } else {
                for (title in activeErrors) {
                    content.addView(
                        journalActiveErrorRow(title),
                        marginLp(-1, -2, 0, 0, 0, 8)
                    )
                }
            }
        }

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(fixedBottomNav("journal"), LinearLayout.LayoutParams(-1, dp(70)))

        setContentView(root)
    }

    /**
     * Строка активной ошибки: только название, без перехода в подробности.
     *
     * @param title человекочитаемое имя fault/alarm
     * @return view строки журнала
     */
    private fun journalActiveErrorRow(title: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
            elevation = dp(1).toFloat()
            isClickable = false
            isFocusable = false
        }

        row.addView(TextView(this).apply {
            text = "⚠"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(200, 60, 60))
        }, LinearLayout.LayoutParams(dp(36), -2))

        row.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = interFont(700)
            setTextColor(Color.rgb(40, 40, 40))
        }, LinearLayout.LayoutParams(0, -2, 1f))

        return row
    }

    private fun showEventDetails(number: Int, titleLine: String, detailText: String) {
        // Оставлен для возможных сервисных экранов; клиентский Журнал больше не вызывает.
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

        cancelTargetedScan()
        cancelWakeSequence()
        stopBatteriesPresenceScan()
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
            val name = normalBleName(result)
            val target = targetedScanAddress
            if (target != null && address.equals(target, ignoreCase = true)) {
                val token = targetedScanToken
                Log.i(BMS_BLE_TAG, "Saved BMS found address=$address name=${name.orEmpty()}")
                targetedScanAddress = null
                stopBleScanQuietly()
                devices[address] = device
                scanRssi[address] = result.rssi
                if (!name.isNullOrBlank()) {
                    scanNames[address] = name
                }
                selectedAddress = address
                runOnUiThread {
                    if (token != targetedScanToken) return@runOnUiThread
                    Log.i(BMS_BLE_TAG, "Connecting")
                    connectSelectedDevice()
                }
                return
            }
            // Presence for «Мои батареи»: MAC match is enough (имя может отсутствовать в adv).
            if (batteriesPresenceScanActive && screenState == "batteries") {
                onSavedBatterySeenInPresenceScan(address, device, result.rssi, name)
            }
            if (name == null) return
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

        cancelTargetedScan()
        cancelWakeSequence()
        polling = false
        bluetoothGatt?.close()
        if (screenState == "search" && ::statusText.isInitialized) {
            statusText.text = "Подключение к $address..."
        }
        Log.i(BMS_BLE_TAG, "Connecting to $address")

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        cancelTargetedScan()
        cancelWakeSequence()
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
                Log.i(BMS_BLE_TAG, "GATT connected")
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
                cancelWakeSequence()
                polling = false
                pollLoopToken++
                resetRemoteWriteState()
                configAutoReadStartedForConnection = false
                latestTemplateCheck = null
                // Не держим cached faults как «текущие» после потери связи.
                data.errors.clear()
                lastErrorSignature = ""
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
                    if (screenState == "journal") {
                        showJournalScreen()
                    } else if (screenState == "dashboard") {
                        updateDashboardUi()
                    }
                    if (screenState == "loading") {
                        showBatteriesScreen(asRootHome = true)
                    } else {
                        refreshBatteriesScreenIfVisible()
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
            Log.i(BMS_BLE_TAG, "Services discovered status=$status count=${gatt.services.size}")
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

            enableNotifications(gatt, notifyCharacteristic!!)
            Log.i(BMS_BLE_TAG, "Notifications enabled")
            // polling до UI: карточки «Мои батареи» должны сразу видеть online.
            polling = true
            runOnUiThread {
                saveCurrentBattery(force = true)
                if (returnToBatteriesAfterConnect) {
                    returnToBatteriesAfterConnect = false
                    showBatteriesScreen(asRootHome = true)
                } else {
                    showDashboardScreen(asRootHome = true)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(BLE_LOG_TAG, "CCCD write uuid=${descriptor.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                polling = false
                cancelWakeSequence()
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
                // Сначала wake/read (0x90), затем обычный poll — без параллельных write.
                startBmsWakeSequence()
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
        // Валидный ответ Daly подтверждает доступность (не факт одной отправки write).
        markBatteryOnlineFromValidResponse(selectedAddress, source = "daly_0x%02X".format(cmd))
        if (wakeInProgress) {
            // Любой валидный ответ Daly после connect = MCU очнулась.
            onWakeResponseReceived()
        }
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

        fun applyMos(on: Boolean?, sw: Switch?) {
            if (sw == null) return
            suppressMosSwitchCallback = true
            try {
                if (on != null) {
                    sw.isChecked = on
                    sw.visibility = View.VISIBLE
                }
                // Только отображение: пользователь не переключает MOS.
                sw.isClickable = false
                sw.isFocusable = false
                sw.isEnabled = false
            } finally {
                suppressMosSwitchCallback = false
            }
        }
        applyMos(data.chargeMos, chargeMosSwitch)
        applyMos(data.dischargeMos, dischargeMosSwitch)

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
            val activeErrors = currentActiveBmsErrors()
            if (activeErrors.isNotEmpty()) {
                balanceValue.text = "Ошибка"
                balanceValue.setTextColor(Color.rgb(239, 83, 80))
                balanceIconHost?.let {
                    fillMetricIconBlock(it, glyph = "⚠", glyphColor = Color.rgb(239, 83, 80))
                }
                applyStatusDot(balanceDot, false)
            } else {
                balanceValue.text = "Норма"
                balanceValue.setTextColor(Color.rgb(31, 179, 90))
                balanceIconHost?.let {
                    fillMetricIconBlock(it, glyph = "✓", glyphColor = Color.rgb(31, 179, 90))
                }
                applyStatusDot(balanceDot, true)
            }
        }
        stateValue?.let { state ->
            val activeErrors = currentActiveBmsErrors()
            when {
                activeErrors.isNotEmpty() -> {
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
                    state.text = "Активна"
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
            val activeErrors = currentActiveBmsErrors()
            batteryInfoText.text = if (activeErrors.isEmpty()) {
                "✓  Нормальное состояние · Устройство: $device$cycles"
            } else {
                "Ошибка: ${activeErrors.first()} · Устройство: $device$cycles"
            }
        }

        cellDiffHeaderValue?.text = data.cellDiffV?.let { "Разбег: %.0f мВ".format(it * 1000.0) }.orEmpty()

        overallStatusBanner?.let { banner ->
            val title = overallStatusTitle
            val sub = overallStatusSub
            val check = currentTemplateCheck()
            val activeErrors = currentActiveBmsErrors()

            fun setBannerSub(text: String) {
                if (text.isBlank()) {
                    sub?.text = ""
                    sub?.visibility = View.GONE
                } else {
                    sub?.text = text
                    sub?.visibility = View.VISIBLE
                }
            }

            when {
                // Реальные BMS fault — отдельная логика, не config mismatch.
                activeErrors.isNotEmpty() -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "Ошибка: ${activeErrors.first()}"
                    title?.setTextColor(Color.rgb(239, 83, 80))
                    setBannerSub(
                        if (activeErrors.size > 1) "Ещё ошибок: ${activeErrors.size - 1}" else ""
                    )
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "unavailable" ||
                    (serverTemplateFetchStatus == "error" && activeServerTemplate == null) -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "⚠  Проверка конфигурации недоступна"
                    title?.setTextColor(Color.rgb(224, 150, 0))
                    setBannerSub(
                        serverTemplateFetchError
                            ?: "Не удалось получить актуальный шаблон с сервера"
                    )
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "checking" || serverTemplateFetchStatus == "fetching" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "…  Идёт инициализация BMS"
                    title?.setTextColor(Color.rgb(111, 119, 129))
                    setBannerSub("")
                }
                check?.status == "mismatch" -> {
                    // ConfigCheckResult.mismatch — без деталей параметров на Главной.
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "✕  Не соответствует"
                    title?.setTextColor(Color.rgb(211, 47, 47))
                    setBannerSub("")
                }
                check?.status == "incomplete" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "⚠  Проверка конфигурации неполная"
                    title?.setTextColor(Color.rgb(224, 150, 0))
                    setBannerSub("Не все параметры удалось прочитать или сравнить")
                    sub?.setTextColor(Color.rgb(111, 119, 129))
                }
                check?.status == "ok" -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "✓  Батарея в норме"
                    title?.setTextColor(Color.rgb(31, 179, 90))
                    setBannerSub("")
                }
                else -> {
                    banner.background = round(Color.WHITE, dp(16), Color.rgb(223, 229, 235), 1)
                    title?.text = "…  Идёт инициализация BMS"
                    title?.setTextColor(Color.rgb(111, 119, 129))
                    setBannerSub("")
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
        obj.put(
            "owner_phone",
            normalizePhoneE164(prefs.getString("phone", "")?.trim().orEmpty())
        )
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
        if (isRoutineMosJournalLine(text) || isRoutineMosJournalLine(" — $text")) return

        val line = "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} — $text"
        localEvents.add(line)
        localEventDetails.add(detail)
        if (localEvents.size > 300) {
            localEvents.removeAt(0)
            if (localEventDetails.isNotEmpty()) localEventDetails.removeAt(0)
        }
    }

    /**
     * Routine MOS ON/OFF из 0x93 — текущее состояние BMS, не ошибка журнала.
     * Реальные fault/protection по 0x98 (в т.ч. залипание/обрыв MOS) сюда не попадают.
     */
    private fun isRoutineMosJournalLine(line: String): Boolean {
        val eventText = line.substringAfter(" — ", line)
        val patterns = listOf(
            Regex("""^MOS зарядки:\s*(ON|OFF)$""", RegexOption.IGNORE_CASE),
            Regex("""^MOS разрядки:\s*(ON|OFF)$""", RegexOption.IGNORE_CASE),
            Regex("""^MOS зарядки\s+(ВКЛ|ВЫКЛ)$""", RegexOption.IGNORE_CASE),
            Regex("""^MOS разрядки\s+(ВКЛ|ВЫКЛ)$""", RegexOption.IGNORE_CASE)
        )
        return patterns.any { it.containsMatchIn(eventText.trim()) }
    }

    /** События журнала для UI: без routine MOS ON/OFF, новые сверху. */
    private fun journalDisplayEvents(): List<Pair<String, String>> {
        val events = localEvents.takeLast(100)
        val details = localEventDetails.takeLast(100)
        val result = mutableListOf<Pair<String, String>>()
        for (i in events.indices.reversed()) {
            val line = events[i]
            if (isRoutineMosJournalLine(line)) continue
            result.add(line to (details.getOrNull(i) ?: line))
        }
        return result
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
        val errorsChanged = errSig != lastErrorSignature
        if (errorsChanged) {
            // История инцидентов строится на сервере по snapshot errors[].
            // Локальный журнал клиента показывает только текущий fault state.
            lastErrorSignature = errSig
            if (screenState == "journal") {
                showJournalScreen()
            }
        }

        val chg = data.chargeMos
        if (chg != null && chg != lastChargeMos) {
            // Routine MOS ON/OFF — текущее состояние, не событие журнала ошибок.
            lastChargeMos = chg
        }

        val dsg = data.dischargeMos
        if (dsg != null && dsg != lastDischargeMos) {
            lastDischargeMos = dsg
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
        lastUploadStatus = "Сохранение…"
        refreshUploadStatusUi()
        lastUploadAt = now

        thread {
            val payload = buildUploadJson()
            var ok = false
            var message: String
            try {
                // 1) LOCAL SAVE (независимо от сети и BLE после этого момента)
                ru.liferych.bms.telemetry.TelemetryLocalRepository.enqueue(this, payload)
                // 2) Попытка sync (не требует BLE)
                val done = ru.liferych.bms.telemetry.TelemetryLocalRepository.syncOnce(this)
                val pending = ru.liferych.bms.telemetry.TelemetryLocalRepository.pendingCount(this)
                ru.liferych.bms.telemetry.TelemetrySyncScheduler.enqueueImmediate(this)
                ok = true
                message = when {
                    pending > 0 && done == 0 -> "Локально сохранено, ожидает отправки: $pending"
                    pending > 0 -> "Синхронизировано +$done, ожидает: $pending"
                    else -> "Синхронизировано"
                }
            } catch (e: Exception) {
                message = "Ошибка локального сохранения: ${e.message ?: "unknown"}"
                Log.w(BLE_LOG_TAG, message)
            }
            uploading = false
            lastUploadStatus = message
            runOnUiThread {
                refreshUploadStatusUi()
                if (!ok && (force || isServiceApp())) {
                    toast(lastUploadStatus)
                }
                if (ok) {
                    Log.i(BLE_LOG_TAG, "telemetry local+sync: $lastUploadStatus")
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

    private fun adminJsonRequest(
        method: String,
        path: String,
        body: JSONObject? = null,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 8000
    ): JSONObject? {
        return try {
            val conn = (URL(adminServerUrl(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
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
        val recordedAt = System.currentTimeMillis()
        val eventId = java.util.UUID.randomUUID().toString()
        obj.put("api_key", BmsApiConfig.API_KEY)
        obj.put("bms_uid", bmsUid())
        obj.put("bluetooth_name", dalyBluetoothDeviceId())
        obj.put("bluetooth_address", selectedAddress ?: "")
        obj.put("event_id", eventId)
        obj.put("recorded_at", recordedAt)
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
        if (requestCode == WARRANTY_CAMERA_PERMISSION_REQUEST_CODE) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchWarrantyCamera()
            } else {
                toast("Для съёмки фото необходимо разрешение на камеру")
            }
            return
        }
        if (requestCode != 1001) return
        if (screenState == "batteries") {
            if (hasBlePermissions()) {
                startBatteriesPresenceScan(resetSeen = true)
            } else {
                batteriesPresenceScanCompleted = true
                refreshAllBatteryPresenceCards()
                toast("Для проверки батарей нужен доступ к Bluetooth")
            }
            return
        }
        if (screenState != "loading") return
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

    override fun onResume() {
        super.onResume()
        // При возврате из background presence мог устареть — перепроверяем.
        if (screenState == "batteries" && loadSavedBatteries().isNotEmpty()) {
            Log.d(BATTERY_AVAILABILITY_TAG, "onResume restart presence check")
            startBatteriesPresenceScan(resetSeen = true)
        }
    }

    override fun onStop() {
        super.onStop()
        ru.liferych.bms.telemetry.TelemetrySyncScheduler.enqueueImmediate(this)
        ru.liferych.bms.telemetry.TelemetryLocalRepository.syncAsync(this)
    }

    override fun onDestroy() {
        ru.liferych.bms.telemetry.TelemetrySyncScheduler.enqueueImmediate(this)
        ru.liferych.bms.telemetry.TelemetryLocalRepository.syncAsync(this)
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
