#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "SECURITY CHECK FAILED: $*" >&2
  exit 1
}

for required in LICENSE NOTICE.md SECURITY.md CONTRIBUTING.md .github/CODEOWNERS; do
  test -f "$required" || fail "missing required security/legal file: $required"
done

# Key stores and private-key files must never be tracked.
if git ls-files | grep -E '\.(jks|keystore|p12|pfx|pem|key)$' >/dev/null; then
  echo "Tracked key/signing material:" >&2
  git ls-files | grep -E '\.(jks|keystore|p12|pfx|pem|key)$' >&2
  fail "tracked key/signing material is forbidden"
fi

# High-confidence credential formats. Exclude this checker so its own patterns do not self-match.
if git grep -nE '(github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----)' --   ':!scripts/security_sanity.sh' >/tmp/omnilink-secret-hits.txt 2>/dev/null; then
  cat /tmp/omnilink-secret-hits.txt >&2
  fail "possible credential/private key committed"
fi

# Do not run untrusted PR code with a privileged pull_request_target token.
if grep -RInE '^[[:space:]]*pull_request_target[[:space:]]*:' .github/workflows; then
  fail "pull_request_target is forbidden by repository policy"
fi

# Every external GitHub Action must be pinned to an immutable 40-character commit SHA.
bad_actions=0
while IFS= read -r line; do
  ref="$(printf '%s\n' "$line" | sed -E 's/.*uses:[[:space:]]*([^[:space:]#]+).*/\1/')"
  case "$ref" in
    ./*) continue ;;
  esac
  if ! printf '%s\n' "$ref" | grep -Eq '@[0-9a-fA-F]{40}$'; then
    echo "Mutable/unpinned Action: $line" >&2
    bad_actions=1
  fi
done < <(grep -RInE '^[[:space:]-]*uses:[[:space:]]*' .github/workflows || true)

test "$bad_actions" -eq 0 || fail "all external Actions must use immutable commit SHAs"

# The ordinary CI workflow should never request repository write permissions.
if grep -A8 -E '^permissions:' .github/workflows/ci.yml | grep -Eq ':[[:space:]]*write'; then
  fail "ordinary CI must remain read-only"
fi

echo "Repository security sanity checks passed."
