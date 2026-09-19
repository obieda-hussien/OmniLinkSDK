#!/usr/bin/env bash
set -euo pipefail

VERSION="$(sed -n 's/^OMNILINK_VERSION=//p' gradle.properties | head -n1 | tr -d '[:space:]')"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Invalid OMNILINK_VERSION: '$VERSION'" >&2
  exit 1
fi

LINE="${VERSION%.*}"

require_text() {
  local file="$1"
  local needle="$2"
  if ! grep -Fq -- "$needle" "$file"; then
    echo "Documentation version check failed: '$file' is missing '$needle'" >&2
    exit 1
  fi
}

require_text README.md "OmniLinkSDK $LINE"
require_text README.md "Version **$VERSION**"
require_text README.md "v$VERSION"
require_text INTEGRATION_GUIDE.md "OmniLinkSDK $LINE Integration Guide"
require_text INTEGRATION_GUIDE.md "v$VERSION"
require_text 00_INTEGRATION_ORDER.md "OmniLinkSDK $LINE"
require_text 01_OmniLinkSDK_PROMPT.md "OmniLinkSDK $LINE"
require_text LINK_PROTOCOL.md "OmniLink $LINE"
require_text SIGNING_TRUST.md "OmniLink $LINE"
require_text OMNILINK_TRUST_MESH.md "OmniLink $LINE"
require_text DESKTOP_TRANSPORT.md "OmniLink $LINE"
require_text CHANGELOG.md "## $VERSION"

echo "Documentation version checks passed for OmniLinkSDK $VERSION"
