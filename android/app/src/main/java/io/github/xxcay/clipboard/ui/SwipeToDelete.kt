@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.xxcay.clipboard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Swipe a card left or right to delete it: a red panel with a trash can
 * shows up underneath, its lid opens as you drag, and past the threshold
 * the card flies away. [onDelete] returns false if deleting failed, then
 * the card slides back.
 */
@Composable
fun SwipeToDelete(onDelete: suspend () -> Boolean, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.33f })

    // A tick when the threshold is crossed, like a "click" of the lid.
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled && !onDelete()) state.reset()
    }

    SwipeToDismissBox(
        state = state,
        backgroundContent = { DeleteBackground(state.dismissDirection, state.progress, state.targetValue != SwipeToDismissBoxValue.Settled) },
        content = { content() },
    )
}

@Composable
private fun DeleteBackground(direction: SwipeToDismissBoxValue, progress: Float, armed: Boolean) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val fromStart = direction == SwipeToDismissBoxValue.StartToEnd
    // How far the lid is open: follows the finger, snaps wide open once armed.
    val open by animateFloatAsState(
        targetValue = if (armed) 1f else (progress / 0.33f).coerceIn(0f, 0.6f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "lid",
    )
    val pop by animateFloatAsState(
        targetValue = if (armed) 1.18f else 0.85f + 0.15f * (progress / 0.33f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pop",
    )
    val red = lerp(Color(0xFFFF8A80), Palette.Danger, (progress / 0.33f).coerceIn(0f, 1f))
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(red)
            .padding(horizontal = 24.dp),
        contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!fromStart) Text("Удалить", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            TrashCan(open, Modifier.size(30.dp).scale(pop))
            if (fromStart) Text("Удалить", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/** A trash can whose lid swings open (0 = closed, 1 = wide open). */
@Composable
private fun TrashCan(open: Float, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val white = Color.White
        val stroke = w * 0.08f
        // Body.
        val top = h * 0.34f
        drawRoundRect(white, topLeft = Offset(w * 0.2f, top), size = Size(w * 0.6f, h * 0.6f), cornerRadius = CornerRadius(w * 0.08f))
        // Ribs.
        for (x in listOf(0.38f, 0.5f, 0.62f)) {
            drawLine(red(), Offset(w * x, top + h * 0.12f), Offset(w * x, h * 0.84f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        }
        // Lid, hinged at its left end.
        rotate(degrees = -55f * open, pivot = Offset(w * 0.14f, h * 0.27f)) {
            drawRoundRect(white, topLeft = Offset(w * 0.12f, h * 0.2f), size = Size(w * 0.76f, h * 0.1f), cornerRadius = CornerRadius(w * 0.04f))
            drawRoundRect(white, topLeft = Offset(w * 0.4f, h * 0.1f), size = Size(w * 0.2f, h * 0.12f), cornerRadius = CornerRadius(w * 0.04f))
        }
    }
}

private fun red() = Palette.Danger
