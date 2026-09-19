package com.omnilink.sdk

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Append-only Binder ABI guard for the Agent Gateway.
 *
 * v1.1 transaction ids 0..3 are already deployed and must never move.
 * v1.2 history/replay methods are appended at 4..6.
 */
class AgentGatewayBinderAbiTest {

    @Test
    fun `v1_1 transaction ids stay stable and v1_2 methods append`() {
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 0,
            IAgentGatewayService.Stub.TRANSACTION_getGatewayManifest
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 1,
            IAgentGatewayService.Stub.TRANSACTION_startAgentTask
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 2,
            IAgentGatewayService.Stub.TRANSACTION_cancelAgentTask
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 3,
            IAgentGatewayService.Stub.TRANSACTION_getTaskSnapshot
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 4,
            IAgentGatewayService.Stub.TRANSACTION_listAgentConversations
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 5,
            IAgentGatewayService.Stub.TRANSACTION_getAgentConversation
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 6,
            IAgentGatewayService.Stub.TRANSACTION_getTaskEvents
        )
    }
}
