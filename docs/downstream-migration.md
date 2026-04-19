# Downstream Migration Guide

This document is the central migration artifact for downstream consumers of
`ib-cl-wrap`.

It should be updated whenever a stabilization phase introduces user-visible
changes that require consumer action.

## Purpose

Use this file to document:

- breaking API changes
- deprecations
- result-shape migrations
- renamed metrics or events
- IB API compatibility changes
- required downstream code updates

`CHANGELOG.md` should summarize changes. This file should explain how to adapt.

## Expected Phase Usage

### Phase 1

Document:

- which APIs are stable
- which APIs are experimental or transitional
- which IB API jar versions are supported
- whether result-shape changes are still expected in Phase 2

Current Phase 1 downstream actions:

- treat [docs/spec-surface.md](/Users/stephan/Syncthing/dev/codex/ib-cl-wrap/docs/spec-surface.md)
  as the stable API boundary
- treat market data, contract details, reconnect helpers, and order-placement
  helpers as experimental during `0.x`
- stop treating visible helper namespaces such as `ib.events` as stable API
- prefer `:request-id` over legacy event keys such as `:req-id`
- treat connection handles from `connect!` as opaque maps
- expect Phase 2 to normalize duplicate contract-details and snapshot result
  conventions before `1.0`

### Phase 2

Document:

- old and new result shapes
- old and new canonical function names
- deprecated APIs and their replacements
- migration deadlines if compatibility shims are temporary

### Phase 3

Document:

- stricter validation rules
- newly rejected invalid inputs
- spec-related assumptions downstream code must now satisfy

### Phase 4

Document:

- reconnect behavior changes
- diagnostics or counter meaning changes
- operational assumptions downstream systems must update

## Recommended Entry Format

For each downstream-impacting change, record:

1. the old behavior
2. the new behavior
3. who is affected
4. the required downstream change
5. whether the change is breaking, deprecated, or additive
6. which release first contains the change

## Initial Known Impact Areas

Likely downstream-impact areas during stabilization:

- snapshot result-shape normalization
- duplicate contract-details API removal or deprecation
- diagnostic counter renames or semantic corrections
- standardization around canonical request-id fields
- stronger validation of public option maps
- clarified IB API jar version support
