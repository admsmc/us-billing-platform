# labor-service

This module owns state and federal labor standards integration (minimum wage, tipped wage, overtime thresholds) for the payroll engine.

## Maintaining state labor standards data (non-engineering workflow)

The authoritative source for per-state labor standards is a CSV file in this repo:

- `src/main/resources/labor-standards-2025.csv`

Engineers own the **schema** (column names, order, and types). Policy/operations collaborators typically only edit a small set of numeric columns.

### Columns policy/ops may edit

For each state row (one per state code):

- `regular_min_wage` – state minimum wage in **dollars** (e.g. `16.00`).
- `tipped_min_cash_wage` – cash wage for tipped employees, in **dollars**.
- `max_tip_credit` – maximum tip credit per hour, in **dollars**.

Optional (by agreement with engineering):

- `weekly_ot_hours` – hours per week before overtime.
- `daily_ot_hours` – hours per day before daily overtime.
- `daily_dt_hours` – hours per day before daily double-time.

All other columns (headers, state codes, dates, citation fields) should generally be treated as read-only by non-engineering users.

### Suggested Google Sheets workflow

1. From this repo, take the current `labor-standards-2025.csv` and import it into Google Sheets:
   - In Sheets: **File → Import → Upload** the CSV and create/replace a sheet.
   - Freeze the header row for easier scrolling.
2. Protect all columns except the editable numeric ones so that policy/ops can only change:
   - `regular_min_wage`
   - `tipped_min_cash_wage`
   - `max_tip_credit`
3. Add basic data validation on those columns:
   - Type: **Number**, greater than or equal to 0.
   - Two decimal places (values are in dollars, not cents).
4. Guidance for values:
   - Leave the cell **blank** if the state defers to the federal minimum or has no separate rule.
   - Enter amounts as plain numbers like `12.50` (no `$`, no commas).

### Syncing changes back into the repo (engineering)

When policy/ops have updated the sheet:

1. In Google Sheets, download the updated data as CSV:
   - **File → Download → Comma-separated values (.csv)**.
2. In this repository, overwrite `src/main/resources/labor-standards-2025.csv` with the downloaded file.
3. From the project root, run **either** of the following:

   **Option A – Gradle task directly**

   ```bash
   ./gradlew :labor-service:test --no-daemon
   ./gradlew :labor-service:runLaborStandardsImporter --no-daemon -PlaborYear=2025
   ```

   Or:

   **Option B – helper script**

   ```bash
   LABOR_YEAR=2025 ./scripts/refresh-labor-standards.sh
   ```

   This will:
   - Validate that the CSV still matches the expected format and parses correctly.
   - Regenerate:
     - `src/main/resources/labor-standards-2025.json` (JSON config used by tools/runtime), and
     - `src/main/resources/labor-standard-2025.sql` (INSERTs for the `labor_standard` table).

4. Review and commit the updated CSV + JSON + SQL files together as a single change.

This keeps the CSV easy to manage in a spreadsheet while ensuring the codebase, generated JSON, and database seed data all stay in sync.