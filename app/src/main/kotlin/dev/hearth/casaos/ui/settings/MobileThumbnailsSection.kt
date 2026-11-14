package dev.hearth.casaos.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.PhotoSizeSelectActual
import androidx.compose.material.icons.rounded.PhotoSizeSelectSmall
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import dev.hearth.casaos.data.PHOTO_HEAD_BYTES
import dev.hearth.casaos.data.THUMBNAIL_MAX_BYTES
import dev.hearth.casaos.data.ThumbnailDownload
import dev.hearth.casaos.ui.components.CardPadding
import dev.hearth.casaos.ui.components.HearthCard

/** How much of each photo Files' thumbnails and the gallery may download on mobile data. */
@Composable
fun MobileThumbnailsSection(value: ThumbnailDownload, onChange: (ThumbnailDownload) -> Unit) {
    val haptic = LocalHapticFeedback.current
    HearthCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Thumbnails on mobile data", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThumbnailDownload.entries.forEachIndexed { i, option ->
                    SegmentedButton(
                        selected = value == option,
                        onClick = {
                            if (value != option) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onChange(option)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, ThumbnailDownload.entries.size),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = value == option) {
                                Icon(
                                    when (option) {
                                        ThumbnailDownload.Off -> Icons.Rounded.HideImage
                                        ThumbnailDownload.Preview -> Icons.Rounded.PhotoSizeSelectSmall
                                        ThumbnailDownload.Whole -> Icons.Rounded.PhotoSizeSelectActual
                                    },
                                    null,
                                    Modifier.size(SegmentedButtonDefaults.IconSize),
                                )
                            }
                        },
                    ) { Text(option.label, maxLines = 1) }
                }
            }
            Text(
                when (value) {
                    ThumbnailDownload.Off -> "Photos keep their icons until you're on Wi-Fi. Thumbnails made before still show."
                    ThumbnailDownload.Preview ->
                        "Reads just the start of each JPEG, ${PHOTO_HEAD_BYTES / 1024} KB, for the small preview cameras save there. " +
                            "Other photos keep their icons until you're on Wi-Fi."
                    ThumbnailDownload.Whole ->
                        "Downloads the whole photo, up to ${THUMBNAIL_MAX_BYTES / (1024 * 1024)} MB, when it has no small preview " +
                            "or the preview is too blurry. This can use a lot of data."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
