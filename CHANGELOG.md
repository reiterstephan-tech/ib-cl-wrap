# Changelog

All notable user-visible changes to `ib-cl-wrap` should be documented in this
file.

This project is in an API stabilization phase. During that work, downstream
consumers should also consult:

- [Downstream Migration Guide](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/downstream-migration.md)
- [API Stabilization Roadmap](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/roadmap.md)

## Unreleased

### Added

- Added API stabilization planning documents:
  - [Roadmap](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/roadmap.md)
  - [Phase 1](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-1-freeze-the-contract.md)
  - [Phase 2](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-2-remove-ambiguity.md)
  - [Phase 3](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-3-tighten-enforcement.md)
  - [Phase 4](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/phases/phase-4-hardening-and-operational-stability.md)
- Added a dedicated downstream migration artifact:
  - [Downstream Migration Guide](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/downstream-migration.md)
- Added the Phase 1 compatibility contract:
  - [Compatibility Policy](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/compatibility.md)

### Changed

- Completed Phase 1 on 2026-04-19 and marked Milestone A done in the roadmap.
- Declared the stable public API boundary versus experimental public APIs in:
  - [Spec Surface](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/spec-surface.md)
- Aligned the documented event list with the currently emitted event families in:
  - [Events](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/events.md)
  - [IB Event Schema v1](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/event-schema-v1.md)
- Declared documentation authority: `README.md` is authoritative and
  `README.en.md` is a maintained translation.
- Declared connection handles opaque and promoted `:request-id` as the
  canonical request-correlation field during stabilization.
- Declared that market data, contract details, reconnect events, and
  order-placement APIs remain experimental during `0.x`.
- Documented that Phase 1 freezes the public surface, while Phase 2 may still
  finalize canonical result shapes and deprecations.
- Documented that Phase 1 must define versioning policy, IB API jar
  compatibility policy, and README authority.
- Documented that Phase 2 must preserve test coverage for canonical
  replacements during migration.
- Documented concrete release communication artifacts for each phase:
  `CHANGELOG.md`, downstream migration guide, and release notes.
