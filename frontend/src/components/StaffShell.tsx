import Link from "next/link";
import { Plus_Jakarta_Sans } from "next/font/google";
import { StaffTabLinks, type StaffSection } from "./StaffTabLinks";

/** The staff portal's typeface, served from this site (no request to Google from the browser). */
const staffFont = Plus_Jakarta_Sans({ subsets: ["latin"], weight: ["400", "500", "600", "700"], display: "swap", variable: "--font-staff" });
import { AcademyBrand } from "./AcademyBrand";
import type { ReactNode } from "react";

export type { StaffSection } from "./StaffTabLinks";

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
      <StaffTabLinks active={active} hasStudentPortal={hasStudentPortal} />
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
