package com.youss17x.playbeam

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App name shown on the home screen and inside the app. */
const val APP_NAME = "PlayBeam PS5"

/** Colors of one theme. */
class Palette(
    val Bg: Color, val Card: Color, val Ink: Color, val Muted: Color, val Faint: Color,
    val Line: Color, val Divider: Color, val Track: Color,
    val Accent: Color, val AccentSoft: Color,
    val Ok: Color, val OkSoft: Color, val OkText: Color,
    val Error: Color, val ErrorSoft: Color,
    /** Main (dark-in-light-mode) button and its text. */
    val Primary: Color, val OnPrimary: Color,
    val LogoBg: Color,
)

private val LightPalette = Palette(
    Bg = Color(0xFFF3F4F7), Card = Color(0xFFFFFFFF), Ink = Color(0xFF0E1424),
    Muted = Color(0xFF5B6475), Faint = Color(0xFF8A92A3),
    Line = Color(0xFFE3E6EC), Divider = Color(0xFFEEF0F4), Track = Color(0xFFE6E8EE),
    Accent = Color(0xFF2F5BEA), AccentSoft = Color(0xFFEAF0FF),
    Ok = Color(0xFF23A55A), OkSoft = Color(0xFFE3F5EA), OkText = Color(0xFF1C6B3F),
    Error = Color(0xFFC62828), ErrorSoft = Color(0xFFFDECEC),
    Primary = Color(0xFF0E1424), OnPrimary = Color.White,
    LogoBg = Color(0xFF0E1424),
)

private val DarkPalette = Palette(
    Bg = Color(0xFF0D1015), Card = Color(0xFF161A21), Ink = Color(0xFFE9EDF3),
    Muted = Color(0xFFA0A9B8), Faint = Color(0xFF6F7888),
    Line = Color(0xFF252B35), Divider = Color(0xFF1F242D), Track = Color(0xFF242A34),
    Accent = Color(0xFF7193FF), AccentSoft = Color(0xFF1C2747),
    Ok = Color(0xFF3CCB7F), OkSoft = Color(0xFF13291D), OkText = Color(0xFF86E3B0),
    Error = Color(0xFFFF6B6B), ErrorSoft = Color(0xFF331A1C),
    Primary = Color(0xFFE9EDF3), OnPrimary = Color(0xFF0D1015),
    LogoBg = Color(0xFF232A36),
)

enum class ThemeMode(val code: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

/** "Pearl" design: calm, iOS-settings-like, in light and dark. Screens redraw when the theme changes. */
object Pearl {
    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var isDark by mutableStateOf(false)
        private set

    private val p: Palette get() = if (isDark) DarkPalette else LightPalette

    val Bg get() = p.Bg
    val Card get() = p.Card
    val Ink get() = p.Ink
    val Muted get() = p.Muted
    val Faint get() = p.Faint
    val Line get() = p.Line
    val Divider get() = p.Divider
    val Track get() = p.Track
    val Accent get() = p.Accent
    val AccentSoft get() = p.AccentSoft
    val Ok get() = p.Ok
    val OkSoft get() = p.OkSoft
    val OkText get() = p.OkText
    val Error get() = p.Error
    val ErrorSoft get() = p.ErrorSoft
    val Primary get() = p.Primary
    val OnPrimary get() = p.OnPrimary
    val LogoBg get() = p.LogoBg

    private fun systemDark(ctx: Context) =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Reads the saved theme (call in onCreate; the activity is recreated when the system theme changes). */
    fun load(ctx: Context) {
        val code = Store.prefs(ctx).getString("theme", null)
        mode = ThemeMode.entries.firstOrNull { it.code == code } ?: ThemeMode.SYSTEM
        isDark = when (mode) {
            ThemeMode.SYSTEM -> systemDark(ctx)
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    }

    fun set(ctx: Context, m: ThemeMode) {
        Store.prefs(ctx).edit().putString("theme", m.code).apply()
        load(ctx)
    }
}

@Composable
private fun pearlColors(): ColorScheme {
    val base = if (Pearl.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Pearl.Accent,
        onPrimary = Color.White,
        primaryContainer = Pearl.AccentSoft,
        onPrimaryContainer = Pearl.Accent,
        secondary = Pearl.Ink,
        onSecondary = Pearl.Bg,
        secondaryContainer = Pearl.AccentSoft,
        onSecondaryContainer = Pearl.Accent,
        background = Pearl.Bg,
        onBackground = Pearl.Ink,
        surface = Pearl.Card,
        onSurface = Pearl.Ink,
        surfaceVariant = Pearl.Track,
        onSurfaceVariant = Pearl.Muted,
        surfaceContainerLowest = Pearl.Card,
        surfaceContainerLow = Pearl.Card,
        surfaceContainer = Pearl.Card,
        surfaceContainerHigh = Pearl.Card,
        surfaceContainerHighest = Pearl.Track,
        outline = Pearl.Line,
        outlineVariant = Pearl.Divider,
        error = Pearl.Error,
    )
}

@Composable
private fun pearlType() = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.9).sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 13.sp, color = Pearl.Muted),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
)

@Composable
fun PearlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = pearlColors(),
        typography = pearlType(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

// ---------- building blocks ----------

@Composable
fun PIcon(@DrawableRes id: Int, tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

@Composable
fun LogoMark(size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Pearl.LogoBg),
        contentAlignment = Alignment.Center
    ) { PIcon(R.drawable.ic_stat_playbeam, Color.White, size * 0.56f) }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineLarge, color = Pearl.Ink, modifier = modifier)
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Pearl.Faint,
        modifier = Modifier.padding(start = 4.dp))
}

@Composable
fun Chip(text: String, fg: Color, bg: Color, dot: Color? = null) {
    Row(
        Modifier.height(30.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dot != null) Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dot))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** White rounded card with a hairline border. */
@Composable
fun PearlCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val border = BorderStroke(1.dp, Pearl.Line)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = Pearl.Card, border = border) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = Pearl.Card, border = border) {
            Column(content = content)
        }
    }
}

/** One row of a settings-style card. */
@Composable
fun SettingsRow(
    label: String,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    divider: Boolean = true,
    @DrawableRes icon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (icon != null) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Pearl.AccentSoft),
                    contentAlignment = Alignment.Center
                ) { PIcon(icon, Pearl.Accent, 18.dp) }
            }
            Column(Modifier.weight(1f).padding(vertical = if (subtitle != null) 10.dp else 0.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Pearl.Ink)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = Pearl.Muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
            }
            if (trailing != null) trailing()
            else if (onClick != null) PIcon(R.drawable.ic_chevron_right, Pearl.Faint, 16.dp)
        }
        if (divider) HorizontalDivider(color = Pearl.Divider,
            modifier = Modifier.padding(start = if (icon != null) 58.dp else 16.dp))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    @DrawableRes icon: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Pearl.Primary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = onColor(container),
            disabledContainerColor = Pearl.Track, disabledContentColor = Pearl.Faint,
        ),
    ) {
        if (icon != null) {
            PIcon(icon, if (enabled) onColor(container) else Pearl.Faint, 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Text color on a button: the theme's OnPrimary on the main button, white on colored ones. */
private fun onColor(container: Color) = if (container == Pearl.Primary) Pearl.OnPrimary else Color.White

@Composable
fun SecondaryButton(
    text: String,
    @DrawableRes icon: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Pearl.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Pearl.Card, contentColor = Pearl.Ink),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        if (icon != null) {
            PIcon(icon, if (enabled) Pearl.Ink else Pearl.Faint, 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Small square number choices (connections). */
@Composable
fun NumberPicker(values: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        values.forEach { n ->
            val on = n == selected
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Pearl.Accent else Pearl.Card)
                    .border(1.dp, if (on) Pearl.Accent else Pearl.Line, RoundedCornerShape(10.dp))
                    .clickable { onSelect(n) },
                contentAlignment = Alignment.Center
            ) {
                Text("$n", color = if (on) Color.White else Pearl.Muted, fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun PearlSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Pearl.Accent, checkedThumbColor = Color.White, checkedBorderColor = Pearl.Accent,
            uncheckedTrackColor = Pearl.Track, uncheckedThumbColor = Color.White, uncheckedBorderColor = Pearl.Track,
        ),
    )
}

@Composable
fun PearlProgress(fraction: Float?, modifier: Modifier = Modifier) {
    if (fraction == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = Pearl.Accent, trackColor = Pearl.Track)
    } else {
        LinearProgressIndicator(progress = { fraction },
            modifier = modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
            color = Pearl.Accent, trackColor = Pearl.Track, drawStopIndicator = {})
    }
}

@Composable
fun pearlFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Pearl.Accent,
    unfocusedBorderColor = Pearl.Line,
    focusedContainerColor = Pearl.Card,
    unfocusedContainerColor = Pearl.Card,
    focusedLabelColor = Pearl.Accent,
    unfocusedLabelColor = Pearl.Muted,
)
