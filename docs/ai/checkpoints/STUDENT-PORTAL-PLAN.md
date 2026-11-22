# Student portal

1. Replace My classes with a full monthly calendar, including a day agenda, month navigation and today shortcut. Combine the student's enrolled sessions with upcoming Zoom schedules linked to their enrolled batches. Keep existing class participation links and move consent to My Report.
2. Make My Report the student home. Reuse monthly attendance, class history and appeals, with no change to official attendance calculations.
3. Add Live Now. Confirm live status from recorded Zoom evidence before showing counts or the chart. Show distinct joined and online students across the viewer's enrolled batches for that class, plus only the viewer's own observed connection time. Poll only while the tab is visible and remove metrics when the run ends. Show the previous and next known classes while idle.
4. Keep the PCA logo in the compact sidebar and use responsive layouts and motion that respects reduced-motion settings.
5. Verify access against anonymous, staff-only, unrelated student and cross-academy requests. Check live-to-idle transitions, calendar navigation and report links on desktop and mobile.

## Behavior and rollout

- **Calendar** opens the current month in Sri Lanka time. Selecting a day opens its agenda with enrolled Zoom slots, class times and cancelled sessions. Plan data comes from linked, successfully synced Zoom monitors and the existing class records. The legacy My classes URL forwards to Calendar.
- **My Report** remains the student home. It brings monthly attendance, class history, appeals, batches, and engagement consent into one section.
- **Live Now** refreshes every 30 seconds in a visible tab. The graph slider lets students explore prior samples during the live class. Counts reflect matched Zoom activity and can lag. When idle, the previous and next class cards use a 45-day window in each direction.
- Student timetable and live pages display class information without publishing Zoom links or webinar IDs.
- Roll out the backend endpoints before the frontend. The change needs no migration. Revert the frontend first and then the API to roll back. The isolated local preview uses fictional data at `http://127.0.0.1:3450`; its registration ID is `PCA001`.
