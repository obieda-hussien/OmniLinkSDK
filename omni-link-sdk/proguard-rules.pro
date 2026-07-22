# Keep all @Serializable classes so kotlinx.serialization can generate serializers
-keep @kotlinx.serialization.Serializable class * { *; }

# Keep AIDL generated interfaces
-keep interface com.omnilink.sdk.IExtensionService { *; }
-keep interface com.omnilink.sdk.IOmniEventCallback { *; }
-keep interface com.omnilink.sdk.IOmniLauncherInterface { *; }
-keep interface com.omnilink.sdk.IOmniResultCallback { *; }

# Keep all public classes, methods, and fields in the SDK package
-keep public class com.omnilink.sdk.** {
    public *;
    protected *;
}
