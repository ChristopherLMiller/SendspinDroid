package com.sendspindroid.coordinator

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import java.net.InetAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's network callback, driven as ConnectivityManager would:
 * which networks it follows and which link changes it reports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionCoordinatorNetworkCallbackTest {

    private val callback = slot<ConnectivityManager.NetworkCallback>()
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true) {
        every { registerDefaultNetworkCallback(capture(callback)) } just runs
    }
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
    }

    private fun network(handle: Long): Network = mockk(relaxed = true) {
        every { networkHandle } returns handle
    }

    /** A fresh LinkAddress object each call, as each callback brings new ones. */
    private fun address(ip: String, prefix: Int = 64): LinkAddress = mockk {
        every { address } returns InetAddress.getByName(ip)
        every { prefixLength } returns prefix
    }

    private fun properties(vararg addresses: LinkAddress): LinkProperties = mockk {
        every { linkAddresses } returns addresses.toList()
    }

    private fun TestScope.coordinatorEvents(): MutableList<NetworkEvent> {
        val coordinator = ConnectionCoordinator(
            currentServerFlow = MutableStateFlow(null),
            sendSpinStateFlow = MutableStateFlow(TransportState.Idle),
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            connectAttempt = { _, _ -> false },
            context = context,
        )
        val events = mutableListOf<NetworkEvent>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            coordinator.networkEvents.collect { events.add(it) }
        }
        testScheduler.runCurrent()
        return events
    }

    @Test
    fun `follows the default network only`() = runTest {
        coordinatorEvents()

        verify { connectivityManager.registerDefaultNetworkCallback(any()) }
        verify(exactly = 0) {
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `a refresh of the same addresses is not an address change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        callback.captured.onAvailable(wifi)
        callback.captured.onLinkPropertiesChanged(wifi, properties(address("2001:db8::1"), address("10.0.1.6", 22)))

        // The same addresses again, as new objects: a router advertisement
        // refreshes lifetimes, which LinkAddress equality would count.
        callback.captured.onLinkPropertiesChanged(wifi, properties(address("2001:db8::1"), address("10.0.1.6", 22)))
        testScheduler.runCurrent()

        assertTrue("no event for a refresh: $events", events.isEmpty())
    }

    @Test
    fun `an added address is an address change`() = runTest {
        val events = coordinatorEvents()
        val wifi = network(1L)
        callback.captured.onAvailable(wifi)
        callback.captured.onLinkPropertiesChanged(wifi, properties(address("2001:db8::1")))

        callback.captured.onLinkPropertiesChanged(wifi, properties(address("2001:db8::1"), address("2001:db8::2")))
        testScheduler.runCurrent()

        assertEquals(1, events.size)
        assertTrue(events.single() is NetworkEvent.LinkAddressesChanged)
    }

    @Test
    fun `a different default network is an identity change`() = runTest {
        val events = coordinatorEvents()
        callback.captured.onAvailable(network(1L))

        callback.captured.onAvailable(network(2L))
        testScheduler.runCurrent()

        assertEquals(listOf<NetworkEvent>(NetworkEvent.IdentityChanged(2L)), events)
    }
}
