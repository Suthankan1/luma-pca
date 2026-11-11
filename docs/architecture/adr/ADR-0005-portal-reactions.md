# ADR-0005 — Portal-owned identifiable reactions
**Status:** Accepted as safe implementation path, pending academy agreement · 2026-11-11
**Decision:** Student-identifiable reaction counts originate only from the academy-controlled logged-in student portal, with integrity checks, rate limiting and opt-in/consent policy. Zoom-native attendee reaction identity is experimental and must not feed per-student leaderboards without proof. Q&A/poll event sources remain explicitly labeled separately.
**Consequences:** Portal is an additional student interaction; don't imply students pressing native Zoom emoji buttons are captured in this metric. Alternative may be Q&A/poll-only analytics if portal usage is rejected.
