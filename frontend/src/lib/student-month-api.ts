import { type ApiResult, staffApi } from "./staff-api";
import { addDays } from "./staff-forms";
import type { StudentDirectoryEntry } from "./student-month";

type StudentPage = { items: StudentDirectoryEntry[]; nextAfter: string | null };

export async function readStudentDirectory() {
  const students: StudentDirectoryEntry[] = [];
  let after: string | null = null;
  do {
    const query = new URLSearchParams({ limit: "200" });
    if (after) query.set("after", after);
    const page: ApiResult<StudentPage> = await staffApi<StudentPage>(`/api/v1/students?${query}`, undefined, 25000);
    if (!page.ok || !page.data) return null;
    students.push(...page.data.items);
    if (page.data.nextAfter && page.data.nextAfter === after) return null;
    after = page.data.nextAfter;
  } while (after);
  return students;
}

/** Retry dense reports over smaller nonoverlapping date ranges to avoid silently losing older sessions. */
export async function readAttendanceWindows<T>(
  from: string, to: string, fetchWindow: (from: string, to: string) => Promise<ApiResult<T>>,
  isCapped: (response: T) => boolean,
): Promise<T[] | null> {
  const response = await fetchWindow(from, to);
  if (!response.ok || !response.data) return null;
  if (!isCapped(response.data)) return [response.data];
  if (from === to) return null;
  const day = 86400000;
  const days = (Date.parse(to) - Date.parse(from)) / day;
  const middle = addDays(from, Math.floor(days / 2));
  const older = await readAttendanceWindows(from, middle, fetchWindow, isCapped);
  if (!older) return null;
  const newer = await readAttendanceWindows(addDays(middle, 1), to, fetchWindow, isCapped);
  return newer ? [...older, ...newer] : null;
}
