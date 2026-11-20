import ExcelJS from "exceljs";

type Cell = string | number | null;
type Column = { header: string; width: number; percent?: boolean };

/** User-provided text is assigned as a string cell, never as a formula or hyperlink. */
export function addReportSheet(workbook: ExcelJS.Workbook, name: string, columns: Column[], rows: Cell[][]) {
  const sheet = workbook.addWorksheet(name, { views: [{ state: "frozen", ySplit: 1 }] });
  sheet.columns = columns.map(column => ({
    header: column.header, width: column.width, ...(column.percent ? { style: { numFmt: "0%" } } : {}),
  }));
  sheet.getRow(1).font = { bold: true, color: { argb: "FFFFFFFF" } };
  sheet.getRow(1).fill = { type: "pattern", pattern: "solid", fgColor: { argb: "FF17394F" } };
  sheet.getRow(1).height = 23;
  sheet.addRows(rows);
  if (rows.length > 0) sheet.autoFilter = { from: { row: 1, column: 1 }, to: { row: rows.length + 1, column: columns.length } };
}
