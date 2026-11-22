import { join } from "node:path";
import PDFDocument from "pdfkit";

type CalendarDay = { date: string; count: number; accent?: "good" | "warning" | "neutral" };
type ReportMonth = { month: string; days: CalendarDay[] };
type ReportTable = { title: string; columns: { label: string; weight: number; align?: "left" | "right" | "center" }[]; rows: string[][] };

export type StaffReportPdf = {
  title: string;
  subject: string;
  periodLabel: string;
  metrics: { label: string; value: string }[];
  notes: string[];
  calendar: ReportMonth[];
  tables: ReportTable[];
};

const navy = "#122B46";
const teal = "#126968";
const ink = "#243A50";
const muted = "#49647A";
const border = "#DAE4EB";
const surface = "#F4F8FA";
const margin = 36;
const gutter = 12;
const fontDir = join(process.cwd(), "public", "report-fonts");
const monthName = new Intl.DateTimeFormat("en-GB", { month: "long", year: "numeric", timeZone: "UTC" });
const weekdays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const fills = { good: "#DDF3E9", warning: "#FFF0D8", neutral: "#E1EFF8" } as const;
const accents = { good: "#125841", warning: "#754900", neutral: "#21587E" } as const;

type TextStyle = {
  size?: number;
  color?: string;
  bold?: boolean;
  width?: number;
  align?: "left" | "right" | "center";
};

function fontFor(value: string, bold = false) {
  if (/\p{Script=Tamil}/u.test(value)) return "Tamil";
  if (/\p{Script=Sinhala}/u.test(value)) return "Sinhala";
  return bold ? "Helvetica-Bold" : "Helvetica";
}

const clean = (value: string) => value.replace(/[‐‑‒–—―]/g, "-").replace(/\s+/g, " ").trim();

/** Draw the supplied, already-authorized report as selectable text and vector shapes. */
export async function buildStaffReportPdf(report: StaffReportPdf): Promise<Buffer> {
  const doc = new PDFDocument({ size: "A4", margin, compress: true, autoFirstPage: false,
    info: { Title: clean(report.title), Author: "Physics Cube Academy", Subject: "Staff attendance report" } });
  const output = new Promise<Buffer>((resolve, reject) => {
    const chunks: Buffer[] = [];
    doc.on("data", (chunk: Buffer) => chunks.push(chunk));
    doc.once("end", () => resolve(Buffer.concat(chunks)));
    doc.once("error", reject);
  });
  doc.registerFont("Tamil", join(fontDir, "tamil.ttf"));
  doc.registerFont("Sinhala", join(fontDir, "sinhala.ttf"));

  let page = 0;
  let y = margin;
  const width = () => doc.page.width - margin * 2;
  const contentBottom = () => doc.page.height - margin - 30;
  const text = (raw: string, x: number, top: number, style: TextStyle = {}) => {
    const value = clean(raw);
    return doc.font(fontFor(value, style.bold)).fontSize(style.size ?? 10)
      .fillColor(style.color ?? ink).text(value, x, top, { width: style.width ?? width(), align: style.align, lineGap: 1.4 });
  };
  const measure = (raw: string, size: number, measureWidth: number, bold?: boolean) => {
    const value = clean(raw);
    return doc.font(fontFor(value, bold)).fontSize(size).heightOfString(value, { width: measureWidth, lineGap: 1.4 });
  };

  function newPage(first = false) {
    doc.addPage();
    page++;
    const footer = doc.page.height - margin - 12;
    doc.moveTo(margin, footer - 7).lineTo(margin + width(), footer - 7).lineWidth(0.6).strokeColor(border).stroke();
    text("LUMA  /  Physics Cube Academy  /  Staff attendance", margin, footer, { size: 8, color: muted });
    text(`Page ${page}`, margin, footer, { size: 8, color: muted, align: "right" });
    y = margin;
    if (!first) {
      const titleWidth = width() * 0.54;
      const labelWidth = width() - titleWidth - gutter;
      text(report.title, margin, y, { size: 14, bold: true, color: navy, width: titleWidth });
      text(report.periodLabel, margin + titleWidth + gutter, y + 2, { size: 8, color: muted, width: labelWidth, align: "right" });
      y += Math.max(measure(report.title, 14, titleWidth, true), measure(report.periodLabel, 8, labelWidth) + 2) + 13;
      doc.moveTo(margin, y).lineTo(margin + width(), y).lineWidth(0.6).strokeColor(border).stroke();
      y += 13;
    }
  }

  function reserve(space: number) {
    if (y + space > contentBottom()) newPage();
  }

  function heading(title: string) {
    text(title, margin, y, { size: 14, color: navy, bold: true });
    y += measure(title, 14, width(), true) + 8;
  }

  function calendarPanel(month: ReportMonth, x: number, top: number, panelWidth: number) {
    const first = new Date(`${month.month}-01T00:00:00Z`);
    const year = first.getUTCFullYear();
    const lastDay = new Date(Date.UTC(year, first.getUTCMonth() + 1, 0)).getUTCDate();
    const offset = (first.getUTCDay() + 6) % 7;
    const byDate = new Map(month.days.map(day => [day.date, day]));
    const cellWidth = (panelWidth - 16) / 7;
    doc.roundedRect(x, top, panelWidth, 154, 8).fill(surface);
    text(monthName.format(first), x + 10, top + 9, { size: 10.5, bold: true, color: navy, width: panelWidth - 20 });
    weekdays.forEach((day, index) => text(day, x + 8 + index * cellWidth, top + 28,
      { size: 7, width: cellWidth, color: muted, align: "center" }));
    for (let day = 1; day <= lastDay; day++) {
      const position = offset + day - 1;
      const left = x + 8 + position % 7 * cellWidth;
      const cellTop = top + 40 + Math.floor(position / 7) * 17;
      const count = byDate.get(`${month.month}-${String(day).padStart(2, "0")}`);
      if (count && count.count > 0) {
        const accent = count.accent ?? "neutral";
        doc.roundedRect(left + 1, cellTop, cellWidth - 2, 16, 3).fill(fills[accent]);
        text(String(day), left + 3, cellTop + 4, { size: 7, bold: true, width: cellWidth * 0.43, color: accents[accent] });
        text(`×${count.count}`, left + cellWidth * 0.36, cellTop + 4, {
          size: 6.5, width: cellWidth * 0.64 - 3, color: accents[accent], align: "right",
        });
      } else {
        text(String(day), left, cellTop + 4, { size: 7, width: cellWidth, color: muted, align: "center" });
      }
    }
  }

  function drawTable(table: ReportTable) {
    const weight = table.columns.reduce((total, column) => total + column.weight, 0);
    const widths = table.columns.map(column => width() * column.weight / weight);
    const padding = 6;
    const headerHeight = Math.max(...table.columns.map((column, index) =>
      measure(column.label, 8, widths[index] - 2 * padding, true))) + 2 * padding;

    function startTable(continued = false) {
      heading(continued ? `${table.title} (continued)` : table.title);
      doc.roundedRect(margin, y, width(), headerHeight, 4).fill(navy);
      let x = margin;
      for (const [index, column] of table.columns.entries()) {
        text(column.label, x + padding, y + padding, { size: 8, color: "#FFFFFF", bold: true,
          width: widths[index] - 2 * padding, align: column.align });
        x += widths[index];
      }
      y += headerHeight;
    }

    const rowHeight = (cells: string[]) => Math.max(11, ...table.columns.map((_, index) =>
      measure(cells[index] || "-", 8.5, widths[index] - 2 * padding))) + 2 * padding;

    reserve(measure(table.title, 14, width(), true) + 8 + headerHeight + (table.rows[0] ? rowHeight(table.rows[0]) : 25));
    startTable();
    if (!table.rows.length) {
      text("No records in this section.", margin + padding, y + padding, { size: 8.5, color: muted });
      y += 35;
      return;
    }
    for (const [rowIndex, cells] of table.rows.entries()) {
      const height = rowHeight(cells);
      if (y + height > contentBottom()) {
        newPage();
        startTable(true);
      }
      if (rowIndex % 2 === 0) doc.rect(margin, y, width(), height).fill(surface);
      let x = margin;
      for (const [index, column] of table.columns.entries()) {
        text(cells[index] || "-", x + padding, y + padding, { size: 8.5, width: widths[index] - 2 * padding, align: column.align });
        x += widths[index];
      }
      doc.moveTo(margin, y + height).lineTo(margin + width(), y + height).lineWidth(0.5).strokeColor(border).stroke();
      y += height;
    }
    y += 19;
  }

  newPage(true);
  const bannerWidth = width() - 40;
  const titleHeight = measure(report.title, 25, bannerWidth, true);
  const subjectHeight = measure(report.subject, 13, bannerWidth);
  const periodHeight = measure(report.periodLabel, 9, bannerWidth);
  const bannerHeight = 68 + titleHeight + subjectHeight + periodHeight;
  doc.roundedRect(margin, y, width(), bannerHeight, 11).fill(navy);
  text("PHYSICS CUBE ACADEMY", margin + 20, y + 16, { size: 8, bold: true, color: "#A3ECE2", width: bannerWidth });
  text(report.title, margin + 20, y + 34, { size: 25, bold: true, color: "#FFFFFF", width: bannerWidth });
  text(report.subject, margin + 20, y + 40 + titleHeight, { size: 13, color: "#EDF7FC", width: bannerWidth });
  text(report.periodLabel, margin + 20, y + 49 + titleHeight + subjectHeight, { size: 9, color: "#B9DEEC", width: bannerWidth });
  y += bannerHeight + 16;

  const metricColumns = Math.min(4, Math.max(1, report.metrics.length));
  const metricWidth = (width() - (metricColumns - 1) * gutter) / metricColumns;
  for (let index = 0; index < report.metrics.length; index += metricColumns) {
    const row = report.metrics.slice(index, index + metricColumns);
    const metricHeight = Math.max(...row.map(metric =>
      measure(metric.label, 8.5, metricWidth - 20, true) + measure(metric.value, 17, metricWidth - 20, true))) + 24;
    reserve(metricHeight);
    row.forEach(({ label, value }, column) => {
      const x = margin + column * (metricWidth + gutter);
      doc.roundedRect(x, y, metricWidth, metricHeight, 7).fill("#EAF5F6");
      text(label, x + 10, y + 9, { size: 8.5, bold: true, color: muted, width: metricWidth - 20 });
      text(value, x + 10, y + 14 + measure(label, 8.5, metricWidth - 20, true),
        { size: 17, bold: true, color: teal, width: metricWidth - 20 });
    });
    y += metricHeight + 9;
  }
  for (const note of report.notes) {
    const noteHeight = measure(note, 8.5, width());
    reserve(noteHeight);
    text(note, margin, y, { size: 8.5, color: muted });
    y += noteHeight + 4;
  }
  y += 9;

  if (report.calendar.length) {
    const panelWidth = report.calendar.length === 1 ? width() : (width() - gutter) / 2;
    for (let index = 0; index < report.calendar.length; index += 2) {
      const firstRow = index === 0;
      if (firstRow) {
        reserve(201);
        heading("Attendance calendar");
        text("Highlighted dates show the number of report sessions.", margin, y, { size: 8, color: muted });
        y += 16;
      } else if (y + 154 > contentBottom()) {
        newPage();
        heading("Attendance calendar (continued)");
      }
      report.calendar.slice(index, index + 2).forEach((month, column) =>
        calendarPanel(month, margin + column * (panelWidth + gutter), y, panelWidth));
      y += 154 + gutter;
    }
    y += 5;
  }
  for (const table of report.tables) drawTable(table);
  doc.end();
  return output;
}
