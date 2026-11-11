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
