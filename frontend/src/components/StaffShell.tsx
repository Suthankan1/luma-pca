import Link from "next/link";
import type { ReactNode } from "react";

export const staffNav = [
  { key: "overview", label: "Overview", href: "/staff" },
  { key: "courses", label: "Courses & batches", href: "/staff/courses" },
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
        {staffNav.map((item, index) => <Link key={item.key} href={item.href} prefetch={false}
          className={`staff-navitem ${item.key === active ? "active" : ""}`} aria-current={item.key === active ? "page" : undefined}>
          <span className="staff-navnum" aria-hidden>{String(index + 1).padStart(2, "0")}</span>{item.label}
        </Link>)}
        {hasStudentPortal && <Link href="/student" prefetch={false} className="staff-navitem">
          <span className="staff-navnum" aria-hidden>↗</span>Student portal
        </Link>}
      </nav>
      <div className="staff-railfoot">
        <p className="staff-academy">PHYSICS CUBE ACADEMY</p>
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
