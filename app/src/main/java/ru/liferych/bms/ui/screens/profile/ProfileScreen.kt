package ru.liferych.bms.ui.screens.profile

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.liferych.bms.ui.components.LiferychTopBar
import ru.liferych.bms.ui.model.ProfileUi
import ru.liferych.bms.ui.theme.LiferychColors
import ru.liferych.bms.ui.theme.LiferychDimens
import ru.liferych.bms.ui.theme.LiferychTheme

/**
 * CLIENT Profile phases matching legacy showGuestProfileScreen / showProfileScreen.
 * No auth API / token / storage changes in this visual stage.
 */
sealed class ProfileUiPhase {
    data object Guest : ProfileUiPhase()

    data class Authorized(
        val name: String,
        val phoneNational: String,
        val email: String,
        val birth: String,
        val statusMessage: String = "",
    ) : ProfileUiPhase()
}

/**
 * CLIENT Profile screen — visual port of legacy profile (guest + authorized form + logout).
 *
 * @param profile seed fake profile from ViewModel
 * @param onBack leave profile tab
 * @param initialPhase optional phase for screenshots / previews
 * @param modifier layout modifier
 */
@Composable
fun ProfileScreen(
    profile: ProfileUi,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPhase: ProfileUiPhase? = null,
) {
    var phase by remember {
        mutableStateOf(
            initialPhase ?: if (profile.isAuthorized) {
                ProfileUiPhase.Authorized(
                    name = profile.displayName,
                    phoneNational = nationalFromDisplayPhone(profile.phone),
                    email = profile.email,
                    birth = profile.birthDate,
                )
            } else {
                ProfileUiPhase.Guest
            },
        )
    }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LiferychColors.Background)
            .imePadding(),
    ) {
        LiferychTopBar(title = "Профиль", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (val current = phase) {
                ProfileUiPhase.Guest -> ProfileGuestBody(
                    onLogin = {
                        // UI shell only — login/register screens stay in legacy for now.
                        phase = ProfileUiPhase.Authorized(
                            name = profile.displayName.ifBlank { "Демо пользователь" },
                            phoneNational = nationalFromDisplayPhone(profile.phone).ifBlank { "9000000000" },
                            email = profile.email,
                            birth = profile.birthDate,
                            statusMessage = "Вход недоступен в UI-preview (auth API не подключён)",
                        )
                    },
                    onRegister = {
                        phase = ProfileUiPhase.Authorized(
                            name = "",
                            phoneNational = "",
                            email = "",
                            birth = "",
                            statusMessage = "Регистрация недоступна в UI-preview (auth API не подключён)",
                        )
                    },
                )

                is ProfileUiPhase.Authorized -> ProfileAuthorizedBody(
                    form = current,
                    onFormChange = { phase = it },
                    onSave = {
                        if (current.name.isBlank()) {
                            phase = current.copy(statusMessage = "Укажите ФИО")
                        } else if (digitsNational(current.phoneNational).length != 10) {
                            phase = current.copy(statusMessage = "Укажите 10 цифр номера")
                        } else {
                            phase = current.copy(
                                statusMessage = "Профиль сохранён локально (preview, API не вызван)",
                            )
                        }
                    },
                    onLogoutClick = { showLogoutConfirm = true },
                    onChangePhoto = {
                        phase = current.copy(
                            statusMessage = "Смена фото недоступна в UI-preview",
                        )
                    },
                )
            }
        }
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
                        // Visual-only logout — no prefs / API / battery wipe.
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
private fun ProfileAuthorizedBody(
    form: ProfileUiPhase.Authorized,
    onFormChange: (ProfileUiPhase.Authorized) -> Unit,
    onSave: () -> Unit,
    onLogoutClick: () -> Unit,
    onChangePhoto: () -> Unit,
) {
    // Parent NavHost Scaffold already applies bottom-bar innerPadding.
    // imePadding (on ProfileScreen) + navigationBars + section spacing keep
    // СОХРАНИТЬ / logout fully scrollable above the nav and keyboard.
    val canSave = isProfileSaveReady(name = form.name, phoneNational = form.phoneNational)
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
                    .clickable(onClick = onChangePhoto),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = "Аватар",
                    tint = LiferychColors.IconMuted,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            val changeShape = RoundedCornerShape(12.dp)
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(changeShape)
                    .background(LiferychColors.Surface)
                    .border(1.dp, LiferychColors.Border, changeShape)
                    .clickable(onClick = onChangePhoto)
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
                    text = "Изменить фото",
                    color = LiferychColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        val cardShape = RoundedCornerShape(LiferychDimens.CardRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(LiferychColors.Surface)
                .border(LiferychDimens.CardBorderWidth, LiferychColors.Border, cardShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            ProfileFieldLabel("ФИО *")
            ProfileTextField(
                value = form.name,
                hint = "Иванов Иван Иванович",
                onValueChange = { onFormChange(form.copy(name = it, statusMessage = "")) },
                keyboardType = KeyboardType.Text,
            )

            ProfileFieldLabel("Номер телефона *")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileTextField(
                    value = "+7",
                    hint = "+7",
                    onValueChange = {},
                    enabled = false,
                    modifier = Modifier.width(64.dp),
                )
                Spacer(Modifier.width(8.dp))
                ProfileTextField(
                    value = form.phoneNational,
                    hint = "999 123-45-67",
                    onValueChange = { onFormChange(form.copy(phoneNational = it, statusMessage = "")) },
                    modifier = Modifier.weight(1f),
                    keyboardType = KeyboardType.Phone,
                )
            }

            ProfileFieldLabel("Email")
            ProfileTextField(
                value = form.email,
                hint = "name@example.ru",
                onValueChange = { onFormChange(form.copy(email = it, statusMessage = "")) },
                keyboardType = KeyboardType.Email,
            )

            ProfileFieldLabel("Дата рождения")
            ProfileTextField(
                value = form.birth,
                hint = "ДД.ММ.ГГГГ",
                onValueChange = { onFormChange(form.copy(birth = it, statusMessage = "")) },
                keyboardType = KeyboardType.Number,
            )

            Text(
                text = "Поля ФИО и телефон обязательны для добавления АКБ.",
                color = LiferychColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        PrimaryProfileButton(
            text = "СОХРАНИТЬ",
            enabled = canSave,
            onClick = onSave,
        )
        Spacer(Modifier.height(18.dp))
        LogoutProfileButton(onClick = onLogoutClick)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "При выходе локальные АКБ очищаются. На сервере ваши АКБ сохраняются и вернутся при повторном входе.",
            color = LiferychColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = LiferychDimens.Space16),
        )
        if (form.statusMessage.isNotBlank()) {
            Text(
                text = form.statusMessage,
                color = Color(0xFF5A5A5A),
                fontSize = 13.sp,
            )
        }
    }
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
    // Legacy: Color.rgb(180, 35, 45) — destructive matches existing client Profile.
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
 * Extracts national digits from a display phone like «+7 (900) 000-00-00».
 */
private fun nationalFromDisplayPhone(phone: String): String {
    return digitsNational(phone)
}

/** National 10 digits for RU phone (legacy extractRuNationalDigits rules). */
private fun digitsNational(phone: String): String {
    var digits = phone.filter { it.isDigit() }
    if (digits.startsWith("8") && digits.length == 11) {
        digits = digits.drop(1)
    } else if (digits.startsWith("7") && digits.length >= 11) {
        digits = digits.drop(1)
    }
    return digits.take(10)
}

/**
 * Legacy required fields for save: non-blank ФИО + exactly 10 national digits.
 * UI enablement only — no API call.
 */
private fun isProfileSaveReady(name: String, phoneNational: String): Boolean {
    return name.isNotBlank() && digitsNational(phoneNational).length == 10
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
            initialPhase = ProfileUiPhase.Guest,
        )
    }
}
