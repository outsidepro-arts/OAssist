package ru.outsideproarts.oassist.update

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UpdateInstallationTest {
    @Test fun activityRecreationDuringVerificationRetainsRequestForNewHost() = runTest {
        var pending = false
        var launches = 0
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val oldHost = async {
            handOffUpdate({ entered.complete(Unit); finish.await(); PreparedUpdate(File("update.apk"), 1) },
                { true }, { launches++ }, { pending = true }, { fail("Unexpected failure") })
        }
        entered.await()
        oldHost.cancel()
        oldHost.join()
        assertTrue(pending)
        assertEquals(0, launches)
        handOffUpdate({ PreparedUpdate(File("update.apk"), 1) }, { pending }, { pending = false; launches++ }, {}, { fail() })
        assertFalse(pending)
        assertEquals(1, launches)
    }

    @Test fun settingsChangeDuringVerificationPreventsInstallerHandoff() = runTest {
        var generation = 1L
        var launches = 0
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val request = async {
            handOffUpdate({ val token = generation; entered.complete(Unit); finish.await(); PreparedUpdate(File("old.apk"), token) },
                { it.generation == generation }, { launches++ }, {}, { fail() })
        }
        entered.await()
        generation++
        finish.complete(Unit)
        request.await()
        assertEquals(0, launches)
    }
}
