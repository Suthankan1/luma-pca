import Link from "next/link";
import { Plus_Jakarta_Sans } from "next/font/google";
import { LinkPending } from "./LinkPending";

/** The staff portal's typeface, served from this site (no request to Google from the browser). */
const staffFont = Plus_Jakarta_Sans({ subsets: ["latin"], weight: ["400", "500", "600", "700"], display: "swap", variable: "--font-staff" });
import { AcademyBrand } from "./AcademyBrand";
import type { ReactNode } from "react";

const staffNav = [
  { key: "overview", label: "Home", href: "/staff", icon: "◷" },
  { key: "sessions", label: "Classes", href: "/staff/sessions", icon: "▶" },
  { key: "class-types", label: "Class Types", href: "/staff/class-types", icon: "▦" },
  { key: "students", label: "Students", href: "/staff/students", icon: "☺" },
  { key: "reports", label: "Reports", href: "/staff/reports", icon: "▤" },
] as const;

export type StaffSection = (typeof staffNav)[number]["key"]
  | "attendance" | "academy" | "courses" | "zoom" | "activity" | "dashboard";

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
      <nav className="staff-nav" aria-label="Staff sections">
        {staffNav.map(item => <Link key={item.key} href={item.href}
          className={`staff-navitem ${item.key === active ? "active" : ""}`}
          aria-current={item.key === active ? "page" : undefined}>
          <span className="staff-navicon" aria-hidden>{item.icon}</span>{item.label}<LinkPending />
        </Link>)}
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
