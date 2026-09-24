#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-}"
if ! [[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Expected a release tag such as v3.0.0" >&2
  exit 1
fi

ROOT="https://jitpack.io/com/github/obieda-hussien/OmniLinkSDK"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

verify() {
  local module="$1" extension="$2" path output attempt
  path="$ROOT/$module/$VERSION/$module-$VERSION.$extension"
  output="$TEMP_DIR/$module.$extension"
  for ((attempt=1; attempt<=12; attempt++)); do
    if curl --fail --location --silent --show-error --connect-timeout 20 \
      --max-time 120 --output "$output" "$path" && test -s "$output"; then
      if [[ "$extension" == "aar" || "$extension" == "jar" ]]; then
        unzip -tqq "$output" || { echo "Invalid $module archive" >&2; return 1; }
      fi
      echo "Verified $module $VERSION ($extension)"
      return 0
    fi
    rm -f "$output"
    if (( attempt < 12 )); then sleep 20; fi
  done
  echo "JitPack artifact unavailable: $path" >&2
  return 1
}

verify omni-link-public aar
verify omni-link-sdk aar
verify omni-link-transport jar
