package com.omnilink.sdk

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.omnilink.sdk.trusted.TrustedServicePolicy
import com.omnilink.sdk.trusted.TrustedServiceResolver
import com.omnilink.sdk.trusted.VerifiedService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CapabilityDiscoveryResult(
    val graph: CapabilityGraphSnapshot,
    val failures: Map<String, String>
)

/**
 * Discovers exported, policy-verified extension services and reads bounded Binder manifests.
 * The verified component and signer are checked again after the Binder reply. No manifest field
 * can choose its own package, trust tier or authority. Binding is explicitly scoped and released.
 */
class TrustedCapabilityDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = TrustedServiceResolver(appContext)

    suspend fun discover(policy: TrustedServicePolicy, timeoutMillis: Long = 5_000): CapabilityDiscoveryResult {
        require(timeoutMillis in 1..30_000)
        val query = withContext(Dispatchers.IO) { resolver.query(policy) }
        val failures = linkedMapOf<String, String>()
        query.rejected.forEach { failures["${it.packageName}/${it.serviceClassName}"] = it.reason }
        val nodes = mutableListOf<CapabilityGraphNode>()
        for (service in query.verified.distinctBy { it.component }.take(MAX_SERVICES)) {
            val key = "${service.packageName}/${service.serviceClassName}"
            val json = readManifest(service, policy.action, timeoutMillis)
            if (json == null) {
                failures[key] = "manifest_unavailable"
                continue
            }
            val current = withContext(Dispatchers.IO) {
                resolver.query(policy).verified.firstOrNull { it.component == service.component }
            }
            if (current == null || current.signerSha256 != service.signerSha256 ||
                current.sameSignerAsHost != service.sameSignerAsHost
            ) {
                failures[key] = "provider_identity_changed"
                continue
            }
            when (val result = TrustedCapabilityCatalog.ingest(current, json)) {
                is CapabilityCatalogResult.Accepted -> nodes += result.nodes
                is CapabilityCatalogResult.Rejected -> failures[key] = result.reason
            }
        }
        if (query.verified.size > MAX_SERVICES) failures["_discovery"] = "service_limit_exceeded"
        return CapabilityDiscoveryResult(
            CapabilityGraphSnapshot(System.currentTimeMillis(), nodes, emptyList()), failures
        )
    }

    private suspend fun readManifest(service: VerifiedService, action: String, timeoutMillis: Long): String? {
        val connected = CompletableDeferred<IExtensionService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (name != service.component) connected.complete(null)
                else connected.complete(IExtensionService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.complete(null)
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }
        }
        val bound = try {
            withContext(Dispatchers.IO) {
                appContext.bindService(service.explicitIntent(action), connection, Context.BIND_AUTO_CREATE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!bound) return null
        val binderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val remote = withTimeoutOrNull(timeoutMillis) { connected.await() } ?: return null
            val response = CompletableDeferred<String?>()
            binderScope.launch {
                response.complete(runCatching { remote.capabilityManifest }.getOrNull())
            }
            // A blocking remote Binder call cannot always be interrupted. Do not hold up discovery
            // while that remote process is stuck; cap the number of attempted services above.
            withTimeoutOrNull(timeoutMillis) { response.await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            binderScope.cancel()
            runCatching { appContext.unbindService(connection) }
        }
    }

    private companion object { const val MAX_SERVICES = 64 }
}
