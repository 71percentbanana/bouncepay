package com.bouncepay

import com.bouncepay.ble.BleIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The UUIDs are parsed when BleIds is first touched, which is the moment the
 * mesh starts. A typo there is a crash on launch, not a compile error.
 */
class BleIdsTest {

    @Test
    fun `service and characteristic ids parse and are distinct`() {
        val ids = listOf(BleIds.SERVICE, BleIds.CHAR_PACKET_IN, BleIds.CHAR_DEVICE_ID)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `ids are custom 128-bit, not in the Bluetooth SIG base range`() {
        // SIG-assigned ids share this suffix; a custom service must not.
        val sigBase = "-0000-1000-8000-00805f9b34fb"
        for (id in listOf(BleIds.SERVICE, BleIds.CHAR_PACKET_IN, BleIds.CHAR_DEVICE_ID)) {
            assertNotEquals(true, id.toString().endsWith(sigBase))
        }
    }
}
