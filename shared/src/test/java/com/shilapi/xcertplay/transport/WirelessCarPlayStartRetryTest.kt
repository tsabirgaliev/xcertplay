package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.transport.WirelessCarPlayStartRetry.Action.*

class WirelessCarPlayStartRetryTest {
    @Test fun doesNotRetryBeforeStartOrAfterConnection() {
        val retry = WirelessCarPlayStartRetry()
        assertEquals(NONE, retry.poll(100_000, false))
        retry.started(100_000)
        assertEquals(NONE, retry.poll(104_999, false))
        assertEquals(NONE, retry.poll(105_000, true))
        assertEquals(NONE, retry.poll(200_000, false))
        assertFalse(retry.pending)
    }

    @Test fun retriesThreeTimesThenExpiresWithoutResettingWifi() {
        val retry = WirelessCarPlayStartRetry()
        retry.started(0)
        assertEquals(RESEND, retry.poll(5_000, false))
        assertEquals(NONE, retry.poll(5_001, false))
        assertEquals(RESEND, retry.poll(10_000, false))
        assertEquals(RESEND, retry.poll(15_000, false))
        assertEquals(NONE, retry.poll(20_000, false))
        assertEquals(NONE, retry.poll(29_999, false))
        assertEquals(EXPIRED, retry.poll(30_000, false))
        assertEquals(3, retry.retries)
    }

    @Test fun repeatedAvailabilityDoesNotExtendDeadline() {
        val retry = WirelessCarPlayStartRetry()
        retry.started(0)
        retry.started(29_000)
        assertEquals(EXPIRED, retry.poll(30_000, false))
    }

    @Test fun connectionAtDeadlineWinsOverTimeout() {
        val retry = WirelessCarPlayStartRetry()
        retry.started(0)
        assertEquals(NONE, retry.poll(30_000, true))
        assertFalse(retry.pending)
    }
}
