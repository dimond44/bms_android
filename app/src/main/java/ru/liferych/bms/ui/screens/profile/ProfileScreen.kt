package ru.liferych.bms.ui.screens.profile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ru.liferych.bms.data.auth.ProfileAvatarFiles
import ru.liferych.bms.domain.auth.AuthState
import ru.liferych.bms.domain.auth.RuPhone
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.model.ProfileAuthFeedback
import ru.liferych.bms.ui.model.ProfileAvatarUi
import ru.liferych.bms.ui.model.ProfileUi
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme
import java.io.File

/**
 * CLIENT Profile navigation phases.
 * Guest / Authorized visuals match the approved design; Login / Register are wired forms.
 */
sealed class ProfileUiPhase {
    data object Guest : ProfileUiPhase()

    data class Login(
        val phoneNational: String = "+",
    ) : ProfileUiPhase()

    data class Register(
        val name: String = "",
        val phoneNational: String = "+",
    ) : ProfileUiPhase()

    data class Authorized(
        val name: String,
        val phoneNational: String,
        val email: String,
        val birth: String,
        val statusMessage: String = "",
    ) : ProfileUiPhase()
}

/**
 * CLIENT Profile screen — guest / login / register / authorized.
 *
 * Design of Guest and Authorized bodies is unchanged.
 * Auth I/O goes through ViewModel callbacks (no SharedPreferences / API in UI).
 * Avatar pickers live in the UI layer; upload goes through [onAvatarPicked].
 *
 * @param profile projected profile for Support defaults / version label
 * @param authState real session state from AuthRepository
 * @param feedback submit/error status from ViewModel
 * @param avatarUi avatar upload/preview status
 * @param onBack leave profile tab
 * @param onLoginSubmit national phone → login
 * @param onRegisterSubmit name + national phone → register
 * @param onSaveProfile authorized form save
 * @param onLogout confirm logout
 * @param onClearFeedback clear status flags
 * @param onAvatarPicked URI from camera/gallery → encode+upload
 * @param onClearAvatarMessage clear avatar status text
 * @param openLogin one-shot: jump to Login (Guest AddBattery gate)
 * @param onOpenLoginConsumed acknowledge [openLogin]
 * @param onCancelAuthFlow clear pending AddBattery when leaving Login/Register without success
 * @param initialPhase optional override for Preview / screenshots
 * @param modifier layout modifier
 */
@Composable
fun ProfileScreen(
    profile: ProfileUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    authState: AuthState = if (profile.isAuthorized) {
        AuthState.Authorized(
            ru.liferych.bms.domain.auth.UserProfile(
                fullName = profile.displayName,
                phoneE164 = RuPhone.toE164(profile.phone),
                email = profile.email,
                birthDate = profile.birthDate,
            ),
        )
    } else {
        AuthState.Guest
    },
    feedback: ProfileAuthFeedback = ProfileAuthFeedback(),
    avatarUi: ProfileAvatarUi = ProfileAvatarUi(),
    onLoginSubmit: (phoneNational: String) -> Unit = {},
    onRegisterSubmit: (name: String, phoneNational: String) -> Unit = { _, _ -> },
    onSaveProfile: (name: String, phoneNational: String, email: String, birth: String) -> Unit = { _, _, _, _ -> },
    onLogout: () -> Unit = {},
    onClearFeedback: () -> Unit = {},
    onAvatarPicked: (Uri) -> Unit = {},
    onClearAvatarMessage: () -> Unit = {},
    openLogin: Boolean = false,
    onOpenLoginConsumed: () -> Unit = {},
    onCancelAuthFlow: () -> Unit = {},
    initialPhase: ProfileUiPhase? = null,
) {
    val context = LocalContext.current
    val avatarFiles = remember { ProfileAvatarFiles(context) }
    var phase by remember {
        mutableStateOf(
            initialPhase ?: phaseFromAuth(authState, profile),
        )
    }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showAvatarChooser by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) onAvatarPicked(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (success && uri != null) onAvatarPicked(uri)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchProfileCamera(
                avatarFiles = avatarFiles,
                onUriReady = { uri ->
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onError = {
                    // Surface via avatar message path by picking nothing.
                },
            )
        }
    }

    fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchProfileCamera(
            avatarFiles = avatarFiles,
            onUriReady = { uri ->
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            },
            onError = {},
        )
    }

    fun openGallery() {
        galleryLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // Sync Guest ↔ Authorized with repository session; preserve Login/Register while editing.
    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Authorized -> {
                val p = authState.profile
                phase = ProfileUiPhase.Authorized(
                    name = p.fullName,
                    phoneNational = p.phoneE164,
                    email = p.email,
                    birth = p.birthDate,
                    statusMessage = if (phase is ProfileUiPhase.Authorized) {
                        (phase as ProfileUiPhase.Authorized).statusMessage
                    } else {
                        ""
                    },
                )
                onClearFeedback()
            }
            AuthState.Guest -> {
                if (phase is ProfileUiPhase.Authorized) {
                    phase = ProfileUiPhase.Guest
                }
            }
            AuthState.Loading -> Unit
        }
    }

    // Guest «ДОБАВИТЬ БАТАРЕЮ» → open Login immediately (not Guest hub).
    LaunchedEffect(openLogin) {
        if (!openLogin) return@LaunchedEffect
        if (authState is AuthState.Authorized) {
            onOpenLoginConsumed()
            return@LaunchedEffect
        }
        onClearFeedback()
        phase = ProfileUiPhase.Login(
            phoneNational = RuPhone.toE164(profile.phone).ifBlank { "+" },
        )
        onOpenLoginConsumed()
    }

    // Push save/login status into authorized form message without wiping edits.
    LaunchedEffect(feedback.message, feedback.isSubmitting) {
        val current = phase
        if (current is ProfileUiPhase.Authorized && feedback.message.isNotBlank()) {
            phase = current.copy(statusMessage = feedback.message)
        }
    }

    val avatarLocalPath = when {
        avatarUi.localPreviewPath.isNotBlank() -> avatarUi.localPreviewPath
        authState is AuthState.Authorized -> authState.profile.avatarLocalPath
        else -> ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background)
            .imePadding(),
    ) {
        LiferychTopBar(
            title = when (phase) {
                is ProfileUiPhase.Login -> "Вход"
                is ProfileUiPhase.Register -> "Регистрация"
                else -> "Профиль"
            },
            onBack = {
                when (phase) {
                    is ProfileUiPhase.Login,
                    is ProfileUiPhase.Register,
                    -> {
                        onClearFeedback()
                        onCancelAuthFlow()
                        phase = ProfileUiPhase.Guest
                    }
                    else -> onBack()
                }
            },
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (val current = phase) {
                ProfileUiPhase.Guest -> ProfileGuestBody(
                    onLogin = {
                        onClearFeedback()
                        phase = ProfileUiPhase.Login(
                            phoneNational = RuPhone.toE164(profile.phone).ifBlank { "+" },
                        )
                    },
                    onRegister = {
                        onClearFeedback()
                        phase = ProfileUiPhase.Register(
                            phoneNational = RuPhone.toE164(profile.phone).ifBlank { "+" },
                        )
                    },
                )

                is ProfileUiPhase.Login -> ProfileLoginBody(
                    phoneNational = current.phoneNational,
                    feedback = feedback,
                    onPhoneChange = {
                        // Raw display value only — no live formatting (cursor stays stable).
                        phase = current.copy(phoneNational = it)
                    },
                    onSubmit = { onLoginSubmit(current.phoneNational) },
                    onGoRegister = {
                        onClearFeedback()
                        phase = ProfileUiPhase.Register(phoneNational = current.phoneNational)
                    },
                )

                is ProfileUiPhase.Register -> ProfileRegisterBody(
                    name = current.name,
                    phoneNational = current.phoneNational,
                    feedback = feedback,
                    onNameChange = { phase = current.copy(name = it) },
                    onPhoneChange = {
                        // Raw display value only — no live formatting (cursor stays stable).
                        phase = current.copy(phoneNational = it)
                    },
                    onSubmit = { onRegisterSubmit(current.name, current.phoneNational) },
                    onGoLogin = {
                        onClearFeedback()
                        phase = ProfileUiPhase.Login(phoneNational = current.phoneNational)
                    },
                )

                is ProfileUiPhase.Authorized -> ProfileAuthorizedBody(
                    form = current,
                    submitting = feedback.isSubmitting,
                    avatarLocalPath = avatarLocalPath,
                    avatarUploading = avatarUi.isUploading,
                    avatarMessage = avatarUi.message,
                    onFormChange = { phase = it },
                    onSave = {
                        onSaveProfile(
                            current.name,
                            current.phoneNational,
                            current.email,
                            current.birth,
                        )
                    },
                    onLogoutClick = { showLogoutConfirm = true },
                    onChangePhoto = {
                        if (!avatarUi.isUploading) {
                            onClearAvatarMessage()
                            showAvatarChooser = true
                        }
                    },
                )
            }
        }
    }

    if (showAvatarChooser) {
        AlertDialog(
            onDismissRequest = { showAvatarChooser = false },
            title = {
                Text(
                    text = "Фото профиля",
                    color = LiferychColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showAvatarChooser = false
                            openCamera()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Сделать фото",
                            color = LiferychColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                    TextButton(
                        onClick = {
                            showAvatarChooser = false
                            openGallery()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Выбрать из галереи",
                            color = LiferychColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAvatarChooser = false }) {
                    Text("Отмена", color = LiferychColors.TextPrimary)
                }
            },
            containerColor = LiferychColors.Surface,
        )
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = {
                Text(
                    text = "Выход",
                    color = LiferychColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "Вы действительно хотите выйти из профиля?",
                    color = LiferychColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        onLogout()
                        phase = ProfileUiPhase.Guest
                    },
                ) {
                    Text("Выйти", color = Color(0xFFB4232D), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Отмена", color = LiferychColors.TextPrimary)
                }
            },
            containerColor = LiferychColors.Surface,
        )
    }
}

private fun phaseFromAuth(authState: AuthState, profile: ProfileUi): ProfileUiPhase {
    return when (authState) {
        is AuthState.Authorized -> ProfileUiPhase.Authorized(
            name = authState.profile.fullName,
            phoneNational = authState.profile.phoneE164,
            email = authState.profile.email,
            birth = authState.profile.birthDate,
        )
        AuthState.Guest,
        AuthState.Loading,
        -> if (profile.isAuthorized) {
            ProfileUiPhase.Authorized(
                name = profile.displayName,
                phoneNational = RuPhone.toE164(profile.phone).ifBlank { profile.phone },
                email = profile.email,
                birth = profile.birthDate,
            )
        } else {
            ProfileUiPhase.Guest
        }
    }
}

@Composable
private fun ProfileGuestBody(
    onLogin: () -> Unit,
    onRegister: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = LiferychDimens.ScreenPadding)
            .padding(top = 18.dp, bottom = LiferychDimens.SectionSpacingLarge),
    ) {
        Text(
            text = "Профиль",
            color = LiferychColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Вы не авторизованы.\nВойдите, чтобы добавлять АКБ и синхронизировать список между устройствами.",
            color = Color(0xFF5A5A5A),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        PrimaryProfileButton(text = "ВОЙТИ", onClick = onLogin)
        Spacer(Modifier.height(10.dp))
        OutlineProfileButton(text = "ЗАРЕГИСТРИРОВАТЬСЯ", onClick = onRegister)
    }
}

@Composable
private fun ProfileLoginBody(
    phoneNational: String,
    feedback: ProfileAuthFeedback,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoRegister: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = LiferychDimens.ScreenPadding)
            .padding(top = 18.dp, bottom = LiferychDimens.SectionSpacingLarge),
    ) {
        Text(
            text = "Вход",
            color = LiferychColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Введите номер телефона. Если профиль ещё не создан — зарегистрируйтесь.",
            color = Color(0xFF5A5A5A),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        ProfileCard {
            ProfileFieldLabel("Номер телефона *")
            RuPhoneInputRow(
                value = phoneNational,
                onValueChange = onPhoneChange,
                enabled = !feedback.isSubmitting,
            )
        }
        if (feedback.message.isNotBlank()) {
            Text(
                text = feedback.message,
                color = if (feedback.suggestRegister || feedback.message.contains("не найден")) {
                    Color(0xFFB4232D)
                } else {
                    Color(0xFF5A5A5A)
                },
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (feedback.isSubmitting) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LiferychColors.BrandYellowDark,
                )
                Spacer(Modifier.width(8.dp))
                Text("Вход…", color = Color(0xFF5A5A5A), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryProfileButton(
            text = "ВОЙТИ",
            onClick = onSubmit,
            enabled = !feedback.isSubmitting,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Зарегистрироваться",
            color = LiferychColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !feedback.isSubmitting, onClick = onGoRegister)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun ProfileRegisterBody(
    name: String,
    phoneNational: String,
    feedback: ProfileAuthFeedback,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onGoLogin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = LiferychDimens.ScreenPadding)
            .padding(top = 18.dp, bottom = LiferychDimens.SectionSpacingLarge),
    ) {
        Text(
            text = "Регистрация",
            color = LiferychColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Создайте профиль по номеру телефона. Номер будет уникальным идентификатором.",
            color = Color(0xFF5A5A5A),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        ProfileCard {
            ProfileFieldLabel("ФИО *")
            ProfileTextField(
                value = name,
                hint = "Иванов Иван Иванович",
                onValueChange = onNameChange,
                enabled = !feedback.isSubmitting,
            )
            ProfileFieldLabel("Номер телефона *")
            RuPhoneInputRow(
                value = phoneNational,
                onValueChange = onPhoneChange,
                enabled = !feedback.isSubmitting,
            )
        }
        if (feedback.message.isNotBlank()) {
            Text(
                text = feedback.message,
                color = Color(0xFFB4232D),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (feedback.isSubmitting) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LiferychColors.BrandYellowDark,
                )
                Spacer(Modifier.width(8.dp))
                Text("Регистрация…", color = Color(0xFF5A5A5A), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryProfileButton(
            text = "ЗАРЕГИСТРИРОВАТЬСЯ",
            onClick = onSubmit,
            enabled = !feedback.isSubmitting,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Уже есть профиль? Войти",
            color = LiferychColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !feedback.isSubmitting, onClick = onGoLogin)
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun ProfileAuthorizedBody(
    form: ProfileUiPhase.Authorized,
    submitting: Boolean,
    avatarLocalPath: String,
    avatarUploading: Boolean,
    avatarMessage: String,
    onFormChange: (ProfileUiPhase.Authorized) -> Unit,
    onSave: () -> Unit,
    onLogoutClick: () -> Unit,
    onChangePhoto: () -> Unit,
) {
    val canSave = isProfileSaveReady(name = form.name, phoneNational = form.phoneNational) &&
        !submitting
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = LiferychDimens.ScreenPadding)
            .padding(top = 18.dp, bottom = LiferychDimens.SectionSpacingLarge),
    ) {
        Text(
            text = "Профиль",
            color = LiferychColors.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 14.dp),
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF6F7F9))
                    .border(2.dp, LiferychColors.BrandYellowDark, CircleShape)
                    .clickable(enabled = !avatarUploading, onClick = onChangePhoto),
                contentAlignment = Alignment.Center,
            ) {
                ProfileAvatarContent(localPath = avatarLocalPath)
                if (avatarUploading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x66FFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = LiferychColors.BrandYellowDark,
                            strokeWidth = 3.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val changeShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(changeShape)
                    .background(LiferychColors.Surface)
                    .border(1.dp, LiferychColors.Border, changeShape)
                    .clickable(enabled = !avatarUploading, onClick = onChangePhoto)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = LiferychColors.TextPrimary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (avatarUploading) "Загрузка…" else "Изменить фото",
                    color = LiferychColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (avatarMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = avatarMessage,
                    color = if (avatarMessage.contains("Не удалось") ||
                        avatarMessage.contains("ошиб", ignoreCase = true)
                    ) {
                        Color(0xFFB4232D)
                    } else {
                        LiferychColors.TextSecondary
                    },
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        ProfileCard {
            ProfileFieldLabel("ФИО *")
            ProfileTextField(
                value = form.name,
                hint = "Иванов Иван Иванович",
                onValueChange = { onFormChange(form.copy(name = it, statusMessage = "")) },
                enabled = !submitting,
            )
            ProfileFieldLabel("Номер телефона *")
            RuPhoneInputRow(
                value = form.phoneNational,
                onValueChange = {
                    onFormChange(
                        form.copy(
                            phoneNational = it,
                            statusMessage = "",
                        ),
                    )
                },
                enabled = !submitting,
            )
            ProfileFieldLabel("Email")
            ProfileTextField(
                value = form.email,
                hint = "email@example.com",
                onValueChange = { onFormChange(form.copy(email = it, statusMessage = "")) },
                enabled = !submitting,
                keyboardType = KeyboardType.Email,
            )
            ProfileFieldLabel("Дата рождения")
            ProfileTextField(
                value = form.birth,
                hint = "ДД.ММ.ГГГГ",
                onValueChange = { onFormChange(form.copy(birth = it, statusMessage = "")) },
                enabled = !submitting,
            )
        }

        if (form.statusMessage.isNotBlank()) {
            Text(
                text = form.statusMessage,
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (submitting) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LiferychColors.BrandYellowDark,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        PrimaryProfileButton(
            text = "СОХРАНИТЬ",
            onClick = onSave,
            enabled = canSave,
        )
        Spacer(Modifier.height(18.dp))
        LogoutProfileButton(onClick = onLogoutClick)
        Text(
            text = "При выходе локальные АКБ очищаются. На сервере ваши АКБ сохраняются и вернутся при повторном входе.",
            color = Color(0xFF6F7781),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun ProfileCard(content: @Composable () -> Unit) {
    val cardShape = RoundedCornerShape(LiferychDimens.CardRadius)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(LiferychColors.Surface)
            .border(1.dp, LiferychColors.Border, cardShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Single full-phone field (no fixed +7 prefix, no live formatting).
 *
 * Default / cleared value is `+` with caret after it.
 * E.164 normalization happens only at submit via [RuPhone.toE164].
 *
 * @param value display phone string
 * @param onValueChange updated display value (filtered, not formatted)
 * @param enabled whether editing is allowed
 */
@Composable
private fun RuPhoneInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            ),
        )
    }
    // Sync when parent replaces the value (open Login/Register, auth restore).
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(
                text = value,
                selection = TextRange(value.length),
            )
        }
    }

    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Color(0xFFF6F7F9))
            .border(1.dp, Color(0xFFDFE5EB), shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (fieldValue.text.isEmpty()) {
            Text(text = "+79991234567", color = Color(0xFF8C8C8C), fontSize = 15.sp)
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { incoming ->
                val filtered = filterPhoneInput(incoming.text)
                if (filtered == incoming.text) {
                    // Keep user selection/cursor — do not rebuild TextFieldValue.
                    fieldValue = incoming
                    onValueChange(filtered)
                } else {
                    // Only when stripping junk or restoring minimal "+".
                    val selection = if (filtered == "+") {
                        TextRange(1)
                    } else {
                        TextRange(filtered.length)
                    }
                    fieldValue = TextFieldValue(text = filtered, selection = selection)
                    onValueChange(filtered)
                }
            },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = TextStyle(
                color = LiferychColors.TextPrimary,
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(LiferychColors.BrandYellowDark),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Keeps a leading `+` and digits only. No spaces/brackets/dashes.
 *
 * @param raw TextField value
 * @return filtered display string; empty input becomes `+`
 */
private fun filterPhoneInput(raw: String): String {
    val out = StringBuilder(raw.length.coerceAtLeast(1))
    for (ch in raw) {
        when {
            ch == '+' && out.isEmpty() -> out.append('+')
            ch.isDigit() -> out.append(ch)
        }
    }
    return if (out.isEmpty()) "+" else out.toString()
}

@Composable
private fun ProfileFieldLabel(text: String) {
    Text(
        text = text,
        color = LiferychColors.TextSecondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 10.dp, bottom = 5.dp),
    )
}

@Composable
private fun ProfileTextField(
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(Color(0xFFF6F7F9))
            .border(1.dp, Color(0xFFDFE5EB), shape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(text = hint, color = Color(0xFF8C8C8C), fontSize = 15.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(
                color = LiferychColors.TextPrimary,
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(LiferychColors.BrandYellowDark),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PrimaryProfileButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val background = if (enabled) LiferychColors.BrandYellow else Color(0xFFE8E8E8)
    val foreground = if (enabled) LiferychColors.TextPrimary else Color(0xFF9A9A9A)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun OutlineProfileButton(
    text: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(LiferychColors.Surface)
            .border(1.dp, LiferychColors.Border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = LiferychColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun LogoutProfileButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(Color(0xFFB4232D))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Выйти из профиля",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Legacy required fields for save: non-blank ФИО + exactly 10 national digits.
 */
internal fun isProfileSaveReady(name: String, phoneNational: String): Boolean {
    return name.isNotBlank() && RuPhone.extractNationalDigits(phoneNational).length == 10
}

/**
 * Creates a FileProvider capture URI for profile camera (legacy avatar_capture.jpg).
 *
 * @param avatarFiles path helper
 * @param onUriReady content URI for TakePicture
 * @param onError launch failure
 */
private fun launchProfileCamera(
    avatarFiles: ProfileAvatarFiles,
    onUriReady: (Uri) -> Unit,
    onError: () -> Unit,
) {
    try {
        onUriReady(avatarFiles.createCaptureUri())
    } catch (_: Exception) {
        onError()
    }
}

/**
 * Circular avatar pixels from local JPEG path (legacy ImageView decodeFile).
 * Server URL is cached into the same local file after login/upload.
 *
 * @param localPath absolute path or blank for default icon
 */
@Composable
private fun ProfileAvatarContent(localPath: String) {
    val bitmap = remember(localPath) {
        if (localPath.isBlank()) {
            null
        } else {
            val file = File(localPath)
            if (!file.exists()) {
                null
            } else {
                try {
                    BitmapFactory.Options().run {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeFile(file.absolutePath, this)
                        inSampleSize = sampleSizeForDisplay(outWidth, outHeight, 208)
                        inJustDecodeBounds = false
                        BitmapFactory.decodeFile(file.absolutePath, this)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Аватар",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(104.dp),
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = "Аватар",
            tint = LiferychColors.IconMuted,
            modifier = Modifier.size(48.dp),
        )
    }
}

private fun sampleSizeForDisplay(width: Int, height: Int, maxSide: Int): Int {
    val longest = maxOf(width, height).coerceAtLeast(1)
    var sample = 1
    while (longest / sample > maxSide * 2) {
        sample *= 2
    }
    return sample
}

@Preview(showBackground = true, name = "ProfileAuthorizedPreview", heightDp = 1100)
@Composable
private fun ProfileAuthorizedPreview() {
    LiferychTheme {
        ProfileScreen(
            profile = ProfileUi(
                displayName = "Демо пользователь",
                email = "demo@liferych.ru",
                phone = "+7 (900) 000-00-00",
                birthDate = "",
                appVersionLabel = "Frontend preview",
                isAuthorized = true,
            ),
            onBack = {},
            initialPhase = ProfileUiPhase.Authorized(
                name = "Демо пользователь",
                phoneNational = "9000000000",
                email = "demo@liferych.ru",
                birth = "",
            ),
        )
    }
}

@Preview(showBackground = true, name = "ProfileGuestPreview", heightDp = 800)
@Composable
private fun ProfileGuestPreview() {
    LiferychTheme {
        ProfileScreen(
            profile = ProfileUi(
                displayName = "",
                email = "",
                phone = "",
                appVersionLabel = "Frontend preview",
                isAuthorized = false,
            ),
            onBack = {},
            authState = AuthState.Guest,
            initialPhase = ProfileUiPhase.Guest,
        )
    }
}
