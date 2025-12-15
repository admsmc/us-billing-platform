# HR pay period management (A2)
This document captures the HR service’s pay period validation rules and how to enforce gap-free calendars.
## Invariants
The HR service treats pay periods as inclusive date ranges.
The following invariants are always enforced on writes (manual or generated):
- `startDate <= endDate`
- `checkDate >= endDate`
- No overlaps across any existing pay period ranges for the employer
- `checkDate` is unique per employer (so lookups by check date are deterministic)
## Gaps
Some employers want pay periods to form a continuous calendar (no gaps).
Other employers may allow gaps (seasonal work, paused payroll, etc.).
For this reason, gap prevention is opt-in at the API level.
### Manual pay period creation
Endpoint: `PUT /employers/{employerId}/pay-periods/{payPeriodId}`
- Query param: `allowGaps` (boolean, default `true`)
- When `allowGaps=false`, the HR service enforces adjacency for the same `frequency`:
  - If there is a previous pay period, then `previous.endDate + 1 day == new.startDate`
  - If there is a next pay period, then `new.endDate + 1 day == next.startDate`
### Generated pay periods
Endpoint: `POST /employers/{employerId}/pay-schedules/{scheduleId}/generate-pay-periods`
- Query param: `allowGaps` (boolean, default `true`)
- The generated set is always validated to be contiguous.
- When `allowGaps=false`, the generated set must also be adjacent to any existing pay periods for the same frequency immediately before/after the generated range.
