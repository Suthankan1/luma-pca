import Link from "next/link";
import { LinkPending } from "./LinkPending";
import type { ReactNode } from "react";

/** The staff menu, in groups people think in: what is happening, teaching, people, and setup. */
export const staffNavGroups = [
  { label: "Overview", items: [
    { key: "overview", label: "Today", href: "/staff", icon: "◷" },
    { key: "dashboard", label: "Dashboard", href: "/staff/dashboard", icon: "▦" },
  ] },
  { label: "Teaching", items: [
    { key: "webinars", label: "Webinars", href: "/staff/webinars", icon: "◉" },
    { key: "sessions", label: "Classes", href: "/staff/sessions", icon: "▶" },
    { key: "attendance", label: "Attendance", href: "/staff/attendance", icon: "✓" },
    { key: "zoom", label: "Zoom review", href: "/staff/zoom", icon: "◎" },
  ] },
  { label: "People", items: [
    { key: "academy", label: "Academy classes", href: "/staff/academy", icon: "⇄" },
    { key: "students", label: "Students", href: "/staff/students", icon: "☺" },
    { key: "team", label: "Staff", href: "/staff/team", icon: "◆" },
  ] },
  { label: "Setup", items: [
    { key: "courses", label: "Courses & batches", href: "/staff/courses", icon: "▤" },
    { key: "activity", label: "Activity log", href: "/staff/activity", icon: "≡" },
  ] },
] as const;

type NavItem = (typeof staffNavGroups)[number]["items"][number];

export const staffNav: readonly NavItem[] = staffNavGroups.flatMap(group => group.items as readonly NavItem[]);

export type StaffSection = NavItem["key"];

/** Staff layout: a navy menu that stays in view, then a compact page header and the page. */
export function StaffShell({ active, crumb, title, subtitle, hasStudentPortal, actions, children }: {
  active: StaffSection;
  crumb: string;
  title: string;
  subtitle: string;
  hasStudentPortal?: boolean;
  /** Buttons or links shown beside the title. */
  actions?: ReactNode;
  children: ReactNode;
}) {
  return <div className="shell">
    <aside className="aside staff-rail" aria-label="Staff navigation">
      <Link href="/staff" className="brand"><span className="brand-dot" aria-hidden /> luma</Link>
      <nav className="staff-nav">
        {staffNavGroups.map(group => <div key={group.label} className="staff-navgroup" role="group" aria-label={group.label}>
          <span className="staff-navlabel" aria-hidden>{group.label}</span>
          {group.items.map(item => <Link key={item.key} href={item.href}
            className={`staff-navitem ${item.key === active ? "active" : ""}`} aria-current={item.key === active ? "page" : undefined}>
            <span className="staff-navicon" aria-hidden>{item.icon}</span>{item.label}<LinkPending />
          </Link>)}
        </div>)}
        {hasStudentPortal && <Link href="/student" className="staff-navitem">
          <span className="staff-navicon" aria-hidden>↗</span>Student portal
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
    <main className="content staff-content">
      <header className="page-head">
        <div>
          <p className="crumbs">{crumb}</p>
          <h1 className="heading staff-heading">{title}</h1>
          {subtitle && <p className="sub page-sub">{subtitle}</p>}
        </div>
        {actions && <div className="page-actions">{actions}</div>}
      </header>
      {children}
    </main>
  </div>;
}
