package io.github.timefreak7.fileforge.spike

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The same checks as the in-app button, for a computer run. Results also go to: adb logcat -d -s Phase0 */
@RunWith(AndroidJUnit4::class)
class Phase0ChecksTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val checks = Phase0Checks(context)
    private lateinit var service: IParserService

    @Before fun bind() {
        service = IParserService.Stub.asInterface(serviceRule.bindService(Intent(context, ParserService::class.java)))
    }

    /** Findings (ok == null) are logged, not asserted: they decide the plan. */
    private fun verify(r: Phase0Checks.Result) {
        Log.i("Phase0", "${r.name}: ${r.detail}")
        assertNotEquals("${r.name}: ${r.detail}", false, r.ok)
    }

    @Test fun serviceIsIsolated() = verify(checks.isolated(service))
    @Test fun isolatedProcessCannotReadAppFiles() = verify(checks.isolatedCannotReadAppFiles(service))
    @Test fun pdfPageRoundTripsThroughSharedMemory() = verify(checks.isolatedPdfRender(service))
    @Test fun hardwareEncodeMainProcess() = verify(checks.encodeMainProcess())
    @Test fun hardwareEncodeIsolatedProcess() = verify(checks.encodeIsolatedProcess(service))
    @Test fun mp3RoundTrip() = verify(checks.mp3RoundTrip())
    @Test fun platformPdfApis() = verify(checks.platformPdfApis())
    @Test fun pdfBoxDevanagari() = verify(checks.devanagari().result)
}
