# Coverage expansion plan (state/local) (B3)

This document captures the current state/local coverage in the repo and proposes a sequencing plan for expanding it.

The goal is to make scope explicit for enterprise go-lives: what is fully supported today, what is partially supported, and what is out of scope.

## Current coverage snapshot

### Tax (withholding + selected other payroll taxes)

#### State income tax withholding (STATE)
Supported:
- All 50 states are modeled for tax year 2025 via the CSV-driven pipeline:
  - Inputs:
    - `tax-content/src/main/resources/state-income-tax-2025-rules.csv`
    - `tax-content/src/main/resources/state-income-tax-2025-brackets.csv`
  - Generated:
    - `tax-content/src/main/resources/tax-config/state-income-2025.json`

Notes:
- “Modeled” means the repo has a rule entry per state for 2025 and it passes `:tax-service:validateTaxConfig`.
- Policy correctness still depends on the curated numbers and SME approvals captured in metadata sidecars.

#### Local income tax withholding (LOCAL)
Partially supported:
- Local withholding rules exist for a limited set of localities via `local-income-2025.json` and `mi-locals-2025.json`.
- Current `localityFilter` values used by tax configs:
  - `NYC`
  - Michigan locals: `DETROIT`, `GRAND_RAPIDS`, `LANSING`
  - Ohio locals: `AKRON`, `CINCINNATI`, `CLEVELAND`, `COLUMBUS`, `DAYTON`, `TOLEDO`, `YOUNGSTOWN`
  - Other locals: `BALTIMORE_CITY`, `BIRMINGHAM`, `KANSAS_CITY`, `LOUISVILLE`, `MARION_COUNTY`, `MULTNOMAH_PFA`, `PHILADELPHIA`, `PORTLAND_METRO_SHS`, `ST_LOUIS`, `WILMINGTON`

Known gaps:
- Most US localities are not modeled.
- Local reciprocity rules and multi-locality allocation within a pay period are not yet covered as first-class content.

#### Unemployment and related payroll taxes (OTHER)
Partially supported (examples exist):
- FUTA: `US_FUTA_2025`
- State unemployment insurance (SUI) examples: `CA_SUI`, `NY_SUI`, `TX_SUI`, `WA_SUI`, `NJ_SUI`, `OR_SUI`, `MA_SUI`
- State disability insurance (SDI) examples: `CA_SDI`, `NJ_SDI`, `NY_SDI`

Known gaps:
- SUI/SDI coverage is not comprehensive across all states.
- Employer experience-rating and per-employer rates are not modeled as a governed workflow.

### Labor (minimum wage + overtime thresholds)

#### Statewide baseline labor standards
Supported:
- All 50 states have baseline rows for 2025 in:
  - `labor-service/src/main/resources/labor-standards-2025.csv`

Notes:
- Some states intentionally defer to federal minimum wage (represented as blank wage values in the CSV).

#### Local labor standards overrides
Partially supported:
- A small set of locality overrides exist in:
  - `labor-service/src/main/resources/labor-standards-2025-local.csv`

Current localities include:
- CA: `LA_CITY`, `SF`
- IL: `CHI`
- NY: `NYC`
- OR: `PORTLAND_METRO`
- WA: `SEA`

Known gaps:
- Most local wage ordinances are not modeled.
- Complex locality rules (tiered wages, employer size bands, industry-specific rates) are not yet modeled.

## Coverage expansion sequencing

### Phase 1 — Make coverage measurable (always-on reporting)
- Maintain this document as the single “what is covered” reference.
- Add lightweight automation (optional) to produce a coverage summary from:
  - `tax-content` CSVs/JSON
  - labor CSVs
- Treat the output as an ops signal, not a hard gate.

### Phase 2 — Expand state-level payroll taxes (SUI/SDI) to enterprise baseline
- Define the target baseline for first enterprise deployments:
  - minimum viable SUI per state (rates + wage bases), plus SDI where applicable.
- Model per-employer experience rates as an explicit configuration workflow (overlay rules or employer configuration tables).
- Sequence by customer demand; typical initial set:
  - CA, NY, TX, FL, IL, PA, NJ, WA, MA, OH

### Phase 3 — Expand local taxes where enterprise customers cluster
- Focus on a curated locality list by market demand.
- Standardize locality identifiers and mapping strategy:
  - HR supplies work city/county/metropolitan identifiers
  - Tax rules reference locality codes
  - Validation ensures locality codes used in configs are registered

### Phase 4 — Expand local labor ordinances in parallel with local tax expansion
- Add locality wage overrides for the same initial markets as Phase 3.
- Extend the labor config model if needed for tiered/complex ordinances.

### Phase 5 — Formalize annual update + audit artifacts for expanded scope
- As scope grows, ensure every new jurisdiction has:
  - an annual update playbook entry
  - metadata sidecars populated with real sources + approvals
  - golden tests for representative scenarios

## Outputs
- This doc is the current “coverage matrix” (supported / partial / gaps) and the sequencing plan.
- Update it whenever new jurisdictions/localities are added.
