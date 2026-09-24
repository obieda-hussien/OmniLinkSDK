package com.omnilink.sdk

import com.omnilink.sdk.trusted.VerifiedService
import kotlinx.serialization.decodeFromString

sealed interface CapabilityCatalogResult {
    data class Accepted(val nodes: List<CapabilityGraphNode>) : CapabilityCatalogResult
    data class Rejected(val reason: String) : CapabilityCatalogResult
}

/**
 * Parses a manifest obtained from a verified Binder service. The manifest can describe supported
 * operations, but it cannot claim its own trust tier, package or signer. Consumers must still
 * enforce the provider's access controller and per-request user authorization on execution.
 */
object TrustedCapabilityCatalog {
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_CAPABILITIES = 128

    fun ingest(
        service: VerifiedService,
        manifestJson: String
    ): CapabilityCatalogResult {
        if (service.packageName.isBlank() || service.serviceClassName.isBlank() ||
            service.signerSha256.isEmpty()
        ) return CapabilityCatalogResult.Rejected("unverified_provider")
        if (manifestJson.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            return CapabilityCatalogResult.Rejected("manifest_too_large")
        }
        val manifest = try {
            OmniJson.instance.decodeFromString<CapabilityManifest>(manifestJson)
        } catch (_: Exception) {
            return CapabilityCatalogResult.Rejected("invalid_manifest")
        }
        if (manifest.protocolVersion !in manifest.minSupportedVersion..manifest.maxSupportedVersion ||
            OmniLinkConstants.CURRENT_PROTOCOL_VERSION !in
                manifest.minSupportedVersion..manifest.maxSupportedVersion
        ) return CapabilityCatalogResult.Rejected("incompatible_protocol")
        if (manifest.capabilities.size > MAX_CAPABILITIES ||
            manifest.capabilities.any { it.name.length !in 1..128 || !it.name.all(::validNameChar) } ||
            manifest.capabilities.map { it.name }.distinct().size != manifest.capabilities.size
        ) return CapabilityCatalogResult.Rejected("invalid_capabilities")
        return CapabilityCatalogResult.Accepted(manifest.capabilities.map { capability ->
            CapabilityGraphNode(
                nodeId = "${service.packageName}/${service.serviceClassName}#${capability.name}",
                appPackage = service.packageName,
                capability = capability,
                trustTier = if (service.sameSignerAsHost) TrustTier.FIRST_PARTY else TrustTier.TRUSTED_PARTNER
            )
        })
    }

    private fun validNameChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '.' || char == '_' || char == '-'
}
