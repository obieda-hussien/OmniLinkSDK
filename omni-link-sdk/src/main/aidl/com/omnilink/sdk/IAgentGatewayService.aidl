package com.omnilink.sdk;

import com.omnilink.sdk.IOmniAgentCallback;

interface IAgentGatewayService {
    String getGatewayManifest(int protocolVersion);
    oneway void startAgentTask(int protocolVersion, String requestJson, IOmniAgentCallback callback);
    void cancelAgentTask(String taskId);
    String getTaskSnapshot(int protocolVersion, String taskId);

    // v1.2+ append-only history/replay API. Never reorder existing methods.
    String listAgentConversations(int protocolVersion, String queryJson);
    String getAgentConversation(int protocolVersion, String conversationId, String queryJson);
    String getTaskEvents(int protocolVersion, String taskId, long afterSequence, int limit);
}
