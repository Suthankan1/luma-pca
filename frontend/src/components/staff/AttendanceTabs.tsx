import Link from "next/link";

export type AttendanceSection = "todo" | "reports" | "totals" | "settings";

/**
 * One place for attendance work, split by task: what needs doing now, reports per student, totals per batch, and the
 * rarely changed rule and engagement settings. Reports is its own page (/staff/reports); the rest are sections of
 * /staff/attendance.
 */
export function AttendanceTabs({ active, todoCount }: { active: AttendanceSection; todoCount?: number }) {
  const tabs: { key: AttendanceSection; label: string; href: string }[] = [
    { key: "todo", label: "To do", href: "/staff/attendance" },
    { key: "reports", label: "Reports", href: "/staff/reports" },
    { key: "totals", label: "Batch totals", href: "/staff/attendance?section=totals" },
    { key: "settings", label: "Rule & settings", href: "/staff/attendance?section=settings" },
  ];
  return <nav className="section-tabs" aria-label="Attendance sections">
    {tabs.map(t => <Link key={t.key} href={t.href} className={`section-tab ${active === t.key ? "on" : ""}`}
      aria-current={active === t.key ? "page" : undefined}>
      {t.label}
      {t.key === "todo" && todoCount !== undefined && <span className={`count-badge ${todoCount ? "" : "zero"}`}
        aria-label={`${todoCount} waiting`}>{todoCount}</span>}
    </Link>)}
  </nav>;
}
