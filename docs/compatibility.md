# Compatibility Policy

This document is the Phase 1 compatibility contract for `ib-cl-wrap`.

Status: Done as of 2026-04-19.

## Documentation Authority

- [README.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/README.md) is the
  authoritative top-level project README.
- [README.en.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/README.en.md) is
  a maintained English translation of the authoritative README.
- API and event guarantees are defined by:
  - [docs/spec-surface.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/spec-surface.md)
  - [docs/events.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/events.md)
  - [docs/event-schema-v1.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/event-schema-v1.md)

If the README and the dedicated contract docs diverge, the dedicated contract
docs win.

## Public Surface Classes

`ib-cl-wrap` now distinguishes between two supported public API classes:

- stable public API:
  documented for normal downstream use and covered by
  `ib.spec/public-api-vars`
- experimental public API:
  callable and documented, but still expected to change during stabilization

Experimental APIs may change shape, naming, or behavior in a normal `0.x`
release when required to reach a coherent `1.0` contract.

Internal helper namespaces such as `ib.events` are not stable public API even
when some vars are visible from Clojure.

## Connection Handle Policy

Connection handles returned by `ib.client/connect!` are opaque maps.

Downstream code may pass the handle back into documented public functions, but
must not depend on:

- exact internal keys
- atom layout
- event bus implementation details
- reconnect bookkeeping fields

Only behavior documented on public functions is part of the support promise.

## Event Compatibility Policy

- Every emitted normalized event includes the v1 envelope keys:
  `:type`, `:source`, `:status`, `:request-id`, `:ts`, `:schema-version`
- `:request-id` is the canonical request correlation key for new consumers
- legacy compatibility keys such as `:req-id` and `:id` may still appear on
  specific events during the `0.x` stabilization line
- legacy request-id keys remain compatibility fields through the end of the
  `0.x` line, but new code should not treat them as canonical
- a new event schema version must be introduced by changing `:schema-version`
  rather than silently redefining the meaning of v1 fields

Additive event changes allowed in v1:

- adding new event types
- adding optional payload keys to existing event types
- adding new experimental event families

Breaking event changes for an existing schema version are not allowed once a
release ships. They must either:

- wait for a new schema version, or
- remain behind an experimental API boundary

## Release Versioning Policy

The project follows semantic-versioning intent, with extra caution for the
current `0.x` stabilization period.

During `0.x`:

- patch releases are for fixes and documentation-only corrections
- minor releases may still contain breaking changes when they are required to
  stabilize the API
- breaking changes must be called out explicitly in
  [CHANGELOG.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/CHANGELOG.md)
  and
  [docs/downstream-migration.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/downstream-migration.md)

The project is ready for `1.0` when:

- the stable public surface is intentionally defined
- canonical result shapes are settled
- deprecations and compatibility shims are complete
- specs and tests enforce the promised contract

After `1.0`:

- additive backward-compatible changes are minor releases
- breaking public API changes require a major release
- deprecations should ship with a documented replacement path before removal

## IB Java API Compatibility Policy

The project supports the Interactive Brokers Java API jar that matches the
tested development and CI workflow for this repository.

Current support promise:

- the wrapper supports the `ibapi.jar` layout expected by the current source
  tree and tests
- reflective compatibility across arbitrary IB jar variants is not part of the
  stable promise in Phase 1
- newer or older IB jars may work, but they are best-effort unless explicitly
  tested and documented

Compatibility verification lives in:

- API-level tests in `test/`
- manual integration checks against a locally supplied `lib/ibapi.jar`

If jar compatibility is widened later, that support matrix should be updated in
this document and enforced in a later stabilization phase.
