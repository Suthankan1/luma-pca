import Link from "next/link";
import { LinkPending } from "./LinkPending";
import type { ReactNode } from "react";

export const staffNav = [
  { key: "overview", label: "Today", href: "/staff" },
  { key: "dashboard", label: "Dashboard", href: "/staff/dashboard" },
  { key: "academy", label: "Academy classes", href: "/staff/academy" },
  { key: "courses", label: "Courses & batches", href: "/staff/courses" },
  { key: "sessions", label: "Classes", href: "/staff/sessions" },
  { key: "students", label: "Students", href: "/staff/students" },
  { key: "team", label: "Staff", href: "/staff/team" },
  { key: "attendance", label: "Attendance", href: "/staff/attendance" },
  { key: "zoom", label: "Zoom review", href: "/staff/zoom" },
  { key: "activity", label: "Activity log", href: "/staff/activity" },
] as const;

export type StaffSection = (typeof staffNav)[number]["key"];

/** Staff layout from the Figma prototype: navy rail with numbered sections, breadcrumb, heading, subtitle. */
export function StaffShell({ active, crumb, title, subtitle, hasStudentPortal, children }: {
  active: StaffSection;
  crumb: string;
  title: string;
  subtitle: string;
  hasStudentPortal?: boolean;
  children: ReactNode;
}) {
  return <div className="shell">
    <aside className="aside staff-rail" aria-label="Staff navigation">
      <Link href="/staff" className="brand"><span className="brand-dot" aria-hidden /> luma</Link>
      <nav className="staff-nav">
        {staffNav.map((item, index) => <Link key={item.key} href={item.href}
          className={`staff-navitem ${item.key === active ? "active" : ""}`} aria-current={item.key === active ? "page" : undefined}>
          <span className="staff-navnum" aria-hidden>{String(index + 1).padStart(2, "0")}</span>{item.label}<LinkPending />
        </Link>)}
        {hasStudentPortal && <Link href="/student" className="staff-navitem">
          <span className="staff-navnum" aria-hidden>↗</span>Student portal
        </Link>}
      </nav>
      <div className="staff-railfoot">
        <p className="staff-academy">PHYSICS CUBE ACADEMY</p>
        <Link href="/account/password" className="staff-signout staff-password">Change password</Link>
        <form method="post" action="/api/session/logout">
          <button className="staff-signout" type="submit">Sign out</button>
        </form>
      </div>
    </aside>
    <main className="content">
      <p className="crumbs">ACADEMY / {crumb.toUpperCase()}</p>
      <h1 className="heading staff-heading">{title}</h1>
      <p className="sub">{subtitle}</p>
      <hr className="staff-rule" />
      {children}
    </main>
  </div>;
}
