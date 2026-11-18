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
export function studentListHref(options: { after?: string | null; status?: string; q?: string | null }) {
  const params = new URLSearchParams();
  if (options.q) params.set("q", options.q);
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

// ---- Class sessions -------------------------------------------------------------
// The API stores instants (UTC) plus the IANA zone the class is taught in. People type
// a date and clock times in that zone; these helpers convert both ways with Intl only.

export const ACADEMY_TIME_ZONE = "Asia/Colombo";
const timeShape = /^([01]\d|2[0-3]):[0-5]\d$/;
const MAX_CLASS_MINUTES = 12 * 60;
const MAX_CLASS_BATCHES = 20;

export function isTimeZone(zone: string) {
  try {
    new Intl.DateTimeFormat("en", { timeZone: zone });
    return /^[A-Za-z]+\/[A-Za-z0-9_+\-/]+$/.test(zone);
  } catch {
    return false;
  }
}

/** Wall-clock parts of an instant in a zone. */
function wallClock(epochMs: number, zone: string) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    timeZone: zone, hourCycle: "h23", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).formatToParts(new Date(epochMs)).map(p => [p.type, p.value]));
  return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}`,
    asUtc: Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute), Number(parts.second)) };
}

/** "2027-01-11" + "18:00" in Asia/Colombo → "2027-01-11T12:30:00.000Z". Times skipped by a DST change resolve forward. */
export function zonedToInstant(date: string, time: string, zone: string) {
  const [y, m, d] = date.split("-").map(Number);
  const [h, mi] = time.split(":").map(Number);
  const wanted = Date.UTC(y, m - 1, d, h, mi);
  let guess = wanted - (wallClock(wanted, zone).asUtc - wanted);
  guess += wanted - wallClock(guess, zone).asUtc;
  return new Date(guess).toISOString();
}

/** An instant's date and clock time in a zone, for form defaults. */
export function instantParts(iso: string, zone: string) {
  const { date, time } = wallClock(Date.parse(iso), zone);
  return { date, time };
}

/** "Mon 11 Jan 2027 · 18:00–20:00" in the class's own zone (24-hour clock). */
/** The class-length limit a live Zoom run's class ends at until Zoom says it ended (backend ZoomRunSessions.LIVE_SPAN). */
export const LIVE_RUN_SPAN_MS = 12 * 3600_000;

/** A Zoom run still going on: its end is only the class-length limit, not a real end time. */
export function isLiveRun(c: { origin?: string | null; startsAt: string; endsAt: string }, now = Date.now()) {
  return c.origin === "ZOOM_RUN" && Date.parse(c.endsAt) - Date.parse(c.startsAt) === LIVE_RUN_SPAN_MS && now < Date.parse(c.endsAt);
}

/** "Wed 18 Nov 2026 · 16:00–17:40", or for a live Zoom run "Wed 18 Nov 2026 · live since 16:00" (no invented end). */
export function classTimeLabel(c: { origin?: string | null; startsAt: string; endsAt: string; timeZone: string }, now = Date.now()) {
  if (!isLiveRun(c, now)) return formatClassTime(c.startsAt, c.endsAt, c.timeZone);
  const start = instantParts(c.startsAt, c.timeZone);
  const weekday = new Intl.DateTimeFormat("en-GB", { timeZone: c.timeZone, weekday: "short" }).format(new Date(c.startsAt));
  return `${weekday} ${formatDate(start.date)} · live since ${start.time}`;
}

export function formatClassTime(startsAt: string, endsAt: string, zone: string) {
  const start = instantParts(startsAt, zone);
  const end = instantParts(endsAt, zone);
  const weekday = new Intl.DateTimeFormat("en-GB", { timeZone: zone, weekday: "short" }).format(new Date(startsAt));
  const endText = end.date === start.date ? end.time : `${formatDate(end.date)} ${end.time}`;
  return `${weekday} ${formatDate(start.date)} · ${start.time}–${endText}`;
}

/** Calendar arithmetic on "YYYY-MM-DD" strings. */
export function addDays(isoDate: string, days: number) {
  const [y, m, d] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/** A "YYYY-MM-DD" string naming a day that exists (not 30 February). */
export function isRealDate(value: unknown): value is string {
  return isIsoDate(value) && addDays(value, 0) === value;
}

export const SESSION_WINDOW_DAYS = 14;

/** The fortnight of classes shown: from the given academy date (default today) for 14 days. */
export function sessionWindow(fromParam: unknown, today = academyToday(), days = SESSION_WINDOW_DAYS) {
  const from = isRealDate(fromParam) ? fromParam : today;
  const to = addDays(from, days);
  return { from, to, lastDay: addDays(to, -1), previous: addDays(from, -days), next: to,
    fromInstant: zonedToInstant(from, "00:00", ACADEMY_TIME_ZONE), toInstant: zonedToInstant(to, "00:00", ACADEMY_TIME_ZONE) };
}

export function sessionListHref(options: { from?: string | null; batch?: string | null }) {
  const params = new URLSearchParams();
  if (options.from) params.set("from", options.from);
  if (options.batch) params.set("batch", options.batch);
  const query = params.toString();
  return `/staff/sessions${query ? `?${query}` : ""}`;
}

export type SessionInput = { title: string; startsAt: string; endsAt: string; timeZone: string; batchIds: string[]; zoomWebinarId: string | null };

/** Same rules as the API (1–20 batches, at most 12 hours, webinar ID digits), checked first. Classes end on the day they start. */
export function parseSessionForm(form: FormData): SessionInput | { error: string } {
  const title = text(form, "title");
  const date = text(form, "date");
  const startTime = text(form, "startTime");
  const endTime = text(form, "endTime");
  const timeZone = text(form, "timeZone") || ACADEMY_TIME_ZONE;
  const rawBatches = form.getAll("batchIds");
  const batchIds = rawBatches.map(uuidOrNull);
  const webinar = text(form, "zoomWebinarId").replace(/[\s-]/g, "");
  if (!title || title.length > 200) return { error: "Enter a class title of up to 200 characters." };
  if (!isRealDate(date)) return { error: "Enter the date of the class." };
  if (!timeShape.test(startTime) || !timeShape.test(endTime)) return { error: "Enter start and end times such as 18:00." };
  if (endTime <= startTime) return { error: "The class must end after it starts, on the same day." };
  const [sh, sm] = startTime.split(":").map(Number);
  const [eh, em] = endTime.split(":").map(Number);
  if ((eh * 60 + em) - (sh * 60 + sm) > MAX_CLASS_MINUTES) return { error: "A class can last at most 12 hours." };
  if (!isTimeZone(timeZone)) return { error: "That request was not valid. Reload the page." };
  if (batchIds.length === 0) return { error: "Choose at least one batch." };
  if (batchIds.length > MAX_CLASS_BATCHES) return { error: `Choose at most ${MAX_CLASS_BATCHES} batches.` };
  if (batchIds.some(id => !id) || new Set(batchIds).size !== batchIds.length) return { error: "Choose batches from the list." };
  if (webinar && !/^\d{9,20}$/.test(webinar)) return { error: "Enter the Zoom webinar ID as digits, such as 812 3456 7890, or leave it empty." };
  return { title, startsAt: zonedToInstant(date, startTime, timeZone), endsAt: zonedToInstant(date, endTime, timeZone), timeZone,
    batchIds: batchIds as string[], zoomWebinarId: webinar || null };
}

export const WEEKDAYS = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;
export type SeriesInput = { title: string; firstDate: string; lastDate: string; weekdays: string[]; startTime: string; endTime: string;
  timeZone: string; batchIds: string[]; zoomWebinarId: string | null; skipDates: string[] };

/**
 * A weekly series from the form: the class fields plus first and last dates, weekdays and
 * dates to skip (holidays), typed as YYYY-MM-DD separated by commas, spaces or new lines.
 * The API re-checks everything and counts the classes.
 */
export function parseSeriesForm(form: FormData): SeriesInput | { error: string } {
  const single = new FormData();
  for (const key of ["title", "startTime", "endTime", "timeZone", "zoomWebinarId"]) single.set(key, String(form.get(key) ?? ""));
  for (const id of form.getAll("batchIds")) single.append("batchIds", id);
  single.set("date", text(form, "firstDate"));
  const parsed = parseSessionForm(single);
  if ("error" in parsed) return { error: parsed.error.replace("the date of the class", "the first date") };
  const firstDate = text(form, "firstDate");
  const lastDate = text(form, "lastDate");
  if (!isRealDate(lastDate) || lastDate < firstDate) return { error: "Enter a last date on or after the first date." };
  if (addDays(firstDate, 366) < lastDate) return { error: "A series can span at most a year." };
  const weekdays = form.getAll("weekdays").map(String);
  if (weekdays.length === 0 || weekdays.some(day => !(WEEKDAYS as readonly string[]).includes(day)) || new Set(weekdays).size !== weekdays.length) {
    return { error: "Choose at least one weekday." };
  }
  const skipDates: string[] = text(form, "skipDates").split(/[\s,;]+/).filter(Boolean);
  const badSkip: string | undefined = skipDates.find(date => !dateShape.test(date) || !isRealDate(date));
  if (badSkip) return { error: `“${badSkip.slice(0, 20)}” is not a date. Write dates to skip as YYYY-MM-DD.` };
  if (skipDates.length > 100) return { error: "List at most 100 dates to skip." };
  return { title: parsed.title, firstDate, lastDate, weekdays: WEEKDAYS.filter(day => weekdays.includes(day)),
    startTime: text(form, "startTime"), endTime: text(form, "endTime"), timeZone: parsed.timeZone, batchIds: parsed.batchIds,
    zoomWebinarId: parsed.zoomWebinarId, skipDates: [...new Set(skipDates)].sort() };
}

/** "81234567890" → "812 3456 7890", the way Zoom shows 11-digit IDs; other lengths in groups of four. */
export function formatWebinarId(id: string | null) {
  if (!id) return "";
  if (id.length === 11) return `${id.slice(0, 3)} ${id.slice(3, 7)} ${id.slice(7)}`;
  return id.replace(/(\d{4})(?=\d)/g, "$1 ");
}

export type ClassSession = { id: string; title: string; startsAt: string; endsAt: string; timeZone: string; localDate: string; status: string;
  zoomWebinarId: string | null; seriesId?: string | null; batches: { batchId: string; courseId: string; code: string; name: string }[]; version: number;
  /** LUMA (scheduled by staff before Zoom owned schedules) or ZOOM_RUN (an actual run of a monitored webinar). */
  origin?: "LUMA" | "ZOOM_RUN" };
export type CourseRef = { id: string; code: string };
type BatchRef = { id: string; courseId: string; code: string; name: string; active: boolean };

/** Batch choices labelled like the enrolment form, "COURSE · BATCH · name"; without course data, "BATCH · name". */
export function batchOptions(courses: CourseRef[], batches: BatchRef[], include: (batch: BatchRef) => boolean) {
  const courseCode = new Map(courses.map(course => [course.id, course.code]));
  return batches.filter(include).map(batch => {
    const course = courseCode.get(batch.courseId);
    return { id: batch.id, label: `${course ? `${course} · ` : ""}${batch.code} · ${batch.name}` };
  }).sort((a, b) => a.label.localeCompare(b.label));
}

// ---- Zoom review ------------------------------------------------------------------

export type Occurrence = { occurrenceUuid: string; webinarId: string; firstEventAt: string; lastEventAt: string; eventCount: number;
  classSessionId: string | null; linkSource: "AUTOMATIC" | "MANUAL" | null; linkedAt: string | null;
  ended?: boolean; reportStatus?: "FETCHED" | "FAILED" | "GAVE_UP" | null; reportFetchedAt?: string | null; reportJoinSessions?: number | null };

/** Where an occurrence's Zoom participant report stands, in words. */
export function reportState(o: Occurrence) {
  if (o.reportStatus === "FETCHED") return `Zoom report fetched${o.reportFetchedAt ? ` ${formatInstant(o.reportFetchedAt)}` : ""} · ${o.reportJoinSessions ?? 0} join session${o.reportJoinSessions === 1 ? "" : "s"}`;
  if (o.reportStatus === "FAILED") return "Zoom report not fetched yet (will retry)";
  if (o.reportStatus === "GAVE_UP") return "Zoom report could not be fetched after 6 tries: fetch it now";
  if (!o.ended) return "Zoom report available after the webinar ends";
  return o.classSessionId ? "Zoom report will be fetched automatically" : "Zoom report: link the occurrence to a class first, or fetch it now";
}
export type ZoomReceipt = { id: string; eventType: string; eventTs: number; status: string; attempts: number; lastError: string | null;
  receivedAt: string; nextAttemptAt: string | null; processedAt: string | null };

export type ZoomMatch = "STUDENT" | "NOT_A_STUDENT" | "UNMATCHED" | "HOST";
export type ZoomParticipant = { email: string | null; displayName: string | null; visits: number; openVisits: number;
  firstSeenAt: string; lastSeenAt: string; match: ZoomMatch; matchSource: "MANUAL" | "SIGN_IN_EMAIL" | "REGISTRATION_EMAIL" | null;
  studentId: string | null; studentNumber: string | null; studentName: string | null; expected: boolean | null };
export type OccurrenceParticipants = { occurrenceUuid: string; classSessionId: string | null; participants: ZoomParticipant[] };
export type UnmatchedZoomIdentity = { email: string; displayName: string | null; occurrences: number; lastSeenAt: string };

/** How a participant was matched, in words. */
export function participantMatchLabel(p: ZoomParticipant) {
  switch (p.match) {
    case "HOST": return "Host";
    case "UNMATCHED": return p.email ? "Not matched" : "No email from Zoom";
    case "NOT_A_STUDENT": return "Not a student";
    case "STUDENT": return p.matchSource === "MANUAL" ? "Student (matched by staff)"
      : p.matchSource === "REGISTRATION_EMAIL" ? "Student (registration email)" : "Student (sign-in email)";
  }
}

/** A Zoom email as the API stores it, or null when it is not one. */
export function zoomEmailOrNull(value: FormDataEntryValue | null) {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email.length <= 254 && /^[^\s@]+@[^\s@]+$/.test(email) ? email : null;
}

export type AttendanceOutcome = "PRESENT" | "LATE" | "ABSENT" | "EXCUSED";
export type AttendanceRow = { studentId: string; studentNumber: string; fullName: string; expected: boolean; seen: boolean; visits: number;
  openVisits: number; connectedSeconds: number; firstJoinAt: string | null; secondsAfterStart: number | null;
  recordId?: string | null; outcome?: AttendanceOutcome | null; recordVersion?: number | null };
export type AttendanceRecordView = { id: string; classSessionId: string; classTitle: string; classStartsAt: string; classEndsAt: string;
  timeZone: string; computedOutcome: AttendanceOutcome; outcome: AttendanceOutcome; connectedSeconds: number; firstJoinAt: string | null;
  secondsAfterStart: number | null; finalisedAt: string; version: number; appealStatus: "OPEN" | "ACCEPTED" | "REJECTED" | null };
export type AttendanceAppeal = { id: string; recordId: string; studentId: string; studentNumber: string; studentName: string; classTitle: string;
  classStartsAt: string; timeZone: string; outcome: AttendanceOutcome; message: string; status: "OPEN" | "ACCEPTED" | "REJECTED";
  createdAt: string; resolutionNote: string | null; resolvedAt: string | null };
export const attendanceOutcomes = ["PRESENT", "LATE", "ABSENT", "EXCUSED"] as const;
export function outcomeOrNull(value: FormDataEntryValue | null): AttendanceOutcome | null {
  return typeof value === "string" && (attendanceOutcomes as readonly string[]).includes(value) ? value as AttendanceOutcome : null;
}
export type AttendancePolicy = { minConnectedPercent: number; lateAfterMinutes: number; description: string; version: number; updatedAt: string; autoFinalise?: boolean };
export const outcomeLabels: Record<AttendanceOutcome, string> = { PRESENT: "Present", LATE: "Late", ABSENT: "Absent", EXCUSED: "Excused" };

/** The rule's numbers from a form, or an error to show. */
export function parsePolicyForm(form: FormData): { minConnectedPercent: number; lateAfterMinutes: number; description: string } | { error: string } {
  const percent = Number(text(form, "minConnectedPercent"));
  const late = Number(text(form, "lateAfterMinutes"));
  const description = text(form, "description");
  if (!Number.isInteger(percent) || percent < 1 || percent > 100) return { error: "The connected share must be a whole number from 1 to 100." };
  if (!Number.isInteger(late) || late < 0 || late > 240) return { error: "Lateness must be a whole number of minutes from 0 to 240." };
  if (description.length < 3 || description.length > 500) return { error: "Say where the rule was approved, in 3 to 500 characters." };
  return { minConnectedPercent: percent, lateAfterMinutes: late, description };
}
export type ProvisionalAttendance = { sessionId: string; status: "PROVISIONAL" | "RECONCILED"; classStartsAt: string; classEndsAt: string;
  finalisedAt?: string | null; linkedOccurrences: number; reportedOccurrences: number; webinarEnded: boolean; unmatchedPeople: number; calculatedAt: string; students: AttendanceRow[] };

/** "1 h 05 min", "45 min", "0 min"; whole minutes, rounded down. */
export function formatDuration(seconds: number) {
  const minutes = Math.max(0, Math.floor(seconds / 60));
  const hours = Math.floor(minutes / 60);
  return hours ? `${hours} h ${String(minutes % 60).padStart(2, "0")} min` : `${minutes} min`;
}

export const zoomEventLabels: Record<string, string> = {
  "webinar.started": "Webinar started",
  "webinar.ended": "Webinar ended",
  "webinar.participant_joined": "Participant joined",
  "webinar.participant_left": "Participant left",
};

/** "13 Jan 2027 18:02" at the academy (or another zone). */
export function formatInstant(iso: string, zone = ACADEMY_TIME_ZONE) {
  const { date, time } = instantParts(iso, zone);
  return `${formatDate(date)} ${time}`;
}

/** Zoom occurrence UUIDs are opaque strings (they may contain "/", "+" or "="); only their size and characters are checked. */
export function occurrenceIdOrNull(value: FormDataEntryValue | null) {
  return typeof value === "string" && /^[\x21-\x7e]{1,100}$/.test(value) ? value : null;
}

/** Decisions and replays are audited with their reason, so it is required; it must not identify people. */
export function parseReason(form: FormData): string | { error: string } {
  const reason = text(form, "reason");
  if (reason.length < 3 || reason.length > 300) return { error: "Give a reason of 3 to 300 characters. Don’t include names or emails." };
  return reason;
}

/** Where to see who joined: the live participants page, or the "Check a webinar" view for a run known only from Zoom's report. */
export function occurrencePeopleHref(o: Occurrence) {
  return o.eventCount === 0
    ? `/staff/zoom/check?${new URLSearchParams({ webinar: o.webinarId, run: o.occurrenceUuid })}`
    : `/staff/zoom/participants?${new URLSearchParams({ occurrence: o.occurrenceUuid })}`;
}

/** "6 events", or that the run is known from Zoom's report only. */
export function occurrenceSource(o: Occurrence) {
  return o.eventCount === 0 ? "from Zoom’s report" : `${o.eventCount} event${o.eventCount === 1 ? "" : "s"}`;
}

export function occurrenceState(occurrence: Occurrence) {
  if (!occurrence.linkSource) return "Needs review";
  if (!occurrence.classSessionId) return "Marked not a class";
  return occurrence.linkSource === "AUTOMATIC" ? "Linked automatically" : "Linked by an administrator";
}

const DAY_MS = 86_400_000;
const MAX_LIST_DAYS = 92;

/**
 * One class-list request covering a day either side of the shown occurrences. The API
 * lists at most 92 days, so a wider spread keeps the most recent end.
 */
export function candidateWindow(occurrences: readonly Occurrence[]) {
  if (occurrences.length === 0) return null;
  const times = occurrences.map(o => Date.parse(o.firstEventAt));
  const to = Math.max(...times) + DAY_MS;
  const from = Math.max(Math.min(...times) - DAY_MS, to - MAX_LIST_DAYS * DAY_MS);
  return { from: new Date(from).toISOString(), to: new Date(to).toISOString() };
}

/** Scheduled classes starting within a day of the occurrence, same webinar first, then nearest in time. */
export function candidateClasses(occurrence: Occurrence, sessions: readonly ClassSession[]) {
  const at = Date.parse(occurrence.firstEventAt);
  return sessions
    .filter(s => s.status === "SCHEDULED" && Math.abs(Date.parse(s.startsAt) - at) <= DAY_MS)
    .map(s => ({ session: s, sameWebinar: s.zoomWebinarId === occurrence.webinarId, distance: Math.abs(Date.parse(s.startsAt) - at) }))
    .sort((a, b) => Number(b.sameWebinar) - Number(a.sameWebinar) || a.distance - b.distance)
    .map(({ session, sameWebinar }) => ({ id: session.id,
      label: `${formatClassTime(session.startsAt, session.endsAt, session.timeZone)} · ${session.title}${sameWebinar ? " · same webinar" : ""}` }));
}

// ---- Engagement (006) ---------------------------------------------------------------------
export type EngagementSettings = { enabled: boolean; consentText: string; consentVersion: number; version: number; updatedAt: string };
export type MyConsent = { enabled: boolean; consentText: string | null; consentVersion: number; granted: boolean | null; leaderboardOptIn: boolean; current: boolean };
export type PulseView = { id: string; prompt: string; options: string[]; openedAt: string; closesAt: string; open: boolean; counts: number[] | null; myAnswer: number | null };
export type ClassEngagement = { sessionId: string; enabled: boolean; reactions: Record<string, number>; reactingStudents: number; pulses: PulseView[];
  source: string; classStartsAt: string; classEndsAt: string };
export type PendingClass = { sessionId: string; title: string; startsAt: string; endsAt: string; timeZone: string; linkedRuns: number;
  state: "READY" | "WAITING_FOR_REPORT" | "NO_ZOOM_RUN" };
export type PendingAttendance = { ruleRecorded: boolean; autoFinalise: boolean; classes: PendingClass[] };
export const pendingStateLabels: Record<PendingClass["state"], string> = {
  READY: "Ready to finalise", WAITING_FOR_REPORT: "Waiting for Zoom's report", NO_ZOOM_RUN: "No Zoom run linked" };
export type WebinarSummary = { webinarId: string; topic: string | null; nextStart: string | null; durationMinutes: number;
  timeZone: string | null; recurring: boolean; upcomingClasses: number; liveEvents: boolean };
export type UpcomingRun = { occurrenceId: string; startsAt: string; endsAt: string; classId: string | null };
export type UpcomingWebinar = { webinarId: string; topic: string | null; timeZone: string | null; recurring: boolean; runs: UpcomingRun[];
  note: string | null; liveEvents?: boolean };
/** Whether a class is still to come, running now, or over, at the moment of the request. */
export function classPhase(startsAt: string, endsAt: string, now = Date.now()): "upcoming" | "live" | "over" {
  return now < Date.parse(startsAt) ? "upcoming" : now < Date.parse(endsAt) ? "live" : "over";
}
export type AttendanceCounts = { present: number; late: number; absent: number; excused: number; ratePercent: number | null };
export type BatchAttendanceSummary = { from: string; to: string; finalClasses: number; classesNotFinal: number; totals: AttendanceCounts;
  students: { studentId: string; studentNumber: string; fullName: string; counts: AttendanceCounts }[]; method: string };
export type AcademyAttendanceSummary = { from: string; to: string; method: string;
  batches: { batchId: string; batchCode: string; batchName: string; courseCode: string; courseTitle: string; finalClasses: number;
    classesNotFinal: number; totals: AttendanceCounts }[] };
/** "83 %" or a dash when nothing was counted. */
export function rateLabel(counts: AttendanceCounts) {
  return counts.ratePercent == null ? "—" : `${counts.ratePercent}%`;
}
/** The first day of the academy month containing an ISO date. */
export function monthStart(isoDate: string) {
  return `${isoDate.slice(0, 7)}-01`;
}
export type Ranking = { limit: number; classes: number; studentsCounted: number; method: string;
  top: { rank: number; studentId: string; studentNumber: string | null; name: string | null; classes: number; reactions: number; pulseAnswers: number; score: number }[] };
export const reactionLabels: Record<string, string> = { UNDERSTOOD: "I understand", CONFUSED: "I’m confused", SLOW_DOWN: "Slow down", SPEED_UP: "Speed up" };

/** A pulse question from a form: prompt, 2–5 distinct options (one per line), minutes 1–10. */
export function parsePulseForm(form: FormData): { prompt: string; options: string[]; minutes: number } | { error: string } {
  const prompt = text(form, "prompt");
  const options = String(form.get("options") ?? "").split("\n").map(o => o.trim()).filter(Boolean);
  const minutes = Number(text(form, "minutes"));
  if (prompt.length < 3 || prompt.length > 300) return { error: "Write a question of 3 to 300 characters." };
  if (options.length < 2 || options.length > 5 || new Set(options).size !== options.length || options.some(o => o.length > 100)) {
    return { error: "Give 2 to 5 different options, one per line, each up to 100 characters." };
  }
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 10) return { error: "Keep it open for 1 to 10 minutes." };
  return { prompt, options, minutes };
}

// ---- Webinar lookup ------------------------------------------------------------------------
export type WebinarRun = { occurrenceUuid: string; startTime: string | null; reported: boolean; linkedSessionId: string | null };
export type WebinarLookup = { webinarId: string; topic: string | null; durationMinutes: number | null; timezone: string | null; runs: WebinarRun[] };
export type RunAttendee = { email: string | null; displayName: string | null; joinSessions: number; connectedSeconds: number;
  firstJoinAt: string; lastLeaveAt: string | null; match: ZoomMatch; matchSource: string | null; studentId: string | null;
  studentNumber: string | null; studentName: string | null };
export type RunAttendance = { webinarId: string; occurrenceUuid: string; startTime: string | null; endTime: string | null;
  joinSessions: number; linkedSessionId: string | null; attendees: RunAttendee[] };

/** Whether a class is running now or within ten minutes of its start or end (when students may react or answer). */
export function classOpenForEngagement(startsAt: string, endsAt: string, now = new Date()) {
  const margin = 10 * 60_000;
  return Date.parse(startsAt) - margin <= now.getTime() && now.getTime() <= Date.parse(endsAt) + margin;
}
