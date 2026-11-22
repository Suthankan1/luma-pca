import Link from "next/link";
import { AcademyBrand } from "./AcademyBrand";
import type { ReactNode } from "react";
import type { Portal } from "@/lib/session-policy";

/** Public pages share the academy header and footer. */
export function Shell({ children, active, otherPortal }: {
  children: ReactNode;
  active?: "home" | Portal;
  otherPortal?: Portal;
}) {
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
