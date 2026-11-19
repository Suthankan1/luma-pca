import Link from "next/link";

export type AttendanceSection = "todo" | "reports" | "totals" | "settings";

/**
 * One place for attendance work, split by task: what needs doing now, reports per student, totals per batch, and the
 * rarely changed rule and engagement settings. Reports is its own page (/staff/dashboard); the rest are sections of
 * /staff/attendance.
 */
export function AttendanceTabs({ active, manager, analyst, todoCount }: {
  active: AttendanceSection; manager: boolean; analyst: boolean; todoCount?: number;
}) {
  const tabs: { key: AttendanceSection; label: string; href: string; show: boolean }[] = [
    { key: "todo", label: "To do", href: "/staff/attendance", show: manager },
    { key: "reports", label: "Reports", href: "/staff/dashboard", show: !analyst || manager },
    { key: "totals", label: "Batch totals", href: "/staff/attendance?section=totals", show: manager || analyst },
    { key: "settings", label: "Rule & settings", href: "/staff/attendance?section=settings", show: true },
  ];
  return <nav className="section-tabs" aria-label="Attendance sections">
    {tabs.filter(t => t.show).map(t => <Link key={t.key} href={t.href} className={`section-tab ${active === t.key ? "on" : ""}`}
      aria-current={active === t.key ? "page" : undefined}>
      {t.label}
      {t.key === "todo" && todoCount !== undefined && <span className={`count-badge ${todoCount ? "" : "zero"}`}
        aria-label={`${todoCount} waiting`}>{todoCount}</span>}
    </Link>)}
  </nav>;
}
