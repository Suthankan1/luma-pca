import PDFDocument from "pdfkit";
import { join } from "node:path";
import { formatClock, formatDay, formatDuration, outcomeLabels, type ClassAttendance } from "@/lib/insights";
import { attendancePercentFor, participationFor } from "@/lib/student-month";
import { ACADEMY_TIME_ZONE, formatDate } from "@/lib/staff-forms";

const navy = "#122B46";
const teal = "#126968";
const ink = "#243A50";
const muted = "#49647A";
const margin = 42;
const publicDir = join(process.cwd(), "public");

type Report = {
  name: string; studentNumber: string; month: string; first: string; last: string; through: string | null;
  generatedAt: string; classes: ClassAttendance[];
};

function fontFor(value: string, bold: boolean) {
  if (/\p{Script=Tamil}/u.test(value)) return "Tamil";
  if (/\p{Script=Sinhala}/u.test(value)) return "Sinhala";
  return bold ? "Helvetica-Bold" : "Helvetica";
}

export async function buildStudentReportPdf(report: Report): Promise<Uint8Array<ArrayBuffer>> {
  const doc = new PDFDocument({ size: "A4", margin, compress: true, autoFirstPage: false,
    info: { Title: `Monthly attendance - ${report.month}`, Author: "Physics Cube Academy", Subject: "Student attendance report" } });
  const chunks: Buffer[] = [];
  const output = new Promise<Uint8Array<ArrayBuffer>>((resolve, reject) => {
    doc.on("data", (chunk: Buffer) => chunks.push(chunk));
    doc.once("end", () => resolve(new Uint8Array(Buffer.concat(chunks))));
    doc.once("error", reject);
  });
  doc.registerFont("Tamil", join(publicDir, "report-fonts/tamil.ttf"));
  doc.registerFont("Sinhala", join(publicDir, "report-fonts/sinhala.ttf"));

  let page = 0;
  let y = 0;
  const width = () => doc.page.width - 2 * margin;
  const text = (value: string, x: number, top: number, options: {
    size?: number; color?: string; bold?: boolean; width?: number; align?: "left" | "right";
  } = {}) => doc.font(fontFor(value, options.bold ?? false)).fontSize(options.size ?? 10)
    .fillColor(options.color ?? ink).text(value, x, top, { width: options.width ?? width(), align: options.align ?? "left" });
  const height = (value: string, size: number, measureWidth: number, bold = false) =>
    doc.font(fontFor(value, bold)).fontSize(size).heightOfString(value, { width: measureWidth });

  function newPage(first = false) {
    doc.addPage();
    page++;
    const bottom = doc.page.height - margin;
    doc.moveTo(margin, bottom - 24).lineTo(doc.page.width - margin, bottom - 24).strokeColor("#D5E3EA").stroke();
    text("Physics Cube Academy  /  Student attendance", margin, bottom - 17, { size: 8, color: muted });
    text(`Page ${page}`, margin, bottom - 17, { size: 8, color: muted, align: "right" });
    y = margin;
    if (!first) {
      text("Monthly attendance", margin, y, { size: 16, bold: true, color: navy });
      text(report.month, margin, y + 2, { size: 10, color: muted, align: "right" });
      y += 34;
    }
  }

  function ensureSpace(needed: number) {
    if (y + needed > doc.page.height - margin - 34) newPage();
  }

  newPage(true);
  const student = `${report.name}  /  ${report.studentNumber}`;
  const bannerHeight = 88 + height(student, 11, width() - 96);
  doc.roundedRect(margin, y, width(), bannerHeight, 12).fill(navy);
  doc.roundedRect(margin + 15, y + 15, 38, 38, 6).fill("#FFFFFF");
  doc.image(join(publicDir, "physics-cube-academy-logo.jpg"), margin + 18, y + 18, { fit: [32, 32] });
  text("PHYSICS CUBE ACADEMY", margin + 66, y + 14, { size: 8, bold: true, color: "#A3ECE2", width: width() - 84 });
  text("Monthly attendance", margin + 66, y + 29, { size: 24, bold: true, color: "#FFFFFF", width: width() - 84 });
  text(report.month, margin + 66, y + 60, { size: 12, color: "#BCEAE6", width: width() - 84 });
  text(student, margin + 66, y + 77, { size: 11, color: "#FFFFFF", width: width() - 96 });
  y += bannerHeight + 12;
  text(`${formatDate(report.first)} - ${formatDate(report.last)}  |  Sri Lanka time`, margin, y, { size: 9, color: muted });
  y += 14;
  text(report.through ? `Includes completed classes through ${formatDate(report.through)}.` : "This month has no completed classes yet.",
    margin, y, { size: 9, color: muted });
  y += 24;

  const totals = { joined: 0, expected: 0, connected: 0 };
  for (const session of report.classes) {
    const counts = participationFor(session, session);
    totals.joined += counts.joined;
    totals.expected += counts.expected;
    totals.connected += session.connectedSeconds;
  }
  const percent = attendancePercentFor(totals);
  const summary = [
    ["Attendance rate", percent == null ? "Unavailable" : `${Math.round(percent)}%`],
    ["Classes joined", `${totals.joined} / ${totals.expected}`],
    ["Connected time", formatDuration(totals.connected)],
  ];
  const card = (width() - 16) / 3;
  summary.forEach(([label, value], index) => {
    const x = margin + index * (card + 8);
    doc.roundedRect(x, y, card, 55, 8).fill("#EAF5F6");
    text(label, x + 10, y + 9, { size: 9, color: muted, width: card - 20 });
    text(value, x + 10, y + 25, { size: 16, bold: true, color: teal, width: card - 20 });
  });
  y += 72;
  text("Class attendance", margin, y, { size: 15, bold: true, color: navy });
  y += 21;
  text("Pending outcomes can change after reconciliation.", margin, y, { size: 9, color: muted });
  y += 22;
  if (!report.classes.length) text("No completed classes in the selected month.", margin, y, { color: muted });

  for (const session of report.classes) {
    const nameWidth = width() - 24;
    const nameHeight = height(session.title, 11, nameWidth, true);
    ensureSpace(nameHeight + 66);
    doc.roundedRect(margin, y, width(), nameHeight + 60, 7).fill("#F0F5F9");
    text(session.title, margin + 12, y + 7, { size: 11, bold: true, width: nameWidth });
    y += nameHeight + 11;
    const clock = (instant: string) => formatClock(instant, ACADEMY_TIME_ZONE);
    const duration = formatDuration(session.connectedSeconds);
    const recorded = participationFor(session, session);
    const outcome = session.outcome ? outcomeLabels[session.outcome] ?? session.outcome
      : !recorded.expected ? "Attendance unavailable" : recorded.joined ? "Joined - pending" : "No recorded join - pending";
    text(`${formatDay(session.startsAt, ACADEMY_TIME_ZONE)}  |  ${clock(session.startsAt)} - ${clock(session.endsAt)}`,
      margin + 12, y, { size: 9, color: muted, width: nameWidth });
    const ratio = recorded.expected ? ` (${Math.round(session.percent)}%)` : "";
    const visits = `${session.visits} ${session.visits === 1 ? "visit" : "visits"}`;
    text(`${outcome}  |  ${duration} connected of ${formatDuration(session.classSeconds)}${ratio}  |  ${visits}`,
      margin + 12, y + 13, { size: 9, bold: true, color: teal, width: nameWidth });
    text(session.firstJoinAt ? `First join ${clock(session.firstJoinAt)}  |  ${session.rejoins} rejoins` : "No first join recorded",
      margin + 12, y + 26, { size: 8, color: muted, width: nameWidth });
    y += 52;
    session.timeline.forEach((visit, index) => {
      ensureSpace(17);
      text(`Connection ${index + 1}    ${clock(visit.joinedAt)} - ${clock(visit.leftAt)}    ${formatDuration(visit.secondsInClass)}`,
        margin + 12, y, { size: 8, color: muted, width: nameWidth });
      y += 14;
    });
    y += 7;
  }

  y += 12;
  ensureSpace(24);
  text(`Generated ${formatDay(report.generatedAt, ACADEMY_TIME_ZONE)} at ${formatClock(report.generatedAt, ACADEMY_TIME_ZONE)}`,
    margin, y, { size: 8, color: muted });
  doc.end();
  return output;
}
