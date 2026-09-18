package com.omnilink.sdk;

import com.omnilink.sdk.IOmniAgentCallback;

interface IAgentGatewayService {
    String getGatewayManifest(int protocolVersion);
    oneway void startAgentTask(int protocolVersion, String requestJson, IOmniAgentCallback callback);
    void cancelAgentTask(String taskId);
    String getTaskSnapshot(int protocolVersion, String taskId);
}
