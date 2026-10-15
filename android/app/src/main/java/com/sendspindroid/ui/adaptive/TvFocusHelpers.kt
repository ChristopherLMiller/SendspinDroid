package com.sendspindroid.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/** Horizontal TV safe-area margin (Android TV layout guidance: 48dp). */
val TvOverscanHorizontal = 48.dp

/** Vertical TV safe-area margin (Android TV layout guidance: 27dp). */
val TvOverscanVertical = 27.dp

/**
 * TV focus treatment for a control that is focusable on its own, such as a
 * Button, an IconButton or anything with Modifier.clickable:
 * - a ring in the theme's onSurface colour, drawn just outside the control
 *   in its own [shape], so it shows against a filled primary button too
 * - a scale of [focusScale] while focused
 *
 * It adds no focus target of its own. Placed before the control's modifiers,
 * onFocusChanged observes the control's focus target, so each control stays a
 * single stop for the D-pad and OK reaches its click handler at once.
 *
 * On other form factors it only attaches [focusRequester].
 *
 * @param focusRequester Optional FocusRequester for programmatic focus control
 * @param shape The control's shape, which the ring follows
 * @param focusScale Scale factor when focused (1.0 = no scale)
 */
fun Modifier.tvFocusRing(
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusScale: Float = 1.1f
): Modifier = composed {
    val withRequester = if (focusRequester != null) this.focusRequester(focusRequester) else this
    if (LocalFormFactor.current != FormFactor.TV) {
        return@composed withRequester
    }

    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1f,
        label = "tv_focus_scale"
    )
    val ringColor = MaterialTheme.colorScheme.onSurface

    withRequester
        .scale(scale)
        .drawWithContent {
            drawContent()
            if (isFocused) {
                val gap = RingGap.toPx()
                val width = RingWidth.toPx()
                val inset = gap + width / 2
                val outline = shape.createOutline(
                    Size(size.width + inset * 2, size.height + inset * 2),
                    layoutDirection,
                    this
                )
                translate(-inset, -inset) {
                    drawOutline(outline, ringColor, style = Stroke(width))
                }
            }
        }
        .onFocusChanged { isFocused = it.isFocused }
}

private val RingGap = 2.dp
private val RingWidth = 3.dp

/**
 * TV focus treatment for an element that is not otherwise focusable, such as
 * the album art card: it adds a focus target as well as the ring and scale of
 * [tvFocusRing]. Do not use it on something clickable, which already has a
 * focus target; that would make the element two D-pad stops. Use
 * [tvFocusRing] there.
 *
 * On other form factors it only adds focusable().
 */
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    focusScale: Float = 1.05f
): Modifier = this
    .tvFocusRing(focusRequester = focusRequester, shape = shape, focusScale = focusScale)
    .focusable()

/**
 * Requests focus on first composition for TV initial focus.
 * Use this to auto-focus the primary control (e.g., Play button) when a TV screen appears.
 *
 * @param focusRequester The FocusRequester to trigger on first composition
 */
@Composable
fun TvInitialFocus(focusRequester: FocusRequester) {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

/**
 * Applies the TV safe-area margins ([TvOverscanHorizontal] at the sides,
 * [TvOverscanVertical] at the top and bottom) on TV devices.
 * On non-TV devices, applies no padding.
 *
 * Use this on the outermost container of each screen to ensure
 * content is visible within the TV's safe display area.
 */
fun Modifier.overscanSafe(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(horizontal = TvOverscanHorizontal, vertical = TvOverscanVertical)
    } else {
        this
    }
}

/**
 * Applies overscan-safe horizontal padding only on TV devices (48dp left/right).
 * Useful when vertical overscan is handled by scroll containers.
 */
fun Modifier.overscanSafeHorizontal(): Modifier = composed {
    val formFactor = LocalFormFactor.current
    if (formFactor == FormFactor.TV) {
        this.padding(horizontal = TvOverscanHorizontal)
    } else {
        this
    }
}
