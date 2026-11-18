/** Pure helpers for the Academy classes screen: shared by its server actions, components and unit tests. */

export type LinkedBatch = {
  id: string; courseId: string; code: string; name: string; active: boolean;
  lastSyncedAt: string | null; lastSyncedStudents: number | null;
};
export type AcademyClass = { className: string; studentCount: number; batch: LinkedBatch | null };

export type SyncResult = {
  academyStudents: number; created: number; enrolled: number; alreadyEnrolled: number;
  withoutName: number; withoutEmail: number;
  withdrawnInLuma: string[]; inactiveInLuma: string[]; invalidIds: string[]; notInAcademyClass: string[];
};

export type AcademyActionState = { ok: boolean; message: string; sync?: SyncResult } | null;

/** The course that holds batches created for Academy classes. */
export const ACADEMY_COURSE = { code: "ACADEMY", title: "Academy classes" } as const;

/** Academy class labels are free text; batch codes allow 1-60 letters, digits, dots, hyphens and underscores. */
export function batchCodeFor(className: string, taken: readonly string[]): string {
  const base = className.normalize("NFKD").replace(/[^A-Za-z0-9]+/g, "-").replace(/^-+|-+$/g, "").toUpperCase().slice(0, 54)
    || "CLASS";
  const used = new Set(taken.map(code => code.toUpperCase()));
  if (!used.has(base)) return base;
  for (let i = 2; ; i++) {
    const candidate = `${base}-${i}`;
    if (!used.has(candidate)) return candidate;
  }
}

/** A class name from a hidden field: what the API accepts (1-200 characters after trimming). */
export function classNameOrNull(value: FormDataEntryValue | null): string | null {
  if (typeof value !== "string") return null;
  const name = value.trim();
  return name && name.length <= 200 ? name : null;
}

/** "Created 3 new students and enrolled 5 (2 were already enrolled)." */
export function syncSummary(result: SyncResult): string {
  const parts = [`${result.academyStudents} student${result.academyStudents === 1 ? "" : "s"} in the Academy class`];
  if (result.created) parts.push(`${result.created} new to LUMA`);
  parts.push(`${result.enrolled} enrolled now`);
  if (result.alreadyEnrolled) parts.push(`${result.alreadyEnrolled} already enrolled`);
  return `Synced: ${parts.join(" · ")}.`;
}

/** "2026-11-18T09:46:39Z" → "18 Nov 2026, 15:16" in the Academy's time zone. */
export function formatSyncedAt(instant: string | null): string {
  if (!instant) return "never";
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) return "never";
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", day: "numeric", month: "short", year: "numeric",
    hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}
