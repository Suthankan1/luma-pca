import type { Dashboard } from "./insights.ts";
import { classTypeKey, compactClassTitle } from "./staff-home.ts";

export type StudentDirectoryEntry = { id: string; studentNumber: string; fullName: string };
export type Participation = { expected: number; joined: number };
export type MonthlyStudent = StudentDirectoryEntry & { types: Record<string, Participation> };

export function attendancePercentFor(counts: Participation) {
  return counts.expected ? counts.joined * 100 / counts.expected : null;
}

export function participationFor(
  session: { state: string },
  record: { visits: number; connectedSeconds: number; outcome: string | null },
): Participation {
  const joined = Number(record.visits > 0 || record.connectedSeconds > 0);
  return { joined, expected: Number(session.state !== "NO_WEBINAR" || joined > 0 || record.outcome != null) };
}

export function completedForStudent(session: { endsAt: string; state: string }, now: number) {
  return session.state !== "LIVE" && Date.parse(session.endsAt) <= now;
}

export function compileStudentMonth(directory: StudentDirectoryEntry[], dashboards: Dashboard[], now: number) {
  const students: MonthlyStudent[] = directory.map(({ id, studentNumber, fullName }) => ({ id, studentNumber, fullName, types: {} }));
  const byId = new Map(students.map(student => [student.id, student]));
  const labels = new Map<string, string>();
  for (const dashboard of dashboards) {
    const completed = dashboard.classes.flatMap((session, index) => {
      if (!completedForStudent(session, now)) return [];
      const key = classTypeKey(session.title);
      labels.set(key, compactClassTitle(session.title));
      return [{ session, key, index }];
    });
    for (const row of dashboard.students) {
      const student = byId.get(row.studentId);
      if (!student) continue;
      for (const { session, key, index } of completed) {
        const cell = row.cells[index];
        if (!cell) continue;
        const keyWithPrefix = `type:${key}`;
        const counts = student.types[keyWithPrefix] ??= { expected: 0, joined: 0 };
        const contribution = participationFor(session, cell);
        counts.expected += contribution.expected;
        counts.joined += contribution.joined;
      }
    }
  }
  return { students, classTypes: [...labels].sort((a, b) => a[1].localeCompare(b[1])).map(([key, label]) => ({ key: `type:${key}`, label })) };
}

export function studentRate(student: MonthlyStudent, type: string) {
  if (type) return student.types[type] ? attendancePercentFor(student.types[type]) : null;
  const totals = Object.values(student.types).reduce((sum, counts) => ({
    expected: sum.expected + counts.expected, joined: sum.joined + counts.joined,
  }), { expected: 0, joined: 0 });
  return attendancePercentFor(totals);
}
