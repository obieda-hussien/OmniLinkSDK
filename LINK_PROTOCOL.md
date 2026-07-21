# OmniLink Protocol

## Action Naming
Satellite apps must never define their own action starting with `_`. The `_` prefix is reserved for protocol-level system calls (e.g. the heartbeat tick, `_tick`). This keeps a clean namespace between "the protocol talking to itself" and "the agent calling app-specific capabilities," with no new AIDL method required to add future system-level calls.
