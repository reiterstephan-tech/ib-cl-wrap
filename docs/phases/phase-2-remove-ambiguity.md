# Phase 2: Remove Ambiguity

## Goal

Reduce or eliminate conflicting API patterns before they become long-term
compatibility obligations.

This phase turns the frozen contract into a coherent one.

## Why This Phase Comes Second

Once the stable surface is declared, the next risk is inconsistency inside that
surface. Ambiguity makes stable APIs expensive because every inconsistency turns
into either user confusion or permanent maintenance burden.

This phase is where canonical shapes are finalized. Phase 1 defines which APIs
are public; Phase 2 can still normalize how those public APIs behave before the
contract is considered fully locked.

## Main Problems To Address

- multiple snapshot result conventions
- overlapping or duplicate APIs
- names that imply guarantees the implementation does not currently provide
- mixed request and response conventions across namespaces

## Main Tasks

1. Unify snapshot result shapes.
   - Decide whether snapshot APIs return:
     - `{:ok true|false ...}` envelopes, or
     - ad hoc values and error maps
   - Migrate the outliers to the chosen pattern.

2. Resolve duplicate contract-details APIs.
   - Choose the canonical API.
   - Either remove, deprecate, or clearly differentiate alternate variants.

3. Clarify operational counters and names.
   - Revisit `dropped-event-count`.
   - Rename or redefine metrics whose semantics are currently misleading.

4. Define deprecation behavior.
   - Keep compatibility where practical.
   - Mark old patterns in docs and tests.
   - Prefer explicit transition paths over silent divergence.

5. Preserve test coverage during migration.
   - Migrate or replace tests as canonical APIs are introduced.
   - Do not leave only deprecated-path tests green while replacement paths have
     no equivalent coverage.
   - Keep deprecated and canonical paths both covered during transition windows
     where both remain supported.

## Downstream Impact

Expected downstream impact:
- high

This is the phase most likely to break downstream consumers.

Likely downstream changes:
- callers of `positions-snapshot!` may need to adapt if its result shape is
  normalized to match the `{:ok ...}` envelope used elsewhere
- consumers of duplicate contract-details helpers may need to move to one
  canonical API
- code that reads diagnostic counters such as `dropped-event-count` may need to
  rename metrics or adjust expectations
- consumers that rely on mixed legacy and canonical request-id keys may need to
  standardize on one path

## Recommended Migration Strategy

To reduce downstream pain:

1. introduce the canonical API first
2. keep compatibility shims for one release window where practical
3. document old and new forms side by side
4. add explicit deprecation notes in code and docs
5. remove legacy variants only in a clearly marked breaking release

## Recommended Downstream Communication

When this phase ships, publish a migration note that includes:

- the old shape or function
- the new shape or function
- a short before/after example
- whether the change is required immediately or only before the next major
  release

Recommended artifacts:

- `CHANGELOG.md`
- `docs/downstream-migration.md`
- release notes

## Deliverables

- one canonical snapshot result convention
- one canonical contract-details snapshot API
- clarified naming for event/drop diagnostics
- documented deprecation notes where compatibility is preserved temporarily
- migrated test coverage for all new canonical APIs

## Risks

- changing result shapes may affect existing consumers
- keeping too many compatibility shims may dilute the cleanup

The right balance is to simplify the API while keeping migration cost
predictable.

## Exit Criteria

Phase 2 is done when:

- public APIs follow a consistent response model
- duplicate or overlapping APIs no longer create confusion
- misleading names are removed or explicitly qualified
- the codebase is ready for a v1-style release candidate
- downstream consumers have a documented migration path for every intentional
  incompatibility
- canonical replacements have passing tests before deprecated paths are removed
