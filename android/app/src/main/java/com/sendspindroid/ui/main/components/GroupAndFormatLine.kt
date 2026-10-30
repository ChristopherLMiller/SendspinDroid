package com.sendspindroid.ui.main.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sendspindroid.R
import com.sendspindroid.model.codecName
import com.sendspindroid.model.kiloHertz
import com.sendspindroid.playback.PlaybackService
import com.sendspindroid.sendspin.protocol.StreamFormat
import com.sendspindroid.ui.theme.SendSpinTheme

/**
 * The group the player is in and the format of the stream it plays, on one
 * centred line: "Group: Kitchen", then "FLAC 24-bit / 48 kHz" in a pill.
 * Either can be missing; with neither there is no line. A long group name
 * gives way, so the format is always shown whole. One line rather than two,
 * so Now Playing needs no more height than it did with the group alone.
 */
@Composable
fun GroupAndFormatLine(
    groupName: String,
    streamFormat: StreamFormat?,
    groupStyle: TextStyle,
    formatStyle: TextStyle,
    modifier: Modifier = Modifier,
    bitPerfect: Boolean = PlaybackService.outputBitPerfect.collectAsStateWithLifecycle().value
) {
    if (groupName.isEmpty() && streamFormat == null) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (groupName.isNotEmpty()) {
            Text(
                text = stringResource(R.string.group_label, groupName),
                style = groupStyle,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (streamFormat != null) {
            if (groupName.isNotEmpty()) Spacer(modifier = Modifier.width(8.dp))
            StreamFormatBadge(format = streamFormat, style = formatStyle, bitPerfect = bitPerfect)
        }
    }
}

/**
 * "FLAC 24-bit / 48 kHz" in an outlined pill, and ", bit-perfect" after it
 * while the output passes the audio to a USB DAC untouched.
 */
@Composable
fun StreamFormatBadge(
    format: StreamFormat,
    style: TextStyle,
    modifier: Modifier = Modifier,
    bitPerfect: Boolean = false
) {
    val formatLabel = stringResource(
        R.string.stream_format_label,
        codecName(format.codec),
        format.bitDepth,
        kiloHertz(format.sampleRate)
    )
    val label = if (bitPerfect) stringResource(R.string.stream_format_bit_perfect, formatLabel) else formatLabel
    val description = stringResource(R.string.stream_format_description, label)
    Text(
        text = label,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics { contentDescription = description }
    )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun GroupAndFormatLinePreview() {
    SendSpinTheme {
        GroupAndFormatLine(
            groupName = "Living Room",
            streamFormat = StreamFormat("flac", 96_000, 2, 24),
            groupStyle = MaterialTheme.typography.labelMedium,
            formatStyle = MaterialTheme.typography.labelSmall,
            bitPerfect = true
        )
    }
}
