package com.omnilink.sdk;

import com.omnilink.sdk.IOmniEventCallback;

interface IExtensionService {
    String executeAction(int protocolVersion, String requestJson);
    boolean registerEventListener(IOmniEventCallback callback);
    void unregisterEventListener(IOmniEventCallback callback);
}
