/** Pure helpers for staff screens: shared by server actions, components and unit tests. */

export const managerRoles = ["ACADEMY_OWNER", "ACADEMY_ADMIN"];

export function isAcademyManager(roles: readonly string[]) {
  return roles.some(role => managerRoles.includes(role));
}

/**
 * What a form action reports back to the form that called it. React resets a form
 * after its action finishes, so on a refusal the submitted text comes back in
 * {@code values} and becomes the fields' defaults; after a success the fields clear.
 */
export type FormState = { ok: boolean; message: string; values?: Record<string, string> } | null;

/** The text fields of a submission, to hand back to the form after a refusal. */
export function submittedValues(form: FormData, names: readonly string[]): Record<string, string> {
  return Object.fromEntries(names.map(name => {
    const value = form.get(name);
    return [name, typeof value === "string" ? value.slice(0, 300) : ""];
  }));
}

const uuidShape = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Ids from hidden form fields are untrusted input: only well-formed UUIDs may reach an API path. */
export function uuidOrNull(value: FormDataEntryValue | null): string | null {
  return typeof value === "string" && uuidShape.test(value) ? value : null;
}

const codeShape = /^[A-Za-z0-9][A-Za-z0-9._-]{0,59}$/;
const dateShape = /^\d{4}-\d{2}-\d{2}$/;

function text(form: FormData, name: string) {
  const value = form.get(name);
  return typeof value === "string" ? value.trim() : "";
}

export type CourseInput = { code: string; title: string };
export type BatchInput = { courseId: string; code: string; name: string; startsOn: string; endsOn: string | null };

/** The same rules the API applies, checked first so people get an immediate, specific message. */
export function parseCourseForm(form: FormData): CourseInput | { error: string } {
  const code = text(form, "code");
  const title = text(form, "title");
  if (!codeShape.test(code)) return { error: "Use 1 to 60 letters, digits, dots, hyphens or underscores for the course code." };
  if (!title || title.length > 200) return { error: "Enter a course title of up to 200 characters." };
  return { code, title };
}

export function parseBatchForm(form: FormData): BatchInput | { error: string } {
  const courseId = uuidOrNull(form.get("courseId"));
  const code = text(form, "code");
  const name = text(form, "name");
  const startsOn = text(form, "startsOn");
  const endsOn = text(form, "endsOn") || null;
  if (!courseId) return { error: "Choose the course this batch belongs to." };
  if (!codeShape.test(code)) return { error: "Use 1 to 60 letters, digits, dots, hyphens or underscores for the batch code." };
  if (!name || name.length > 200) return { error: "Enter a batch name of up to 200 characters." };
  if (!dateShape.test(startsOn)) return { error: "Enter the date the batch starts." };
  if (endsOn && !dateShape.test(endsOn)) return { error: "Enter a valid end date or leave it empty." };
  if (endsOn && endsOn < startsOn) return { error: "A batch cannot end before it starts." };
  return { courseId, code, name, startsOn, endsOn };
}

/**
 * Turns an API refusal into a sentence for the person. The API writes its problem
 * details for callers, so a specific detail is shown as is; generic ones are replaced.
 */
export function problemMessage(status: number, detail: string | undefined) {
  if (status === 403) return "Only academy owners and administrators can make this change.";
  if (status === 404) return "That record no longer exists. Reload the page.";
  if (status === 409 && detail?.includes("changed by someone else")) {
    return "Someone else changed this record. Reload the page and try again.";
  }
  if ((status === 400 || status === 409) && detail && !/^(Invalid request content|Failed to read request|Bad Request)/i.test(detail)) {
    return detail;
  }
  if (status === 400) return "Some of the details are not valid. Check them and try again.";
  return "We couldn’t save that right now. Please try again shortly.";
}

/** "2027-01-10" → "10 Jan 2027", without time-zone shifts. */
export function formatDate(isoDate: string | null) {
  if (!isoDate) return "";
  const [year, month, day] = isoDate.split("-").map(Number);
  const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  return `${day} ${months[month - 1]} ${year}`;
}

const emailShape = /^[^\s@]{1,64}@[^\s@.]+(\.[^\s@.]+)+$/;

export type StudentInput = { studentNumber: string; fullName: string; email: string };

export function parseStudentForm(form: FormData): StudentInput | { error: string } {
  const studentNumber = text(form, "studentNumber");
  const fullName = text(form, "fullName");
  const email = text(form, "email");
  if (!codeShape.test(studentNumber)) return { error: "Use 1 to 60 letters, digits, dots, hyphens or underscores for the student number." };
  if (!fullName || fullName.length > 200) return { error: "Enter the student’s full name, up to 200 characters." };
  if (email.length > 254 || !emailShape.test(email)) return { error: "Enter a valid email address." };
  return { studentNumber, fullName, email };
}

export function parseEnrollForm(form: FormData): { batchId: string; enrolledOn: string } | { error: string } {
  const batchId = uuidOrNull(form.get("batchId"));
  const enrolledOn = text(form, "enrolledOn");
  if (!batchId) return { error: "Choose a batch." };
  if (!dateShape.test(enrolledOn)) return { error: "Enter the enrolment date." };
  return { batchId, enrolledOn };
}

export function isIsoDate(value: unknown): value is string {
  return typeof value === "string" && dateShape.test(value);
}

export const accountStatusLabels: Record<string, string> = {
  PENDING_ACTIVATION: "Invitation pending",
  ACTIVE: "Account active",
  DISABLED: "Account disabled",
};

/** Today's date at the academy (Asia/Colombo), for date inputs' defaults. */
export function academyToday(now = new Date()) {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}

/** Links between student list pages; `after` is the API's keyset cursor. */
export function studentListHref(options: { after?: string | null; status?: string }) {
  const params = new URLSearchParams();
  if (options.status && options.status !== "active") params.set("status", options.status);
  if (options.after) params.set("after", options.after);
  const query = params.toString();
  return `/staff/students${query ? `?${query}` : ""}`;
}

export const staffRoleLabels: Record<string, string> = {
  ACADEMY_OWNER: "Owner",
  ACADEMY_ADMIN: "Administrator",
  INSTRUCTOR: "Instructor",
  ATTENDANCE_OFFICER: "Attendance officer",
  ANALYST: "Analyst",
};
const elevatedRoles = ["ACADEMY_OWNER", "ACADEMY_ADMIN"];

/** Roles in a fixed, senior-first order (the API returns them alphabetically). */
export function roleList(roles: readonly string[]) {
  const order = Object.keys(staffRoleLabels);
  return [...roles].sort((a, b) => order.indexOf(a) - order.indexOf(b)).map(role => staffRoleLabels[role] ?? role).join(", ");
}

/** Roles the caller may grant: only owners grant owner or administrator. The API enforces the same rule. */
export function grantableRoles(callerRoles: readonly string[]) {
  return Object.keys(staffRoleLabels).filter(role => !elevatedRoles.includes(role) || callerRoles.includes("ACADEMY_OWNER"));
}

/** Whether the caller may change this staff member at all: themselves never, an owner or administrator only as an owner. */
export function canManageStaff(caller: { accountId: string; roles: readonly string[] }, target: { id: string; roles: readonly string[] }) {
  if (caller.accountId === target.id || !isAcademyManager(caller.roles)) return false;
  return !target.roles.some(role => elevatedRoles.includes(role)) || caller.roles.includes("ACADEMY_OWNER");
}

export function parseRoles(form: FormData): string[] | { error: string } {
  const roles = form.getAll("roles").filter((role): role is string => typeof role === "string");
  if (roles.length === 0) return { error: "Choose at least one role." };
  if (roles.some(role => !(role in staffRoleLabels)) || new Set(roles).size !== roles.length) return { error: "Choose roles from the list." };
  return roles;
}

export function parseStaffForm(form: FormData): { email: string; roles: string[] } | { error: string } {
  const email = text(form, "email");
  if (email.length > 254 || !emailShape.test(email)) return { error: "Enter a valid email address." };
  const roles = parseRoles(form);
  return "error" in roles ? roles : { email, roles };
}

export function parseAssignForm(form: FormData): { email: string; role: string } | { error: string } {
  const email = text(form, "email");
  const role = text(form, "role");
  if (email.length > 254 || !emailShape.test(email)) return { error: "Enter the staff member’s email address." };
  if (role !== "INSTRUCTOR" && role !== "ATTENDANCE_OFFICER") return { error: "Choose instructor or attendance officer." };
  return { email, role };
}
