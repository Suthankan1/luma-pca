import Link from "next/link";
import { AcademyBrand } from "./AcademyBrand";
import { PortalFrame, PortalIcon, PortalRail } from "./PortalRail";
import type { ReactNode } from "react";
import type { Portal } from "@/lib/session-policy";

const studentNav = [
  { key: "calendar", label: "Calendar", href: "/student/calendar", icon: "calendar" },
  { key: "attendance", label: "My Report", href: "/student", icon: "chart" },
  { key: "live", label: "Live Now", href: "/student/live", icon: "live" },
] as const;

/**
 * Layout for public pages and the student portal. Students get their own menu (classes, attendance, account); public
 * pages show the academy's brand.
 */
export function Shell({ children, active, otherPortal, studentSection }: {
  children: ReactNode;
  active?: "home" | Portal;
  otherPortal?: Portal;
  /** Which student page is open, for the menu. */
  studentSection?: (typeof studentNav)[number]["key"];
}) {
  if (active === "student") {
    return <PortalFrame className="student-shell">
      <PortalRail portal="student">
        <nav className="staff-nav" aria-label="Student portal">
          {studentNav.map(item => <Link key={item.key} href={item.href} title={item.label} aria-label={item.label}
            className={`staff-navitem ${(studentSection ?? "attendance") === item.key ? "active" : ""}`}
            aria-current={(studentSection ?? "attendance") === item.key ? "page" : undefined}>
            <PortalIcon name={item.icon} /><span className="portal-rail-copy">{item.label}</span>
          </Link>)}
          {otherPortal && <Link className="staff-navitem" href={`/${otherPortal}`} title={otherPortal === "staff" ? "Staff portal" : "Student portal"}
            aria-label={otherPortal === "staff" ? "Staff portal" : "Student portal"}>
            <PortalIcon name="external" /><span className="portal-rail-copy">{otherPortal === "staff" ? "Staff portal" : "Student portal"}</span>
          </Link>}
        </nav>
      </PortalRail>
      <main className="content staff-content">{children}</main>
    </PortalFrame>;
  }
  return <div className="public-shell">
    <header className="public-header" aria-label="Academy navigation">
      <AcademyBrand href="/" />
      <div className="public-brand-copy"><strong>PHYSICS CUBE</strong><span>ACADEMY · LUMA</span></div>
      <nav className="public-nav" aria-label="Platform navigation">
      {active === "staff" ? <>
        <Link className="navitem active" aria-current="page" href="/staff">Staff portal</Link>
        {otherPortal && <Link className="navitem" href={`/${otherPortal}`}>Student portal</Link>}
      </> : <Link className={`navitem ${active === "home" ? "active" : ""}`} href="/"
        aria-current={active === "home" ? "page" : undefined}>Platform home</Link>}
      </nav>
    </header>
    <main className={`public-content ${active === "home" ? "public-home" : "public-auth"}`}>{children}</main>
    <footer className="public-footer"><span>Physics Cube Academy</span><span>Attendance tracking</span></footer>
  </div>;
}
