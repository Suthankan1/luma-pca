import { isRealDate } from "./staff-forms.ts";
import { classMonthRange, isMonthKey, shiftMonth } from "./staff-home.ts";

export function reportPeriod(month: unknown, endMonth: unknown, today: string) {
  const currentMonth = today.slice(0, 7);
  const first = month === undefined || month === null ? currentMonth : month;
  const last = endMonth === undefined || endMonth === null || endMonth === "" ? first : endMonth;
  if (!isMonthKey(first) || !isMonthKey(last) || !isRealDate(`${first}-01`) || !isRealDate(`${last}-01`)
      || first > last || last > currentMonth || last > shiftMonth(first, 11)) return null;
  const months = [];
  for (let key = first; key <= last; key = shiftMonth(key, 1)) months.push(classMonthRange(key, today));
  return { first, last, months, from: months[0].first, to: months[months.length - 1].through! };
}

export type ReportPeriod = NonNullable<ReturnType<typeof reportPeriod>>;

export const reportKinds = ["session", "class-type", "student"] as const;
export type ReportKind = typeof reportKinds[number];

export function isReportKind(value: string | null): value is ReportKind {
  return reportKinds.some(kind => kind === value);
}
