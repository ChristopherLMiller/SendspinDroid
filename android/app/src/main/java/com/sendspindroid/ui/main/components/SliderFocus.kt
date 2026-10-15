package com.sendspindroid.ui.main.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Up and Down move focus off a horizontal material3 Slider, which from
 * material3 1.4 steps its value on all four arrow keys: without this, a
 * keyboard or D-pad that lands on the slider can leave it only with Tab.
 * Left and Right still step it. Add it to the Slider's modifier.
 */
internal fun Modifier.upDownMovesFocus(focusManager: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> return@onPreviewKeyEvent false
        }
        if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
        true
    }
