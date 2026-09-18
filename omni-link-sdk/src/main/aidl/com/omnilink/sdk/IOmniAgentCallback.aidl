package com.omnilink.sdk;

oneway interface IOmniAgentCallback {
    void onEvent(String eventJson);
}
