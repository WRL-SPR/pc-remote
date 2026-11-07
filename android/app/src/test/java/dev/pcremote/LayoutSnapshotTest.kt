package dev.pcremote

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Renders the main screen at the window sizes of bar phones and Galaxy Fold cover / inner screens.
 * Record with `gradle recordPaparazziDebug`; images land in app/src/test/snapshots/images.
 */
@RunWith(Parameterized::class)
class LayoutSnapshotTest(private val screen: Screen) {

    data class Screen(val name: String, val widthDp: Int, val heightDp: Int) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun screens() = listOf(
            Screen("cover_300dp", 300, 780),     // Fold 1~4 cover with larger display size
            Screen("cover_344dp", 344, 882),     // Fold 3~5 cover
            Screen("bar_384dp", 384, 832),       // S2x bar phones
            Screen("cover_411dp", 411, 900),     // Fold 7~8 cover (wider)
            Screen("bar_landscape", 832, 384),
            // Fold main (inner) screens at default display size, px / 2.625
            Screen("inner_fold1", 586, 701),         // Fold 1: 1536x1840
            Screen("inner_fold2_4", 673, 841),       // Fold 2~4: 1768x2208
            Screen("inner_fold2_4_land", 841, 673),
            Screen("inner_fold5_6", 690, 829),       // Fold 5~6: 1812x2176
            Screen("inner_fold5_6_land", 829, 690),
            Screen("inner_fold7_8", 750, 832),       // Fold 7~8: 1968x2184
            Screen("inner_fold7_8_land", 832, 750),
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = screen.widthDp * 3,
            screenHeight = screen.heightDp * 3,
            xdpi = 480,
            ydpi = 480,
            density = Density.XXHIGH,
            orientation = if (screen.widthDp > screen.heightDp) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            softButtons = false,
        ),
        theme = "android:Theme.Material.Light.NoActionBar",
    )

    private val gaming = Pc(id = "1", name = "게이밍 PC", host = "192.168.0.10", mac = "1C:69:7A:12:34:56", token = "t")
    private val living = Pc(id = "2", name = "거실 미디어 PC (아주 긴 이름 테스트용)", host = "192.168.0.11", mac = "1C:69:7A:12:34:57", token = "t")
    private val study = Pc(id = "3", name = "서재 PC", host = "192.168.0.12", mac = "1C:69:7A:12:34:58", token = "t")
    private val office = Pc(id = "4", name = "작업용", host = "192.168.0.13", mac = "1C:69:7A:12:34:59", token = "t")

    @Test
    fun fourPcs() = render(
        pcs = listOf(gaming, living, study, office),
        states = mapOf(
            "1" to PcState(agent = true, stream = true),
            "2" to PcState(agent = false, stream = false),
            "3" to PcState(agent = true, stream = false),
            "4" to PcState(agent = false, stream = false),
        ),
        busy = mapOf("2" to "켜는 중… 24초"),
    )

    @Test
    fun fourPcsDark() = render(
        pcs = listOf(gaming, living, study, office),
        states = mapOf(
            "1" to PcState(agent = true, stream = true),
            "2" to PcState(agent = false, stream = false),
            "3" to PcState(agent = true, stream = false),
            "4" to PcState(agent = false, stream = false),
        ),
        busy = mapOf("2" to "켜는 중… 24초"),
        dark = true,
    )

    @Test
    fun singlePc() = render(listOf(gaming), mapOf("1" to PcState(agent = true, stream = true)))

    @Test
    fun empty() = render(emptyList(), emptyMap())

    private fun render(
        pcs: List<Pc>,
        states: Map<String, PcState>,
        busy: Map<String, String> = emptyMap(),
        dark: Boolean = false,
    ) {
        paparazzi.snapshot {
            PcRemoteTheme(dark = dark) {
                PcRemoteScreen(
                    pcs = pcs,
                    states = states,
                    busy = busy,
                    snackbar = remember { SnackbarHostState() },
                    onAdd = {},
                    onWake = {},
                    onStream = {},
                    onPower = { _, _ -> },
                    onEdit = {},
                )
            }
        }
    }
}
