package com.picoxr.mrspacetowerdefense

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picoxr.mrspacetowerdefense.ui.MainActivity

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.picoxr.mrspacetowerdefense", appContext.packageName)
    }

    @Test
    fun mainActivityLaunches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val intent =
            Intent(instrumentation.targetContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        instrumentation.targetContext.startActivity(intent)
        val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000L)
        instrumentation.removeMonitor(monitor)

        assertNotNull("MainActivity was not created within the timeout", activity)
        assertTrue(activity is MainActivity)
        activity.finish()
    }
}
