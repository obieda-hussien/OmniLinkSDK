# OmniLinkSDK Repository Hardening

This document describes the intended GitHub repository controls for OmniLinkSDK.

The repository is public so official artifacts can be built/resolved through the current public JitPack
workflow. Public visibility does not make the project open source; see LICENSE.

## Important public-fork limitation

GitHub's Terms of Service grant GitHub users platform rights to view and fork public repositories
through GitHub.

Therefore a public repository cannot truthfully promise that nobody can press GitHub's Fork button.

The proprietary LICENSE reserves all additional copyright rights and does not grant permission to use,
modify, redistribute, publish, sell, sublicense, or ship derivative builds merely because a GitHub fork
exists.

If absolute technical prevention of GitHub forks is required, the repository must be made private. That
would change the current public JitPack distribution model and should be treated as a separate
infrastructure decision.

## Main branch ruleset

Create a repository ruleset named:

```text
protect-main
```

Target:

```text
default branch
```

Recommended active rules:

- restrict deletions;
- block force pushes;
- require a pull request before merge;
- require conversation resolution;
- require status checks;
- require branch to be up to date before merge;
- block non-fast-forward direct rewrites;
- optionally require signed commits if the owner and the connected GitHub App are configured as bypass
  actors.

Required status checks should include:

```text
build_and_test
Dependency review
CodeQL java-kotlin
```

Because the repository owner is currently the sole human maintainer, requiring a mandatory human review
without an owner bypass would deadlock legitimate owner changes. Prefer a ruleset that restricts
updates and grants bypass only to:

- repository administrators controlled by Abdelrahman Hussein;
- the explicitly trusted connected GitHub App used for authorized maintenance, if it is available as a
  selectable bypass actor.

Do not grant bypass to generic write-role collaborators.

## Release tag ruleset

Create a second ruleset:

```text
protect-release-tags
```

Target pattern:

```text
v*
```

Recommended rules:

- restrict creation to approved bypass actors;
- restrict updates;
- restrict deletion;
- prevent force changes.

Release tags should be immutable after publication.

## Collaborator policy

Keep repository write/admin access minimal.

Review:

```text
Settings -> Collaborators / Manage access
```

Remove any person, bot, deploy key, GitHub App, OAuth App, or integration that does not currently need
write access.

Use read access where write is unnecessary.

Periodically review installed GitHub Apps and fine-grained tokens.

## Actions policy

Recommended repository Actions settings:

```text
Settings -> Actions -> General
```

Use the most restrictive policy that still permits the pinned official actions used by this repository.

Workflow token default:

```text
Read repository contents permission
```

Do not enable broad write permission globally.

The release workflow is the intentional exception and requests `contents: write` explicitly.

Do not allow untrusted fork pull requests to run with repository secrets.

Never add `pull_request_target` code execution for untrusted contributions.

## Security and quality settings

In:

```text
Settings -> Security / Advanced Security
```

enable where available:

- Dependency graph;
- Dependabot alerts;
- Dependabot security updates;
- grouped security updates if useful;
- private vulnerability reporting;
- secret scanning;
- push protection for secrets;
- CodeQL/code scanning.

The repository includes `.github/dependabot.yml` and an advanced CodeQL workflow, but repository-side
features still need to be enabled when GitHub does not enable them automatically.

## Secret policy

Never store in Git:

- Omni release/debug keystores;
- transport private keys;
- GitHub tokens;
- signing passwords;
- API keys;
- device credentials;
- production user data.

The .gitignore blocks common key/secret file patterns as a defense-in-depth measure, but ignore rules
are not a security boundary.

If a secret is committed, rotate/revoke it immediately even if the commit is later removed.

## Workflow supply-chain policy

Official actions are pinned to immutable commit SHAs.

Dependabot monitors both:

- Gradle dependencies;
- GitHub Actions.

Any action update must preserve the immutable-SHA pattern after review.

The CI and release workflows validate the Gradle wrapper before running the build.

## Release policy

The automated release workflow:

1. runs only against main;
2. validates the Gradle wrapper;
3. checks documentation/version consistency;
4. builds and tests both modules;
5. verifies Maven publication locally;
6. verifies an existing version tag, if present, points at the exact release HEAD;
7. creates the version tag and GitHub release only after those checks pass.

Do not create an official release from an unreviewed feature branch.

## Ownership

Copyright © 2026 Abdelrahman Hussein (عبدالرحمن حسين). All rights reserved.

See LICENSE, NOTICE.md, SECURITY.md, and CONTRIBUTING.md.
