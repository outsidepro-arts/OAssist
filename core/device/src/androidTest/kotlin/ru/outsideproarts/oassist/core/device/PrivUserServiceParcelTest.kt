package ru.outsideproarts.oassist.core.device

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivUserServiceParcelTest {
    @Test fun execPassesNormalizedParcelTimeoutToProcess() {
        val timeouts = mutableListOf<Long>()
        val service = PrivUserService { command, timeout, maxBytes ->
            assertEquals(listOf("/system/bin/getprop"), command)
            assertEquals(100, maxBytes)
            timeouts.add(timeout)
            ExecResult(0, "ok")
        }

        for (timeout in listOf(1L, 30_000L, 0L, Long.MAX_VALUE)) {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(PrivUserService.DESCRIPTOR)
                request.writeInt(1)
                request.writeString("getprop")
                request.writeLong(timeout)
                request.writeInt(100)
                request.setDataPosition(0)
                service.transact(PrivUserService.TRANSACTION_EXEC, request, reply, 0)
                reply.setDataPosition(0)
                assertEquals(0, reply.readInt())
                assertEquals("ok", reply.readString())
                assertEquals(0, reply.readInt())
            } finally {
                reply.recycle()
                request.recycle()
            }
        }
        assertEquals(listOf(1L, 30_000L, 1L, 30_000L), timeouts)
    }
}
