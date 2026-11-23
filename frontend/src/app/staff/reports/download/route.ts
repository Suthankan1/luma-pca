import ExcelJS from "exceljs";
import { requirePortal } from "@/lib/auth-server";
import { reportClassTotals, reportSessions, reportStudentSessions } from "@/lib/report-data";
import { isReportKind, reportPeriod, type ReportPeriod } from "@/lib/report-period";
import { addReportSheet } from "@/lib/report-workbook";
import { staffApi } from "@/lib/staff-api";
import { classTypeReportPdf, reportAttendanceStatus, sessionReportPdf, studentReportPdf } from "@/lib/staff-report-content";
import { buildStaffReportPdf, type StaffReportPdf } from "@/lib/staff-report-pdf";
import { ACADEMY_TIME_ZONE, academyToday, instantParts, uuidOrNull, type ProvisionalAttendance } from "@/lib/staff-forms";
import { averageClassAttendance, classTypeKey, compactClassTitle } from "@/lib/staff-home";
import { attendancePercentFor, completedForStudent, participationFor, type StudentDirectoryEntry } from "@/lib/student-month";

const noCache = { "Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff" };
const problem = (message: string, status: number) => new Response(message, { status, headers: noCache });
const unavailable = () => problem("The report data couldn’t load. Please try again.", 503);
const notFound = () => problem("The selected record wasn’t found in this report period.", 404);
const column = (header: string, width: number, percent = false) => ({ header, width, percent });
const fraction = (value: number | null) => value == null ? null : value / 100;

function summary(workbook: ExcelJS.Workbook, period: ReportPeriod, rows: (string | number | null)[][]) {
  addReportSheet(workbook, "Summary", [column("Measure", 30), column("Value", 60)], [
    ["From (Sri Lanka)", period.from], ["Through (Sri Lanka)", period.to], ...rows,
  ]);
}

export async function GET(request: Request) {
  const identity = await requirePortal("staff");
  const query = new URL(request.url).searchParams;
  const kind = query.get("kind");
  const selection = query.get("selection");
  const format = query.get("format") ?? "xlsx";
  if (format !== "pdf" && format !== "xlsx") return problem("Select PDF or Excel.", 400);
  const now = Date.now();
  const period = reportPeriod(query.get("month"), query.get("endMonth"), academyToday(new Date(now)));
  if (!period || !isReportKind(kind) || !selection || selection.length > 240) return problem("Select a report, record, and valid month range.", 400);
  if (kind !== "class-type" && !uuidOrNull(selection)) return problem("Select a valid record.", 400);

  const workbook = format === "xlsx" ? new ExcelJS.Workbook() : null;
  let pdf: StaffReportPdf | undefined;
  if (kind === "student") {
    const student = await staffApi<StudentDirectoryEntry>(`/api/v1/students/${selection}`);
    if (!student.ok) return student.status === 404 ? notFound() : unavailable();
    const sessions = await reportStudentSessions(selection, period);
    if (!sessions) return unavailable();
    const completed = [...new Map(sessions.map(session => [session.id, session])).values()]
      .filter(session => {
        const { date } = instantParts(session.startsAt, ACADEMY_TIME_ZONE);
        return date >= period.from && date <= period.to && completedForStudent(session, now);
      }).sort((a, b) => a.startsAt.localeCompare(b.startsAt));
    if (workbook) {
      const groups = new Map<string, { title: string; expected: number; joined: number }>();
      const totals = { expected: 0, joined: 0 };
      const rows = completed.map(session => {
        const key = classTypeKey(session.title);
        const title = compactClassTitle(session.title);
        const counts = participationFor(session, session);
        const group = groups.get(key) ?? { title, expected: 0, joined: 0 };
        group.expected += counts.expected;
        group.joined += counts.joined;
        totals.expected += counts.expected;
        totals.joined += counts.joined;
        groups.set(key, group);
        const { date, time } = instantParts(session.startsAt, ACADEMY_TIME_ZONE);
        return [date, time, title, counts.expected ? Math.round(session.connectedSeconds / 60) : null,
          reportAttendanceStatus(session.outcome, counts.expected > 0, counts.joined > 0)];
      });
      const overall = attendancePercentFor(totals);
      summary(workbook, period, [["Registration ID", student.data.studentNumber], ["Student", student.data.fullName],
        ["Tracked sessions", totals.expected], ["Sessions joined", totals.joined], ["Attendance", overall == null ? null : `${Math.round(overall)}%`]]);
      addReportSheet(workbook, "Class types", [column("Class type", 54), column("Tracked sessions", 18), column("Joined", 12), column("Attendance", 16, true)],
        [...groups.values()].map(group => [group.title, group.expected, group.joined, fraction(attendancePercentFor(group))]));
      addReportSheet(workbook, "Sessions", [column("Date", 14), column("Start time", 13), column("Class type", 54),
        column("Connected minutes", 20), column("Attendance status", 28)], rows);
    } else {
      pdf = studentReportPdf(period, student.data, completed);
    }
  } else {
    const sessions = await reportSessions(period, now);
    if (!sessions) return unavailable();
    if (kind === "session") {
      const session = sessions.find(item => item.id === selection);
      if (!session) return notFound();
      const response = await staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${session.id}/attendance`, undefined, 25000);
      if (!response.ok) return response.status === 404 ? notFound() : unavailable();
      const attendance = response.data;
      if (attendance.status === "NO_ATTENDEES") return notFound();
      if (workbook) {
        const expected = attendance.students.filter(row => row.expected);
        const joined = expected.filter(row => row.seen).length;
        const rate = attendance.linkedOccurrences > 0 || joined > 0 ? attendancePercentFor({ expected: expected.length, joined }) : null;
        const { date, time } = instantParts(session.startsAt, ACADEMY_TIME_ZONE);
        summary(workbook, period, [["Class", compactClassTitle(session.title)], ["Session date", date], ["Start time", time],
          ["Expected students", expected.length], ["Expected students who joined", joined],
          ["Attendance", rate == null ? null : `${Math.round(rate)}%`],
          ["Status", attendance.finalisedAt ? "Final" : "Provisional"]]);
        addReportSheet(workbook, "Students", [column("Registration ID", 22), column("Student", 34), column("Expected", 13),
          column("Joined", 12), column("Connected minutes", 20), column("Attendance status", 28)], attendance.students.map(row => [
          row.studentNumber, row.fullName, row.expected ? "Yes" : "No", row.seen ? "Yes" : "No",
          row.seen || attendance.linkedOccurrences > 0 || row.outcome ? Math.round(row.connectedSeconds / 60) : null,
          reportAttendanceStatus(row.outcome, row.seen || attendance.linkedOccurrences > 0, row.seen),
        ]));
      } else {
        pdf = sessionReportPdf(period, session, attendance);
      }
    } else {
      const group = sessions.filter(session => classTypeKey(session.title) === selection);
      if (!group.length) return notFound();
      const totals = await reportClassTotals(period, group);
      if (!totals || group.some(session => !totals.has(session.id))) return unavailable();
      if (workbook) {
        const average = averageClassAttendance(group, totals);
        summary(workbook, period, [["Class type", compactClassTitle(group[0].title)], ["Completed sessions", group.length],
          ["Average attendance", average == null ? null : `${average}%`]]);
        addReportSheet(workbook, "Sessions", [column("Date", 14), column("Start time", 13), column("Class", 54), column("Expected", 13),
          column("Joined", 12), column("Attendance", 16, true)], group.map(session => {
          const total = totals.get(session.id)!;
          const { date, time } = instantParts(session.startsAt, ACADEMY_TIME_ZONE);
          return [date, time, compactClassTitle(session.title), total.expected, total.joined, fraction(attendancePercentFor(total))];
        }));
      } else {
        pdf = classTypeReportPdf(period, group, totals);
      }
    }
  }
  const bytes = pdf ? await buildStaffReportPdf(pdf) : await workbook!.xlsx.writeBuffer();
  const filename = `luma-${kind}-${period.first}${period.first === period.last ? "" : `-to-${period.last}`}.${format}`;
  console.info("[luma] attendance report exported", {
    actorAccountId: identity.accountId, academyId: identity.academyId, kind, format, from: period.from, through: period.to,
  });
  return new Response(new Uint8Array(bytes), { headers: {
    ...noCache, "Content-Type": format === "pdf" ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "Content-Disposition": `attachment; filename="${filename}"`,
  } });
}
