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

### Changed

- Documented that Phase 1 freezes the public surface, while Phase 2 may still
  finalize canonical result shapes and deprecations.
- Documented that Phase 1 must define versioning policy, IB API jar
  compatibility policy, and README authority.
- Documented that Phase 2 must preserve test coverage for canonical
  replacements during migration.
- Documented concrete release communication artifacts for each phase:
  `CHANGELOG.md`, downstream migration guide, and release notes.
