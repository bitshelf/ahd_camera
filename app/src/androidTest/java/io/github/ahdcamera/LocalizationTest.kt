package io.github.ahdcamera

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import io.github.ahdcamera.camera.NativeBridge
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    @Test fun rustV4l2BridgeLoadsAndAcceptsAnUnknownClosedHandle() {
        assertEquals("stopped|0|0|0|-1|2", NativeBridge.status(0))
        NativeBridge.stop(0)
    }

    private fun resources(language: String): Resources {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(language))
        }
        return context.createConfigurationContext(configuration).resources
    }

    @Test fun englishQuantitiesAndFallback() {
        val english = resources("en")
        assertEquals("1 camera selected", english.getQuantityString(R.plurals.selected_cameras, 1, 1))
        assertEquals("2 cameras selected", english.getQuantityString(R.plurals.selected_cameras, 2, 2))
        assertEquals("Settings", resources("fr").getString(R.string.settings))
    }

    @Test fun chineseSelectionAndFormattedMessages() {
        val chinese = resources("zh-CN")
        assertEquals("\u8bbe\u7f6e", chinese.getString(R.string.settings))
        assertEquals("Opening 1920×1080", resources("en").getString(R.string.opening_resolution, 1920, 1080))
        assertEquals("\u6b63\u5728\u6253\u5f00 1920×1080", chinese.getString(R.string.opening_resolution, 1920, 1080))
        // Integer precision and placeholder types must remain compatible in every locale.
        chinese.getString(R.string.stream_metrics, 29.5f, 3L)
        chinese.getString(R.string.no_hardware_encoder, 1920, 1080, 30)
    }
}
