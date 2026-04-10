# Phase 3: Tighten Enforcement

## Goal

Make the stabilized contract mechanically enforceable through specs and tests.

This phase converts intent into guardrails.

## Why This Phase Comes Third

Stronger enforcement is most useful after the contract is clear. Tightening
specs too early would harden inconsistencies that Phase 2 is meant to resolve.

## Main Tasks

1. Strengthen specs for newer APIs.
   - Replace generic `map?` specs where practical.
   - Add structured specs for:
     - market data request options
     - contract details request options
     - order placement options
     - canonical snapshot results

2. Add compatibility tests.
   - Assert the intended public vars.
   - Assert the intended event types.
   - Assert required event envelope keys and stable payload keys.

3. Expand result-shape tests.
   - Verify all public snapshot helpers follow the chosen contract.
   - Add tests for error and timeout paths in the normalized shape.

4. Cover deprecation paths.
   - If compatibility shims remain, test them explicitly.
   - Ensure old behavior fails loudly when it is no longer supported.

## Downstream Impact

Expected downstream impact:
- medium

This phase is less about changing the API shape and more about rejecting usage
that was previously tolerated.

Likely downstream changes:
- callers may need to supply more complete or correctly typed option maps
- consumers depending on missing or loosely typed fields may see failures
  earlier in development or test
- custom integrations may need updates if they relied on undocumented partial
  payloads

## Recommended Downstream Communication

When this phase ships, communicate:

- which validations became stricter
- whether runtime behavior changed or only instrumentation/test behavior
- concrete examples of previously tolerated invalid calls that are now rejected

Recommended artifacts:

- `CHANGELOG.md`
- `docs/downstream-migration.md`
- release notes

## Deliverables

- stronger `clojure.spec` coverage for public APIs
- compatibility tests that fail on accidental public API changes
- result-shape tests across all snapshot helpers
- explicit deprecation tests where needed
- compatibility checks for declared IB API jar support where practical

## Benefits

- accidental breaking changes are detected earlier
- docs and implementation are easier to keep aligned
- future refactors are safer

## Exit Criteria

Phase 3 is done when:

- public APIs have meaningful input and output specs
- the test suite detects unplanned public surface changes
- event contract regressions are caught automatically
- deprecation paths are explicit and test-backed
- downstream consumers can identify validation-related failures quickly
