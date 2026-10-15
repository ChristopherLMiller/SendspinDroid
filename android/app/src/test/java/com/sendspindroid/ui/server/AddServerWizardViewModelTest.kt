package com.sendspindroid.ui.server

import com.sendspindroid.ui.wizard.ConnectionTestState
import com.sendspindroid.ui.wizard.DiscoveredServerUi
import com.sendspindroid.ui.wizard.WizardStep
import com.sendspindroid.ui.wizard.WizardStepAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A failed connection test returns to the address with its error, which lasts until the address changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddServerWizardViewModelTest {

    private fun failedTest(): AddServerWizardViewModel = AddServerWizardViewModel().apply {
        localAddress = "192.168.1.100:8927"
        navigateTo(WizardStep.SS_TestLocal)
        onLocalTestFailed("Connection timed out")
    }

    @Test
    fun `a failed test returns to the address with its error`() {
        val viewModel = failedTest()

        assertEquals(WizardStep.SS_FindServer, viewModel.currentStep.value)
        assertEquals(ConnectionTestState.Failed("Connection timed out"), viewModel.localTestState.value)
        assertEquals("192.168.1.100:8927", viewModel.localAddress)
    }

    @Test
    fun `editing the address clears the error`() {
        val viewModel = failedTest()

        viewModel.handleStepAction(WizardStepAction.UpdateLocalAddress("192.168.1.10:8927"))

        assertEquals(ConnectionTestState.Idle, viewModel.localTestState.value)
    }

    @Test
    fun `picking a discovered server clears the error`() {
        val viewModel = failedTest()

        viewModel.handleStepAction(
            WizardStepAction.SelectDiscoveredServer(DiscoveredServerUi("1", "Office", "192.168.1.101:8927"))
        )

        assertEquals(ConnectionTestState.Idle, viewModel.localTestState.value)
    }

    @Test
    fun `Back from the testing step returns to the address and keeps it`() {
        val viewModel = AddServerWizardViewModel().apply {
            localAddress = "192.168.1.100:8927"
            navigateTo(WizardStep.SS_TestLocal)
        }

        assertEquals(WizardStep.SS_FindServer, viewModel.onBack())
        assertEquals("192.168.1.100:8927", viewModel.localAddress)
    }
}
