# Rules shipped with the AAR to consuming apps

# Keep all @Serializable classes so kotlinx.serialization works across module boundaries
-keep @kotlinx.serialization.Serializable class com.omnilink.sdk.** { *; }

# Keep AIDL generated interfaces and their inner Stubs
-keep interface com.omnilink.sdk.IExtensionService { *; }
-keep class com.omnilink.sdk.IExtensionService$Stub { *; }
-keep interface com.omnilink.sdk.IOmniEventCallback { *; }
-keep class com.omnilink.sdk.IOmniEventCallback$Stub { *; }
-keep interface com.omnilink.sdk.IOmniLauncherInterface { *; }
-keep class com.omnilink.sdk.IOmniLauncherInterface$Stub { *; }
-keep interface com.omnilink.sdk.IOmniResultCallback { *; }
-keep class com.omnilink.sdk.IOmniResultCallback$Stub { *; }

# Keep all public API classes, methods, and fields in the SDK
-keep public class com.omnilink.sdk.** {
    public *;
    protected *;
}
