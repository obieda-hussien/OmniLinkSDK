package com.omnilink.sdk;

import com.omnilink.sdk.IOmniEventCallback;
import com.omnilink.sdk.IOmniResultCallback;

interface IExtensionService {
    // IMPORTANT: existing methods stay in their original order forever.
    // AIDL transaction IDs are positional; inserting above them breaks already-installed services.
    String executeAction(int protocolVersion, String requestJson);
    oneway void executeActionAsync(int protocolVersion, String requestJson, IOmniResultCallback callback);
    boolean registerEventListener(IOmniEventCallback callback);
    void unregisterEventListener(IOmniEventCallback callback);

    // Added in protocol v3. Append-only to preserve Binder ABI compatibility.
    String getCapabilityManifest();
}
