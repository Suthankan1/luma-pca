# Assign a payment class roster from Class Types

1. Keep the selected month's Zoom schedule as the list of assignable class rows. In each uniquely identified tracked webinar row, use the exact payment `class_type` names from the read-only Academy source and show the expected count after sync.
2. On Sync, validate the staff session and webinar scope, sync the selected source roster, and assign the webinar in one LUMA transaction. Create or replace the internal roster as needed. Publish only when the expected membership agrees with the source count. Retain historical roster links and deliberate withdrawals.
3. Keep this assignment for subsequent runs of that webinar until staff change it. The month filters the schedule on screen. Payment membership comes from the selected label. For example, if DC contains 140 eligible distinct students, upcoming December Paper Class runs expect 140. Actual attendance remains based on matched Zoom evidence.
4. Verify authorization, the 140-student case, roster changes, and failure before assignment. No external payment data is written and no schema migration is required.
