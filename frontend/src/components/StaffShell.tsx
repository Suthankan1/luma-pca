import { Plus_Jakarta_Sans } from "next/font/google";
import { StaffTabLinks, type StaffSection } from "./StaffTabLinks";
import { PortalFrame, PortalRail } from "./PortalRail";

/** The staff portal's typeface, served from this site (no request to Google from the browser). */
const staffFont = Plus_Jakarta_Sans({ subsets: ["latin"], weight: ["400", "500", "600", "700"], display: "swap", variable: "--font-staff" });
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
  return <PortalFrame className={`staff-shell ${staffFont.variable}${active === "overview" ? " staff-home-shell" : ""}`}>
    <PortalRail portal="staff">
      <StaffTabLinks active={active} hasStudentPortal={hasStudentPortal} />
    </PortalRail>
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
  </PortalFrame>;
}
