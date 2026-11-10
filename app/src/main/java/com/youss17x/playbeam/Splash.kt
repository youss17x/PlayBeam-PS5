package com.youss17x.playbeam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Light opening animation colors (same in light and dark theme, as chosen)
private val SplashBg = Color(0xFFF3F4F7)
private val SplashTile = Color(0xFF0B1E3F)
private val SplashInk = Color(0xFF0E1424)
private val SplashAccent = Color(0xFF2F5BEA)
private val SplashMuted = Color(0xFF5B6475)
private val SplashFaint = Color(0xFF8A92A3)
private val BeamBlue = Color(0xFF4FC3F7)
private val BeamLight = Color(0xFF81D4FA)

/**
 * "PlayBeam — Has No Limits" opening animation (~2.6 s), then [onFinished].
 * Icon zooms in, beam lines slide in, the play triangle nudges forward,
 * the name rises, a line stretches under it and the tagline spreads out.
 */
@Composable
fun Splash(onFinished: () -> Unit) {
    val logo = remember { Animatable(0f) }      // 0 → 1 (scale + fade)
    val beam = remember { Animatable(0f) }      // beam lines slide in
    val shot = remember { Animatable(0f) }      // triangle nudge
    val title = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    val tag = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }       // whole screen fade-out

    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
        launch { delay(250); beam.animateTo(1f, tween(500, easing = LinearOutSlowInEasing)) }
        launch {
            delay(650)
            shot.animateTo(1f, tween(180))
            shot.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        }
        launch { delay(700); title.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
        launch { delay(1000); line.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        launch { delay(1150); tag.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
        delay(2350)
        out.animateTo(0f, tween(320))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .alpha(out.value)
            .background(SplashBg)
            // block taps on the app underneath while the animation plays
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // ---- icon ----
            val s = 0.6f + 0.4f * logo.value + 0.06f * kotlin.math.sin(Math.PI * logo.value).toFloat()
            Box(
                Modifier
                    .size(124.dp)
                    .graphicsLayer { scaleX = s; scaleY = s; alpha = logo.value }
                    .clip(RoundedCornerShape(34.dp))
                    .background(SplashTile),
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(96.dp)) {
                    // the icon's 108-unit design, inner 18..90 area mapped onto this canvas
                    val k = size.width / 72f
                    fun x(v: Float) = (v - 18f) * k
                    fun y(v: Float) = (v - 18f) * k
                    val slide = (1f - beam.value) * -16f * k / 3f

                    fun bar(left: Float, top: Float, right: Float, color: Color) = drawRoundRect(
                        color = color.copy(alpha = beam.value),
                        topLeft = Offset(x(left) + slide, y(top)),
                        size = Size((right - left) * k, 5f * k),
                        cornerRadius = CornerRadius(2.5f * k),
                    )
                    bar(30.5f, 42.5f, 51.5f, BeamBlue)
                    bar(25.5f, 51.5f, 51.5f, BeamLight)
                    bar(30.5f, 60.5f, 51.5f, BeamBlue)

                    val push = shot.value * 5f * k / 3f
                    val tri = Path().apply {
                        moveTo(x(55.5f) + push, y(35.5f))
                        lineTo(x(81.5f) + push, y(54f))
                        lineTo(x(55.5f) + push, y(72.5f))
                        close()
                    }
                    val round = PathEffect.cornerPathEffect(3f * k)
                    drawPath(tri, Color.White, style = Fill)
                    drawPath(tri, Color.White, style = Stroke(width = 2f * k, pathEffect = round))
                }
            }

            Spacer(Modifier.height(26.dp))

            // ---- name ----
            Text(
                "PlayBeam",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.03).em,
                color = SplashInk,
                modifier = Modifier.graphicsLayer {
                    alpha = title.value
                    translationY = (1f - title.value) * 12.dp.toPx()
                },
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .width(140.dp * line.value)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SplashAccent)
            )
            Spacer(Modifier.height(12.dp))
            // ---- tagline ----
            Text(
                "HAS NO LIMITS",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (0.08f + 0.34f * tag.value).em,
                color = SplashMuted,
                modifier = Modifier.alpha(tag.value),
            )
        }
        Text(
            "PS5",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.3.em,
            color = SplashFaint,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp).navigationBarsPadding(),
        )
    }
}
