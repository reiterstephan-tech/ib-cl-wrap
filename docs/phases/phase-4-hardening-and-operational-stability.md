# Phase 4: Hardening and Operational Stability

## Goal

Improve runtime reliability and observability around the stabilized API.

This phase is about production behavior rather than API shape.

## Why This Phase Comes Last

Hardening should support a settled contract. Once earlier phases define and
enforce the API, this phase can focus on real operational guarantees instead of
locking in behavior that may still change.

## Main Tasks

1. Improve overflow and drop observability.
   - Make counters and metrics reflect actual behavior.
   - Distinguish closed-channel failures from real pressure-related loss where
     possible.

2. Clarify reconnect guarantees.
   - Define what events are emitted during reconnect.
   - Define what requests are safe to retry.
   - Clarify what happens to request registries and in-flight operations.

3. Tighten request lifecycle guarantees.
   - Review cleanup paths for subscriptions and channels.
   - Ensure timeout, disconnect, and request-failure paths are consistent.
   - Reduce ambiguity around concurrent flows.

4. Improve diagnostics.
   - Enrich error contexts where useful.
   - Make operational states easier to inspect using existing public events and
     diagnostics where possible.
   - Add new public inspection APIs only if existing surfaces cannot carry the
     required information cleanly.

## Downstream Impact

Expected downstream impact:
- low to medium

Likely downstream changes:
- consumers may need to update alerting or dashboards if diagnostic counters or
  reconnect events become more precise
- integrations may need to adjust retry or recovery logic if reconnect
  semantics are clarified
- downstream tests may need updates if they previously assumed less precise
  operational behavior

## Recommended Downstream Communication

When this phase ships, communicate:

- whether any reconnect event sequences changed
- whether any counters or diagnostics changed meaning
- whether request cleanup timing or failure classification changed

Recommended artifacts:

- `CHANGELOG.md`
- `docs/downstream-migration.md`
- release notes

## Deliverables

- more trustworthy event/drop diagnostics
- documented reconnect behavior
- stronger cleanup guarantees for request lifecycles
- improved error and state visibility

## Non-Goals

- redefining the public contract
- introducing major new public API concepts unless required for diagnostics

Preferred order for observability changes:

1. enrich existing event payloads where compatible
2. improve existing counters or diagnostics
3. add narrowly scoped new public inspection APIs only if the first two are not
   sufficient

## Exit Criteria

Phase 4 is done when:

- runtime metrics and counters are trustworthy
- reconnect behavior is documented and testable
- request lifecycle cleanup is consistent across failure modes
- the library is easier to debug under real IB session conditions
- downstream operations teams can update monitoring and recovery expectations
