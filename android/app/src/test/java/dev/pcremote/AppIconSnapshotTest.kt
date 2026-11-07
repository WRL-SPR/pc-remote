package dev.pcremote

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

    @androidx.compose.runtime.Composable
    private fun Icon(shape: Shape) {
        Box(Modifier.size(108.dp).clip(shape)) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.size(108.dp))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(108.dp))
        }
    }
}
