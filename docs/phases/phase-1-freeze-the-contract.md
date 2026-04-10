# Phase 1: Freeze the Contract

## Goal

Define the exact public API that `ib-cl-wrap` intends to support as stable.

This phase is about making intentional promises. It does not require large
behavioral changes yet, but it does require choosing what counts as public,
documented, and versioned.

## Why This Phase Comes First

The later phases depend on knowing what we are stabilizing.

Without a frozen contract:
- docs drift from implementation
- tests protect the wrong things
- internal helpers can accidentally become external commitments

## Scope

This phase should answer the following questions:

- Which namespaces are public?
- Which vars are public and supported?
- Which event types are part of the stable event contract?
- Which payload keys are guaranteed?
- Which result shapes are considered canonical?
- Which compatibility guarantees apply to future changes?

## Main Tasks

1. Declare the stable public surface.
   - Review `public-api-vars`.
   - Decide whether each exported function is:
     - stable public API
     - experimental API
     - internal helper that should not be documented as public

2. Reconcile code and docs.
   - Align `README.md`, `README.en.md`, `docs/spec-surface.md`, and
     `docs/events.md`.
   - Ensure the documented event list matches the emitted event list.

3. Define stability boundaries.
   - State whether connection handle maps are opaque.
   - State whether legacy keys such as `:req-id` remain supported and for how
     long.
   - State whether newer areas like market data, contract details, and order
     placement are stable or experimental.

4. Publish compatibility rules.
   - Add a short compatibility policy for additions, deprecations, and
     breaking changes.
   - Clarify how event schema versions are introduced.

## Downstream Impact

Expected downstream impact:
- low to medium

Likely downstream changes:
- consumers may need to stop relying on undocumented vars or payload keys
- consumers may need to treat some currently accessible functions as
  non-stable or experimental
- consumers may need to update internal wrapper docs or usage guidelines

This phase should make future breakage more predictable, but it can still feel
breaking to downstream users if they have already depended on behavior that was
never clearly declared as public.

## Downstream Communication

When this phase ships, communicate:

- the exact list of stable namespaces and functions
- the exact event types and guaranteed payload keys
- any APIs that remain available but are no longer considered stable
- whether any consumer-visible behavior is only documented, not changed

## Deliverables

- updated public surface document
- updated event contract document
- explicit compatibility policy
- consistent README references to supported namespaces and functions

## Non-Goals

- redesigning result shapes
- removing duplicate APIs
- strengthening runtime behavior under load

Those belong to later phases.

## Exit Criteria

Phase 1 is done when:

- a reader can identify the supported API without reading source code
- `public-api-vars` and the docs tell the same story
- the event schema documentation matches reality
- experimental or transitional areas are clearly marked
- downstream consumers have enough information to audit their current usage
