import Link from "next/link";
import { Plus_Jakarta_Sans } from "next/font/google";
import { LinkPending } from "./LinkPending";

/** The staff portal's typeface, served from this site (no request to Google from the browser). */
const staffFont = Plus_Jakarta_Sans({ subsets: ["latin"], weight: ["400", "500", "600", "700"], display: "swap", variable: "--font-staff" });
import { AcademyBrand } from "./AcademyBrand";
import type { ReactNode } from "react";

/** The staff menu, in groups people think in: what is happening, teaching, people, and setup. */
export const staffNavGroups = [
  { label: "Overview", items: [
    { key: "overview", label: "Home", href: "/staff", icon: "◷" },
  ] },
  { label: "Teaching", items: [
    { key: "sessions", label: "Classes", href: "/staff/sessions", icon: "▶" },
    { key: "attendance", label: "Attendance", href: "/staff/attendance", icon: "✓" },
  ] },
  { label: "Academy", items: [
    { key: "academy", label: "Academy classes", href: "/staff/academy", icon: "⇄" },
    { key: "students", label: "Students", href: "/staff/students", icon: "☺" },
  ] },
  { label: "Administration", items: [
    { key: "courses", label: "Courses & batches", href: "/staff/courses", icon: "▤" },
    { key: "zoom", label: "Zoom review", href: "/staff/zoom", icon: "◎" },
    { key: "activity", label: "Activity log", href: "/staff/activity", icon: "≡" },
  ] },
] as const;

/** Pages reached from another section's tabs light up that section in the menu. */
const sectionOf: Record<string, string> = { dashboard: "attendance" };

type NavItem = (typeof staffNavGroups)[number]["items"][number];

export const staffNav: readonly NavItem[] = staffNavGroups.flatMap(group => group.items as readonly NavItem[]);

export type StaffSection = NavItem["key"] | "dashboard";

/** Staff layout: a navy menu that stays in view, then a compact page header and the page. */
export function StaffShell({ active, crumb, title, fullTitle, subtitle, hasStudentPortal, actions, children }: {
  active: StaffSection;
  crumb: string;
  title: string;
  fullTitle?: string;
  subtitle: string;
  hasStudentPortal?: boolean;
  /** Buttons or links shown beside the title. */
  actions?: ReactNode;
  children: ReactNode;
}) {
  return <div className={`shell staff-shell ${staffFont.variable}${active === "overview" ? " staff-home-shell" : ""}`}>
    <aside className="aside staff-rail" aria-label="Staff navigation">
      <AcademyBrand href="/staff" />
      <nav className="staff-nav">
        {staffNavGroups.map(group => <div key={group.label} className="staff-navgroup" role="group" aria-label={group.label}>
          <span className="staff-navlabel" aria-hidden>{group.label}</span>
          {group.items.map(item => <Link key={item.key} href={item.href}
            className={`staff-navitem ${item.key === (sectionOf[active] ?? active) ? "active" : ""}`}
            aria-current={item.key === (sectionOf[active] ?? active) ? "page" : undefined}>
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
          {crumb && <p className="crumbs">{crumb}</p>}
          <h1 className="heading staff-heading" title={fullTitle}>{title}</h1>
          {subtitle && <p className="sub page-sub">{subtitle}</p>}
        </div>
        {actions && <div className="page-actions">{actions}</div>}
      </header>
      {children}
    </main>
  </div>;
}
