package com.zidex.edms.ui

import android.app.Activity
import android.content.Context
import android.view.Window
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Brand identity, shared by Compose and by anything that needs the raw colour. */
val Brand = Color(0xFF4F46E5)
val Ink = Color(0xFF111827)
val CanvasGray = Color(0xFFF4F5F7)
val Hairline = Color(0xFFE8EAEE)
val Muted = Color(0xFF6B7280)
val CardShape = RoundedCornerShape(20.dp)

// ── Theme mode ────────────────────────────────────────────────────────────────

enum class ThemeMode(val label: String) { SYSTEM("حسب النظام"), LIGHT("فاتح"), DARK("داكن") }

private fun prefs(ctx: Context) = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

private val modeState = mutableStateOf(ThemeMode.SYSTEM)

/** Persisted light/dark/system choice; safe to call from any composable. */
@Composable
fun rememberThemeMode(): androidx.compose.runtime.MutableState<ThemeMode> {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mode = remember { modeState }
    LaunchedEffect(Unit) {
        if (!prefs(ctx).contains(KEY)) return@LaunchedEffect
        mode.value = prefs(ctx).getString(KEY, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }
    return mode
}

private const val KEY = "mode"

fun setThemeMode(ctx: Context, mode: ThemeMode) {
    modeState.value = mode
    prefs(ctx).edit().putString(KEY, mode.name).apply()
}

/** The background motif's on/off state, read once at start-up and written on change. */
fun readBackdrop(ctx: Context) = prefs(ctx).getBoolean(KEY_BG, true)

fun setBackdrop(ctx: Context, on: Boolean) {
    Session.backdrop = on
    prefs(ctx).edit().putBoolean(KEY_BG, on).apply()
}

private const val KEY_BG = "backdrop"

/**
 * Full colour schemes, not the four-colour version this used to have: the rest
 * of Material 3's roles (containers, on-* pairs, error) fell back to the
 * library defaults, which is why the dark drawer showed default purple
 * surfaces instead of the archive's own palette.
 */
private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    background = CanvasGray,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFEBEEF3),
    surfaceContainerHighest = Color(0xFFE5E9F0),
    outline = Color(0xFF9AA3B2),
    outlineVariant = Hairline,
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFCD34D),
    onTertiary = Color(0xFF451A03),
    background = Color(0xFF0A0B12),
    onBackground = Color(0xFFE8EAF2),
    surface = Color(0xFF12131C),
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Color(0xFF1C1E2B),
    onSurfaceVariant = Color(0xFF9BA3B7),
    surfaceContainerLowest = Color(0xFF0A0B12),
    surfaceContainerLow = Color(0xFF12131C),
    surfaceContainer = Color(0xFF171926),
    surfaceContainerHigh = Color(0xFF1D2030),
    surfaceContainerHighest = Color(0xFF242838),
    outline = Color(0xFF4A5064),
    outlineVariant = Color(0xFF272B3B),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

@Composable
fun EdmsTheme(content: @Composable () -> Unit) {
    val mode by rememberThemeMode()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val scheme = if (dark) DarkScheme else LightScheme

    // The app draws edge to edge, so the system-bar icons have to follow the
    // scheme or they vanish into the background on a dark screen.
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window: Window? = (view.context as? Activity)?.window
        LaunchedEffect(dark, window) {
            window?.let {
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    MaterialTheme(colorScheme = scheme) {
        // Arabic UI: right-to-left regardless of the device language.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}

// ── Keyboard insets ───────────────────────────────────────────────────────────

/**
 * Keeps a text field's content above the soft keyboard.
 *
 * `enableEdgeToEdge()` in MainActivity means the window no longer resizes for
 * the IME, so a focused field near the bottom used to sit *behind* the keyboard
 * with no way to scroll to it. `imePadding()` shrinks this container to the
 * space above the keyboard, and `imeNestedScroll()` lets a scrolling parent
 * bring the focused field into view when it is off-screen.
 *
 * Applied at the root of every screen rather than per field, so it covers
 * every input in the app including dialogs.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.aboveKeyboard(scrollable: Boolean = true): Modifier {
    val base = Modifier.imePadding()
    val nested = if (scrollable) Modifier.imeScrollSafe() else Modifier
    return this.then(base).then(nested)
}

/**
 * `imeNestedScroll()` is still experimental in this Compose version, so the
 * opt-in lives here once instead of on every scroll container.
 */
@OptIn(ExperimentalLayoutApi::class)
fun Modifier.imeScrollSafe(): Modifier = this.imeNestedScroll()

// ── Background decoration ─────────────────────────────────────────────────────

/**
 * Slow, low-contrast archive motif behind everything: drifting tinted blobs and
 * a grid of faint document cards. It follows the finger (a few pixels of
 * parallax, damped) so the screen feels alive without ever pulling focus.
 *
 * Deliberately cheap: one Canvas, ~5 radial gradients and ~24 rounded rects per
 * frame, no allocation per frame, and it stops entirely when [enabled] is false
 * (which the settings screen can turn off for battery).
 */
@Composable
fun ArchiveBackdrop(modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (!enabled) return
    val transition = rememberInfiniteTransition(label = "backdrop")
    // 48s / 67s / 91s: mutually prime-ish periods, so the pattern never visibly
    // repeats and never syncs into a pulse.
    val a by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(48_000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val b by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(67_000, easing = LinearEasing), RepeatMode.Restart), label = "b")
    val c by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(91_000, easing = LinearEasing), RepeatMode.Reverse), label = "c")

    // Touch parallax: keep only the latest pointer position, damped by the draw.
    var touch by remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    var hasTouch by remember { mutableStateOf(false) }

    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminanceIsDark()
    val tint = scheme.primary
    val cardTint = scheme.onSurface
    val accent2 = scheme.secondary
    val accent3 = scheme.tertiary

    Canvas(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.pressed }
                    if (change != null) {
                        touch = change.position
                        hasTouch = true
                    } else if (hasTouch) {
                        hasTouch = false
                    }
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        // Damp the pointer: the motif leans towards the finger by at most 3% of
        // the screen, so it never feels like a scroll hijack.
        val ox = if (hasTouch) (touch.x - w / 2f) / w * w * 0.03f else 0f
        val oy = if (hasTouch) (touch.y - h / 2f) / h * h * 0.03f else 0f

        blob(tint, 0.22f + 0.10f * a, 0.16f + 0.07f * b, w * 0.62f, Offset(ox, oy))
        blob(accent2, 0.78f - 0.09f * b, 0.30f + 0.11f * c, w * 0.50f, Offset(-ox, oy * 0.6f))
        blob(accent3, 0.30f + 0.12f * c, 0.86f - 0.08f * a, w * 0.70f, Offset(ox * 0.5f, -oy))
        blob(tint, 0.90f - 0.07f * c, 0.78f + 0.06f * a, w * 0.44f, Offset(-ox * 0.7f, -oy * 0.8f))

        // A drifting lattice of faint cards — the archive's shelves, abstracted.
        val cols = 4
        val rows = 7
        val cw = w / cols
        val ch = h / rows
        for (r in 0 until rows) {
            for (col in 0 until cols) {
                // Deterministic phase so the lattice breathes evenly.
                val phase = ((r * cols + col) % 7) / 7f
                val lift = sin((a * 2f * PI.toFloat() + phase * 2f * PI.toFloat())) * ch * 0.05f
                val alpha = (if (dark) 0.030f else 0.035f) * (0.6f + 0.4f * sin(phase * PI.toFloat()))
                val x = col * cw + cw * 0.28f + (if (col % 2 == 0) ox * 0.25f else 0f)
                val y = r * ch + ch * 0.26f + lift + (if (r % 2 == 0) oy * 0.25f else 0f)
                glyph(cardTint.copy(alpha = alpha), Offset(x, y), Size(cw * 0.44f, ch * 0.40f), phase)
            }
        }
    }
}

private fun DrawScope.blob(color: Color, cx: Float, cy: Float, radius: Float, shift: Offset) {
    val w = size.width
    val h = size.height
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(cx * w + shift.x, cy * h + shift.y),
            radius = radius,
        ),
        radius = radius,
        center = Offset(cx * w + shift.x, cy * h + shift.y),
    )
}

/** A small tilted card with a spine line — the archive motif reduced to a mark. */
private fun DrawScope.glyph(color: Color, at: Offset, size: Size, phase: Float) {
    translate(at.x, at.y) {
        rotate(-6f + 12f * sin(phase * PI.toFloat()), pivot = Offset(size.width / 2f, size.height / 2f)) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.width * 0.16f)
            drawRoundRect(color, topLeft = Offset.Zero, size = size, cornerRadius = r)
            drawRoundRect(
                color.copy(alpha = color.alpha * 2.2f),
                topLeft = Offset(size.width * 0.18f, size.height * 0.20f),
                size = Size(size.width * 0.52f, size.height * 0.09f),
                cornerRadius = r,
            )
        }
    }
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

private val PI = Math.PI
private fun sin(x: Float) = kotlin.math.sin(x)