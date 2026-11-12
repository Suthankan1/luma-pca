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
    case "ZOOM_OCCURRENCE_LINKED": return { summary: `Linked a Zoom occurrence to ${event.targetLabel ?? "a class"}`,
      detail: typeof d.reason === "string" ? `Reason: ${d.reason}` : undefined };
    case "ZOOM_OCCURRENCE_MARKED_NOT_CLASS": return { summary: "Marked a Zoom occurrence as not a class",
      detail: typeof d.reason === "string" ? `Reason: ${d.reason}` : undefined };
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
