# API Stabilization Roadmap

This roadmap describes the recommended implementation order for stabilizing the
public API of `ib-cl-wrap`.

The goal is not only to make the code pass tests, but to make the API easier to
understand, safer to depend on, and cheaper to evolve.

Downstream adaptation is part of this work. Several stabilization steps are
likely to affect consumers, especially if they rely on undocumented behavior,
inconsistent result shapes, or overlapping APIs.

## Implementation Order

1. Phase 1: Freeze the Contract
2. Phase 2: Remove Ambiguity
3. Phase 3: Tighten Enforcement
4. Phase 4: Hardening and Operational Stability

## Why This Order

The order is intentional:

- Phase 1 defines what is public and stable.
- Phase 2 removes conflicting or confusing API behavior before it becomes
  locked in.
- Phase 3 adds stronger specs and compatibility tests once the contract is
  settled.
- Phase 4 improves runtime behavior and observability around the stabilized
  contract.

Important boundary:

- Phase 1 freezes which APIs, namespaces, and event families are public.
- Phase 2 may still change canonical result shapes, naming, and behavior within
  that public surface before those details are treated as fully locked.

Doing these out of order would create churn. For example, hardening reconnect
or overflow behavior before the public contract is frozen would increase the
chance of stabilizing the wrong behavior.

## Phase Summary

### Phase 1: Freeze the Contract

Primary outcome:
- a clearly documented and intentionally versioned public surface

Focus:
- public namespaces and functions
- event types and payload guarantees
- result map conventions
- compatibility policy

Downstream impact:
- low to medium
- mostly documentation and classification changes
- may require downstream teams to stop depending on undocumented APIs once the
  stable boundary is declared

Document:
- [Phase 1](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-1-freeze-the-contract.md)

### Phase 2: Remove Ambiguity

Primary outcome:
- one coherent API style instead of multiple competing patterns

Focus:
- duplicate APIs
- inconsistent result shapes
- misleading names or metrics
- deprecation paths

Downstream impact:
- high
- this is the phase most likely to require code changes in downstream consumers
- callers may need to migrate to new result envelopes, new canonical function
  names, or renamed diagnostics

Document:
- [Phase 2](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-2-remove-ambiguity.md)

### Phase 3: Tighten Enforcement

Primary outcome:
- the stabilized API is enforced by specs and tests

Focus:
- stronger option specs
- compatibility tests
- event payload validation
- deprecation coverage

Downstream impact:
- medium
- downstream breakage is less likely to come from behavior changes and more
  likely to come from stricter validation exposing invalid usage

Document:
- [Phase 3](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-3-tighten-enforcement.md)

### Phase 4: Hardening and Operational Stability

Primary outcome:
- more reliable behavior under load, disconnects, and edge conditions

Focus:
- reconnect semantics
- overflow/drop observability
- request lifecycle guarantees
- diagnostics

Downstream impact:
- low to medium
- behavior should become more predictable, but consumers may need to adapt to
  clarified reconnect or diagnostic semantics

Document:
- [Phase 4](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-4-hardening-and-operational-stability.md)

## Suggested Milestones

- Milestone A: complete Phase 1
  - repository has a declared stable surface
- Milestone B: complete Phase 2
  - repository is ready for a practical v1 API release candidate
- Milestone C: complete Phase 3
  - repository has strong change-detection guardrails
- Milestone D: complete Phase 4
  - repository is ready for broader external usage

## Downstream Planning Guidance

Consumers should treat the phases differently:

- Phase 1 should trigger a dependency review.
  - Downstream projects should compare their usage against the newly declared
    stable surface.
- Phase 2 should trigger migration planning.
  - Downstream projects should budget implementation time for API updates.
- Phase 3 should trigger validation cleanup.
  - Downstream projects should expect stricter input and output assumptions.
- Phase 4 should trigger operational verification.
  - Downstream projects should confirm reconnect and diagnostic expectations in
    integration environments.

## Versioning Policy Ownership

Phase 1 must define the project versioning policy.

That policy should answer:

- whether the project remains in `0.x` during stabilization
- whether semantic versioning is the governing policy
- what conditions trigger `1.0`
- how deprecations are handled across releases

Until that exists, references to "breaking release" and "release candidate"
should be read as planning terms rather than a final release contract.

## IB API Compatibility Ownership

IB Java API jar compatibility is a cross-cutting concern and should be declared
in Phase 1, then enforced and hardened in later phases.

That work should define:

- which IB API jar versions are supported
- whether reflective compatibility across jar variants is part of the stable
  support promise
- where jar compatibility expectations are tested

## Release Guidance

To make downstream adaptation manageable:

- Phase 1 can ship in a normal minor release if it only clarifies support
  boundaries without changing behavior.
- Phase 2 should be treated as a breaking-change release unless compatibility
  shims fully preserve existing behavior.
- Phase 3 may be a minor or breaking release depending on whether stricter
  enforcement rejects previously tolerated usage.
- Phase 4 is usually a minor release, unless operational semantics change in a
  way consumers must handle differently.

## Communication Artifacts

Each phase should publish user-facing changes through explicit artifacts.

Recommended artifacts:

- `CHANGELOG.md`
  - authoritative release summary of user-visible changes
- `docs/downstream-migration.md`
  - concrete migration guidance for downstream integrators
- release notes
  - concise packaging of upgrade impact and highlights

Phase 2 should not ship without a migration artifact.

Current artifacts:

- [Changelog](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/CHANGELOG.md)
- [Downstream Migration Guide](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/downstream-migration.md)

## Exit Criteria

The stabilization effort can be considered complete when:

- the public API is documented and matches the code
- ambiguous APIs are removed or explicitly deprecated
- specs and tests guard the public contract
- runtime diagnostics support production debugging
