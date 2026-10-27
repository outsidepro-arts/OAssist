package ru.outsideproarts.oassist.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.net.NetworkHandle
import ru.outsideproarts.oassist.core.net.NetworkMonitor
import java.net.URL
import javax.net.ssl.HttpsURLConnection

@RunWith(AndroidJUnit4::class)
class NetworkMonitorInstrumentedTest {
    @Test
    fun targetAppUidCanReachHttpsAndObserveDefaultNetwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Enable with -e verifyInternet true", InstrumentationRegistry.getArguments().getString("verifyInternet") == "true")
        val context = instrumentation.targetContext
        val appUid = context.packageManager.getApplicationInfo(context.packageName, 0).uid
        assertEquals("Instrumentation must run under the target app UID", appUid, Process.myUid())
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork ?: error("Device has no default network")
        val capabilities = connectivity.getNetworkCapabilities(active) ?: error("Default network capabilities unavailable")
        assertTrue("Default network does not advertise internet", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        val monitor = NetworkMonitor(context)
        monitor.start()
        try {
            withTimeout(10_000) { monitor.isOnline.first { it } }
            val graphMonitor = NetworkHandle.get()
            assertNotNull("Application network handle missing", graphMonitor)
            withTimeout(10_000) { graphMonitor!!.isOnline.first { it } }
            val request = URL("https://example.com/").openConnection() as HttpsURLConnection
            try {
                request.requestMethod = "HEAD"
                request.connectTimeout = 10_000
                request.readTimeout = 10_000
                val code = request.responseCode
                assertTrue("HTTPS response $code", code in 200..399)
                assertTrue("New monitor went offline despite successful HTTPS", monitor.isOnline.value)
                assertTrue("Application monitor went offline despite successful HTTPS", graphMonitor!!.isOnline.value)
            } finally {
                request.disconnect()
            }
        } finally {
            monitor.stop()
        }
    }
}
