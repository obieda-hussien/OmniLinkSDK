package com.omnilink.sdk;

import com.omnilink.sdk.IOmniEventCallback;
import com.omnilink.sdk.IOmniResultCallback;

interface IExtensionService {
    String executeAction(int protocolVersion, String requestJson);
    oneway void executeActionAsync(int protocolVersion, String requestJson, IOmniResultCallback callback);
    boolean registerEventListener(IOmniEventCallback callback);
    void unregisterEventListener(IOmniEventCallback callback);
}
