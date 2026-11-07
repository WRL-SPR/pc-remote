package dev.pcremote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/** Previews the adaptive launcher icon under a circle mask. */
class AppIconSnapshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 600, softButtons = false))

    @Test
    fun launcherIcon() {
        paparazzi.snapshot {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(CircleShape)
            }
        }
    }

    /** The app-list icons for the Routines / Bixby actions (PC 켜기, 절전, 최대 절전, 재시작, 끄기). */
    @Test
    fun actionIcons() {
        paparazzi.snapshot {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CircleShape, R.drawable.ic_action_bg_green, R.drawable.ic_action_fg_power, 56)
                    Icon(CircleShape, R.drawable.ic_action_bg_indigo, R.drawable.ic_action_fg_moon, 56)
                    Icon(CircleShape, R.drawable.ic_action_bg_indigo, R.drawable.ic_action_fg_bed, 56)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(CircleShape, R.drawable.ic_launcher_background, R.drawable.ic_action_fg_restart, 56)
                    Icon(CircleShape, R.drawable.ic_action_bg_red, R.drawable.ic_action_fg_power, 56)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Icon(
        shape: Shape,
        background: Int = R.drawable.ic_launcher_background,
        foreground: Int = R.drawable.ic_launcher_foreground,
        sizeDp: Int = 108,
    ) {
        Box(Modifier.size(sizeDp.dp).clip(shape)) {
            Image(painterResource(background), null, Modifier.size(sizeDp.dp))
            Image(painterResource(foreground), null, Modifier.size(sizeDp.dp))
        }
    }
}
