package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStateTest {

    private fun fullState() = LiveDeviceState(
        torchOn = false,
        musicActive = true,
        wifiOn = true,
        batteryPct = 78,
        charging = true,
        screenOn = true,
        ringer = RingerMode.NORMAL,
    )

    @Test
    fun format_compactLine() {
        assertEquals(
            "torch=off; music=true; wifi=true; battery=78%+; screen=true; ringer=normal",
            formatDeviceState(fullState()),
        )
    }

    @Test
    fun format_unknowns() {
        val state = fullState().copy(torchOn = null, batteryPct = null, charging = null)
        assertEquals(
            "torch=unknown; music=true; wifi=true; battery=unknown; screen=true; ringer=normal",
            formatDeviceState(state),
        )
    }

    @Test
    fun torchTracker_lastOnWinsAndUnknownInitially() {
        val tracker = TorchTracker()
        assertNull(tracker.snapshot())
        tracker.onTorchChanged("0", true)
        assertEquals(true, tracker.snapshot())
        tracker.onTorchChanged("0", false)
        assertEquals(false, tracker.snapshot())
    }

    @Test
    fun torchTracker_anyBurning() {
        val tracker = TorchTracker()
        tracker.onTorchChanged("0", false)
        tracker.onTorchChanged("1", true)
        assertEquals(true, tracker.snapshot())
    }
}
