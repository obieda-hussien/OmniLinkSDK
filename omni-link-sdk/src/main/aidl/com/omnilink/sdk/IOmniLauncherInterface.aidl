package com.omnilink.sdk;

interface IOmniLauncherInterface {
    boolean performLauncherAction(String actionName);
    boolean renderOmniWidget(String widgetId, String composeJson);
    boolean removeOmniWidget(String widgetId);
    void clearAllOmniWidgets();
    boolean openWidgetPicker();
}
