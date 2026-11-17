import { formatClassTime, formatDate, formatInstant } from "./staff-forms.ts";

/**
 * Plain-language lines for the activity log. Details are what the API recorded (never
 * passwords, tokens or student personal data); unknown actions fall back to their code.
 */
export type ActivityEvent = { id: string; occurredAt: string; action: string; actorAccountId: string | null; actorEmail: string | null;
  targetType: string; targetId: string | null; targetLabel: string | null; details: Record<string, unknown> };

export const ACTIVITY_CATEGORIES = [
  { key: "", label: "Everything" },
  { key: "staff", label: "Staff" },
  { key: "sign-in", label: "Invitations & sign-in" },
  { key: "students", label: "Students" },
  { key: "classes", label: "Classes & attendance" },
  { key: "zoom", label: "Zoom" },
] as const;

const roleNames: Record<string, string> = {
  ACADEMY_OWNER: "Owner", ACADEMY_ADMIN: "Administrator", INSTRUCTOR: "Instructor", ATTENDANCE_OFFICER: "Attendance officer",
  ANALYST: "Analyst", STUDENT: "Student",
};

const list = (value: unknown) => Array.isArray(value) ? value.map(v => roleNames[String(v)] ?? String(v)).join(", ") : "";
const count = (value: unknown, one: string, many = `${one}s`) => {
  const n = Number(value ?? 0);
  return `${n} ${n === 1 ? one : many}`;
};

const classFields: Record<string, string> = { title: "title", webinar: "Zoom webinar", batches: "batches" };
const dayNames: Record<string, string> = { MONDAY: "Monday", TUESDAY: "Tuesday", WEDNESDAY: "Wednesday", THURSDAY: "Thursday",
  FRIDAY: "Friday", SATURDAY: "Saturday", SUNDAY: "Sunday" };
const zoneOf = (d: Record<string, unknown>) => typeof d.timeZone === "string" ? d.timeZone : "Asia/Colombo";
/** The class's current title, or "a class" once it is gone. */
const quoted = (event: ActivityEvent) => event.targetLabel ? `“${event.targetLabel}”` : "a class";

/** Who acted: their email, "the system" for automatic actions, or "a removed account". */
export function actorName(event: ActivityEvent) {
  if (event.actorEmail) return event.actorEmail;
  return event.actorAccountId ? "A removed account" : "The system";
}

export function describeActivity(event: ActivityEvent): { summary: string; detail?: string } {
  const target = event.targetLabel ?? (event.targetType === "ACCOUNT" ? "an account" : event.targetType.toLowerCase().replace(/_/g, " "));
  const d = event.details ?? {};
  switch (event.action) {
    case "STAFF_ACCOUNT_CREATED": return { summary: `Added staff member ${target}`, detail: list(d.roles) || undefined };
    case "STAFF_ROLES_CHANGED": return { summary: `Changed the roles of ${target}`, detail: `${list(d.before)} → ${list(d.after)}` };
    case "STAFF_ACCOUNT_DISABLED": return { summary: `Disabled ${target}` };
    case "STAFF_ACCOUNT_ENABLED": return { summary: `Re-enabled ${target}`,
      detail: d.newPasswordRequired === "true" ? "A new password is required" : "Existing password kept" };
    case "OWNER_BOOTSTRAPPED": return { summary: `Created the first owner, ${target}` };
    case "INVITATION_ISSUED": return { summary: `Issued an invitation for ${target}`,
      detail: d.delivery === "EMAIL" ? (d.bulk === "true" ? "Emailed (bulk)" : "Emailed") : "Link copied by staff" };
    case "INVITATIONS_QUEUED": return { summary: `Queued ${count(d.queued, "invitation email")}`,
      detail: event.targetType === "BATCH" ? `Batch ${target}${d.resend === "true" ? ", resending" : ""}` : (d.resend === "true" ? "Every active student, resending" : "Every active student") };
    case "PASSWORD_RESET_REQUESTED": return { summary: `Password reset requested for ${target}` };
    case "PASSWORD_RESET_COMPLETED": return { summary: `Password reset completed for ${target}` };
    case "STUDENTS_IMPORTED": return { summary: `Imported ${count(d.students, "student")}`,
      detail: event.targetType === "BATCH" ? `Enrolled ${d.enrolled ?? 0} in ${target}${d.enrolledOn ? ` from ${String(d.enrolledOn)}` : ""}` : undefined };
    case "CLASS_SCHEDULED": return { summary: `Scheduled ${quoted(event)}`,
      detail: typeof d.startsAt === "string" && typeof d.endsAt === "string" ? formatClassTime(d.startsAt, d.endsAt, zoneOf(d)) : undefined };
    case "CLASS_UPDATED": {
      const changed = Array.isArray(d.changed) ? d.changed.map(String) : [];
      const moved = changed.includes("time") && typeof d.startsAtBefore === "string" && typeof d.startsAt === "string" && typeof d.endsAt === "string"
        ? `moved from ${formatInstant(d.startsAtBefore, zoneOf(d))} to ${formatClassTime(d.startsAt, d.endsAt, zoneOf(d))}` : "";
      const rest = changed.filter(field => field !== "time").map(field => classFields[field] ?? field);
      return { summary: `Changed ${quoted(event)}`, detail: [moved, rest.length ? `changed ${rest.join(", ")}` : ""].filter(Boolean).join("; ") || undefined };
    }
    case "CLASS_CANCELLED": return { summary: `Cancelled ${quoted(event)}` };
    case "CLASS_RESTORED": return { summary: `Restored ${quoted(event)}` };
    case "CLASS_SERIES_CREATED": return { summary: `Scheduled a weekly series of ${count(d.classes, "class", "classes")}`,
      detail: [Array.isArray(d.weekdays) ? `Every ${d.weekdays.map(w => dayNames[String(w)] ?? String(w)).join(" and ")}` : "",
        typeof d.firstDate === "string" && typeof d.lastDate === "string" ? `${formatDate(d.firstDate)} – ${formatDate(d.lastDate)}` : ""]
        .filter(Boolean).join(", ") || undefined };
    case "CLASS_SERIES_UPDATED": {
      const changed = Array.isArray(d.changed) ? d.changed.map(String) : [];
      const parts = [changed.includes("time") && typeof d.startTime === "string" && typeof d.endTime === "string" ? `time ${d.startTime}–${d.endTime}` : "",
        changed.includes("title") ? "title" : "", changed.includes("webinar") ? "Zoom webinar" : ""].filter(Boolean);
      return { summary: `Changed ${count(d.classes, "class", "classes")} of a weekly series`,
        detail: [typeof d.fromDate === "string" ? `From ${formatDate(d.fromDate)}` : "", parts.length ? `changed ${parts.join(", ")}` : ""]
          .filter(Boolean).join("; ") || undefined };
    }
    case "CLASS_SERIES_CANCELLED": return { summary: `Cancelled ${count(d.cancelled, "class", "classes")} of a weekly series`,
      detail: typeof d.fromDate === "string" ? `From ${formatDate(d.fromDate)}` : undefined };
    case "ZOOM_OCCURRENCE_LINKED": return { summary: `Linked a Zoom occurrence to ${event.targetLabel ?? "a class"}`,
      detail: typeof d.reason === "string" ? `Reason: ${d.reason}` : undefined };
    case "ZOOM_OCCURRENCE_MARKED_NOT_CLASS": return { summary: "Marked a Zoom occurrence as not a class",
      detail: typeof d.reason === "string" ? `Reason: ${d.reason}` : undefined };
    case "ATTENDANCE_EXPORTED": return { summary: d.scope === "batch" ? `Exported attendance of batch ${event.targetLabel ?? ""}`.trim()
        : `Exported attendance of ${event.targetLabel ? `“${event.targetLabel}”` : "a class"}`,
      detail: [d.scope === "batch" && typeof d.from === "string" && typeof d.to === "string" ? `${formatDate(d.from)} – ${formatDate(d.to)}` : "",
        typeof d.rows === "string" ? `${d.rows} row${d.rows === "1" ? "" : "s"}` : ""].filter(Boolean).join(" · ") || undefined };
    case "ATTENDANCE_FINALISED": return { summary: `Finalised attendance of ${event.targetLabel ? `“${event.targetLabel}”` : "a class"}`,
      detail: [`${d.present ?? 0} present`, `${d.late ?? 0} late`, `${d.absent ?? 0} absent`].join(", ") + (typeof d.rule === "string" ? ` (rule ${d.rule})` : "") };
    case "ENGAGEMENT_RANKING_EXPORTED": return { summary: "Downloaded an engagement ranking",
      detail: typeof d.from === "string" && typeof d.to === "string" ? `${formatDate(d.from)} – ${formatDate(d.to)}` : undefined };
    case "PASSWORD_CHANGED": return { summary: "Changed their password" };
    case "STUDENT_ERASED": return { summary: `Erased a student’s personal data${typeof d.placeholder === "string" ? ` (now ${d.placeholder})` : ""}`,
      detail: typeof d.reason === "string" ? `Reason: ${d.reason}` : undefined };
    case "ATTENDANCE_RECALCULATED": return { summary: `Recalculated attendance of ${event.targetLabel ? `“${event.targetLabel}”` : "a class"}`,
      detail: `${d.changed ?? 0} changed, ${d.kept ?? 0} corrected kept, ${d.added ?? 0} added` + (typeof d.reason === "string" ? ` · Reason: ${d.reason}` : "") };
    case "ATTENDANCE_CORRECTED": return { summary: `Corrected attendance in ${event.targetLabel ? `“${event.targetLabel}”` : "a class"}`,
      detail: [typeof d.from === "string" && typeof d.to === "string" ? `${d.from.toLowerCase()} → ${d.to.toLowerCase()}` : "",
        typeof d.reason === "string" ? `Reason: ${d.reason}` : ""].filter(Boolean).join(" · ") || undefined };
    case "ATTENDANCE_POLICY_SET": return { summary: "Changed the attendance rule",
      detail: `${d.minConnectedPercent}% connected, late after ${d.lateAfterMinutes} min` + (typeof d.previous === "string" ? ` (was ${d.previous})` : "") };
    case "ATTENDANCE_APPEAL_SUBMITTED": return { summary: "A student appealed their attendance" };
    case "ATTENDANCE_APPEAL_ACCEPTED": return { summary: "Attendance appeal accepted", detail: typeof d.note === "string" ? `Note: ${d.note}` : undefined };
    case "ATTENDANCE_APPEAL_REJECTED": return { summary: "Attendance appeal rejected", detail: typeof d.note === "string" ? `Note: ${d.note}` : undefined };
    case "ZOOM_IDENTITY_LINKED": return { summary: `Matched a Zoom account to student ${event.targetLabel ?? ""}`.trim(),
      detail: [typeof d.email === "string" ? d.email : "", typeof d.reason === "string" ? `Reason: ${d.reason}` : ""].filter(Boolean).join(" · ") || undefined };
    case "ZOOM_IDENTITY_DECISION_REMOVED": return { summary: "Removed a Zoom account decision",
      detail: [typeof d.email === "string" ? d.email : "", typeof d.reason === "string" ? `Reason: ${d.reason}` : ""].filter(Boolean).join(" · ") || undefined };
    case "ZOOM_IDENTITY_MARKED_NOT_STUDENT": return { summary: "Marked a Zoom account as not a student",
      detail: [typeof d.email === "string" ? d.email : "", typeof d.reason === "string" ? `Reason: ${d.reason}` : ""].filter(Boolean).join(" · ") || undefined };
    case "ZOOM_RECEIPT_REPLAYED": return { summary: "Replayed a failed Zoom event", detail: typeof d.reason === "string" ? `Reason: ${d.reason}` : undefined };
    case "ZOOM_LINKS_REVISED": return { summary: `Zoom links re-checked after ${event.targetLabel ? `“${event.targetLabel}”` : "a class"} changed`,
      detail: [Number(d.unlinked) ? `${d.unlinked} back to review` : "", Number(d.moved) ? `${d.moved} moved` : "", Number(d.linked) ? `${d.linked} linked` : ""]
        .filter(Boolean).join(", ") || undefined };
    default: return { summary: event.action.toLowerCase().replace(/_/g, " ").replace(/^./, c => c.toUpperCase()) };
  }
}

/** Links to the activity page keep the area and record filters. */
export function activityHref(options: { category?: string; target?: string | null; before?: string | null }) {
  const params = new URLSearchParams();
  if (options.category) params.set("category", options.category);
  if (options.target) params.set("target", options.target);
  if (options.before) params.set("before", options.before);
  const query = params.toString();
  return `/staff/activity${query ? `?${query}` : ""}`;
}
