package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.p2p.*
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [FakeP2p::class, FakeChannel::class])
class WifiP2pGroupManagerTest {
    @Before fun reset() {
        FakeP2p.group = null
        FakeP2p.creates = 0
        FakeP2p.removes = 0
        FakeP2p.busy = 0
        FakeP2p.pending = null
        FakeP2p.deferInspection = false
        FakeP2p.defer = false
        FakeP2p.deferRemoval = false
        FakeP2p.pendingRemoval = null
        FakeP2p.race = false
        FakeChannel.closed = false
    }

    private fun manager() = WifiP2pGroupManager(RuntimeEnvironment.getApplication(), SSID, PASSWORD)
    private fun start(manager: WifiP2pGroupManager, timeout: Long = 3000): WirelessHotspotInfo {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit<WirelessHotspotInfo> { manager.start(timeout) }.get(5, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    @Test fun reusesAndRemovesMatchingOwner() {
        FakeP2p.group = group()
        val manager = manager()
        assertEquals(SSID, start(manager).ssid)
        assertEquals(0, FakeP2p.creates)
        manager.close()
        manager.close()
        assertEquals(1, FakeP2p.removes)
        assertTrue(FakeChannel.closed)
    }

    @Test fun replacesWrongCredentialsAndWrongBand() {
        for (old in listOf(group(password = "incorrect"), group(frequency = 2412), group(owner = false))) {
            FakeP2p.group = old
            val manager = manager()
            val before = FakeP2p.removes
            assertEquals(5180, start(manager).frequencyMHz)
            assertEquals(before + 1, FakeP2p.removes)
            manager.close()
        }
    }

    @Test fun leavesUnrelatedGroupUntouched() {
        val unrelated = group(ssid = "DIRECT-other")
        FakeP2p.group = unrelated
        try { start(manager()); fail("Expected conflict") }
        catch (failure: ExecutionException) { assertTrue(failure.cause!!.message!!.contains("conflict")) }
        assertSame(unrelated, FakeP2p.group)
        assertEquals(0, FakeP2p.removes)
        assertEquals(0, FakeP2p.creates)
    }

    @Test fun retriesTransientBusy() {
        FakeP2p.busy = 1
        val manager = manager()
        start(manager)
        assertEquals(2, FakeP2p.creates)
        manager.close()
    }

    @Test fun adoptsGroupCreatedDuringBusyRace() {
        FakeP2p.busy = 1
        FakeP2p.race = true
        val manager = manager()
        start(manager)
        assertEquals(1, FakeP2p.creates)
        manager.close()
    }

    @Test fun busyExpiresWithinStartupDeadline() {
        FakeP2p.busy = Int.MAX_VALUE
        val begin = System.nanoTime()
        try { start(manager(), 150); fail("Expected timeout") }
        catch (failure: ExecutionException) { assertTrue(failure.cause!!.message!!.contains("Timed out")) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1500)
        assertEquals(1, FakeP2p.creates)
    }

    @Test fun missingInspectionCallbackDoesNotCreateOrRemoveAnyGroup() {
        FakeP2p.deferInspection = true
        try { start(manager(), 100); fail("Expected inspection timeout") }
        catch (_: ExecutionException) { }
        assertEquals(0, FakeP2p.creates)
        assertEquals(0, FakeP2p.removes)
    }

    @Test fun cleanupLeavesReplacementByAnotherAppUntouched() {
        FakeP2p.group = group()
        val manager = manager()
        start(manager)
        val unrelated = group(ssid = "DIRECT-other")
        FakeP2p.group = unrelated
        manager.close()
        assertSame(unrelated, FakeP2p.group)
        assertEquals(0, FakeP2p.removes)
        assertTrue(FakeChannel.closed)
    }

    @Test fun timeoutStillRemovesLateCreation() {
        FakeP2p.defer = true
        val manager = manager()
        try { start(manager, 100); fail("Expected timeout") }
        catch (_: ExecutionException) { }
        assertFalse(FakeChannel.closed)
        FakeP2p.group = group()
        FakeP2p.pending!!.onSuccess()
        assertNull(FakeP2p.group)
        assertTrue(FakeChannel.closed)
        manager.close()
        assertEquals(1, FakeP2p.removes)
    }

    @Test fun backgroundCloseWaitsUntilRemovalIsConfirmed() {
        FakeP2p.group = group()
        val manager = manager()
        start(manager)
        FakeP2p.deferRemoval = true
        val executor = Executors.newSingleThreadExecutor()
        try {
            val closing = executor.submit { manager.close() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (FakeP2p.pendingRemoval == null && System.nanoTime() < deadline) Thread.yield()
            assertNotNull(FakeP2p.pendingRemoval)
            assertFalse(closing.isDone)
            assertFalse(FakeChannel.closed)
            FakeP2p.group = null
            FakeP2p.pendingRemoval!!.onSuccess()
            closing.get(2, TimeUnit.SECONDS)
            assertTrue(FakeChannel.closed)
            manager.close()
            assertEquals(1, FakeP2p.removes)
        } finally { executor.shutdownNow() }
    }

    @Test fun closesChannelOnlyAfterRemovalCompletes() {
        FakeP2p.group = group()
        val manager = manager()
        start(manager)
        FakeP2p.deferRemoval = true
        manager.close()
        manager.close()
        assertFalse(FakeChannel.closed)
        assertNotNull(FakeP2p.group)
        FakeP2p.group = null
        FakeP2p.pendingRemoval!!.onSuccess()
        assertTrue(FakeChannel.closed)
        assertEquals(1, FakeP2p.removes)
    }

    @Test fun cancellationKeepsChannelUntilLateSuccessIsRemoved() {
        FakeP2p.defer = true
        val manager = manager()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<WirelessHotspotInfo> { manager.start(3000) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (FakeP2p.pending == null && System.nanoTime() < deadline) Thread.yield()
            assertNotNull(FakeP2p.pending)
            manager.close()
            manager.close()
            assertFalse(FakeChannel.closed)
            FakeP2p.group = group()
            FakeP2p.pending!!.onSuccess()
            try { future.get(2, TimeUnit.SECONDS); fail("Expected cancellation") }
            catch (_: ExecutionException) { }
            assertNull(FakeP2p.group)
            assertEquals(1, FakeP2p.removes)
            assertTrue(FakeChannel.closed)
        } finally { executor.shutdownNow() }
    }

    companion object {
        const val SSID = "DIRECT-xcertplay1234"
        const val PASSWORD = "12345678"
        fun group(ssid: String = SSID, password: String = PASSWORD, frequency: Int = 5180,
                  owner: Boolean = true): WifiP2pGroup = WifiP2pGroup().also {
            ReflectionHelpers.setField(it, "mNetworkName", ssid)
            ReflectionHelpers.setField(it, "mPassphrase", password)
            ReflectionHelpers.setField(it, "mFrequency", frequency)
            ReflectionHelpers.setField(it, "mIsGroupOwner", owner)
            ReflectionHelpers.setField(it, "mInterface", "p2p-test-interface")
        }
    }
}

@Implements(WifiP2pManager::class)
class FakeP2p {
    @Implementation fun initialize(context: Context, looper: Looper,
        listener: WifiP2pManager.ChannelListener): WifiP2pManager.Channel =
        Shadow.newInstanceOf(WifiP2pManager.Channel::class.java).also { FakeChannel.closed = false }
    @Implementation fun requestGroupInfo(channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.GroupInfoListener) {
        if (!deferInspection) listener.onGroupInfoAvailable(group)
    }
    @Implementation fun requestConnectionInfo(channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ConnectionInfoListener) {
        listener.onConnectionInfoAvailable(WifiP2pInfo().also {
            it.groupFormed = true
            it.groupOwnerAddress = InetAddress.getByName("192.168.49.1")
        })
    }
    @Implementation fun createGroup(channel: WifiP2pManager.Channel, config: WifiP2pConfig,
        listener: WifiP2pManager.ActionListener) {
        creates++
        if (defer) { pending = listener; return }
        if (busy-- > 0) {
            if (race) group = WifiP2pGroupManagerTest.group()
            listener.onFailure(WifiP2pManager.BUSY)
        } else {
            group = WifiP2pGroupManagerTest.group()
            listener.onSuccess()
        }
    }
    @Implementation fun removeGroup(channel: WifiP2pManager.Channel,
        listener: WifiP2pManager.ActionListener) {
        check(!FakeChannel.closed)
        removes++
        if (deferRemoval) { pendingRemoval = listener; return }
        group = null
        listener.onSuccess()
    }
    companion object {
        @Volatile var group: WifiP2pGroup? = null
        @Volatile var pending: WifiP2pManager.ActionListener? = null
        var deferRemoval = false
        @Volatile var pendingRemoval: WifiP2pManager.ActionListener? = null
        var creates = 0
        var removes = 0
        var busy = 0
        var deferInspection = false
        var defer = false
        var race = false
    }
}

@Implements(WifiP2pManager.Channel::class)
class FakeChannel {
    @Implementation fun close() { closed = true }
    companion object { @Volatile var closed = false }
}
