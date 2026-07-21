# OmniLinkSDK Architecture Evolution

This document outlines technical proposals to evolve the SDK for higher performance, lower latency, and modern Android (15/16/17) compliance.

## 1. Zero-Copy & Binary Serialization (Performance & Latency)
Currently, `ActionRequest` and `OmniEvent` use JSON strings via `kotlinx.serialization`. For high-throughput or large data (like images or ML tensors):
- **Protobuf or FlatBuffers:** Migrate the wire format from JSON strings to binary. FlatBuffers allows zero-copy reads, eliminating parsing overhead entirely.
- **SharedMemory (Ashmem) for Large Payloads:** For payloads exceeding 1MB (the Binder transaction limit), the SDK should automatically transparently negotiate a `ParcelFileDescriptor` wrapping `SharedMemory` or `MemoryFile`. This prevents `TransactionTooLargeException` and achieves zero-copy large data transfer.

## 2. Asynchronous IPC (Non-blocking)
- **`oneway` AIDL Methods:** Currently, `executeAction` is a synchronous AIDL method blocking the caller's thread until `ActionOutcome` returns. For heavy processing, this is suboptimal.
- **Callback-based Execution:** Change the AIDL signature to `oneway void executeAction(..., IOmniResultCallback callback)`. This frees the binder thread pool immediately, allowing the extension to process the request on its own time and stream the result back asynchronously.

## 3. Advanced Event Bus (Backpressure)
- **Flow Control:** The current `IOmniEventCallback` pushes events. If the producer is faster than the consumer, the binder buffer fills up.
- **Reactive Streams over IPC:** Implement true backpressure (like RxJava/Flow `request(n)`) across the AIDL boundary so the consumer dictates the event rate.

## 4. Modern Android Constraints (Android 15+)
- **Strict Background Restrictions:** Android 15 heavily restricts background service starts and foreground service (FGS) durations (e.g., the 6-hour limit on `dataSync`).
- **WorkManager Integration:** As noted in the heartbeat phase, indefinite background services are no longer viable. The SDK should provide native `WorkManager` hooks for periodic tasks (`_tick`) rather than assuming long-lived binds.
- **Security & Signature Verification:** Relying solely on `Binder.getCallingUid()` and package name checks is vulnerable to package spoofing on rooted devices. The SDK should implement cryptographic signature verification (checking the APK's signing certificate) to ensure only authorized Workspace/Satellite apps can connect.
