## Authorization

- [ ] This change was explicitly authorized by the repository owner.
- [ ] I have the right to submit every included file and dependency.
- [ ] No secrets, signing keys, tokens, credentials, private user data, or proprietary third-party code are included.

## Security

- [ ] Identity and authorization remain separate.
- [ ] Unknown/PAIRED callers do not gain privileged capabilities.
- [ ] Remote input remains size-bounded and untrusted.
- [ ] No new shell/code/SQL/path injection surface was introduced without an explicit constrained capability.
- [ ] Cryptographic verification, replay protection, and fail-closed behavior were not weakened.
- [ ] GitHub Actions use least privilege and immutable action SHAs.

## Verification

- [ ] `bash scripts/verify_docs_version.sh`
- [ ] `./gradlew build test --stacktrace`
- [ ] `./gradlew publishToMavenLocal --stacktrace`

> Unsolicited code contributions are not accepted. See CONTRIBUTING.md and LICENSE.
