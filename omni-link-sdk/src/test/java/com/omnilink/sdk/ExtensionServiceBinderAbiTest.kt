package com.omnilink.sdk

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Append-only Binder ABI guard.
 *
 * AIDL assigns transaction codes by declaration order. These assertions protect already-installed
 * protocol-v1/v2 extension services from accidental method reordering in future SDK revisions.
 */
class ExtensionServiceBinderAbiTest {

    @Test
    fun `legacy transaction ids remain stable and manifest is append-only`() {
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 0,
            IExtensionService.Stub.TRANSACTION_executeAction
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 1,
            IExtensionService.Stub.TRANSACTION_executeActionAsync
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 2,
            IExtensionService.Stub.TRANSACTION_registerEventListener
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 3,
            IExtensionService.Stub.TRANSACTION_unregisterEventListener
        )
        assertEquals(
            IBinder.FIRST_CALL_TRANSACTION + 4,
            IExtensionService.Stub.TRANSACTION_getCapabilityManifest
        )
    }
}
