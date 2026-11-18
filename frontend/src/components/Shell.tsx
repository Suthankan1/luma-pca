import Link from "next/link";
import Image from "next/image";
import type { ReactNode } from "react";
import type { Portal } from "@/lib/session-policy";

const studentNav = [
  { key: "home", label: "My classes", href: "/student", icon: "▶" },
  { key: "attendance", label: "My attendance", href: "/student/attendance", icon: "✓" },
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
    return <div className="shell">
      <aside className="aside staff-rail student-rail" aria-label="Academy navigation">
        <Link href="/student" className="brand"><span className="brand-dot" aria-hidden /> luma</Link>
        <nav className="staff-nav" aria-label="Student portal">
          {studentNav.map(item => <Link key={item.key} href={item.href}
            className={`staff-navitem ${(studentSection ?? "home") === item.key ? "active" : ""}`}
            aria-current={(studentSection ?? "home") === item.key ? "page" : undefined}>
            <span className="staff-navicon" aria-hidden>{item.icon}</span>{item.label}
          </Link>)}
          {otherPortal && <Link className="staff-navitem" href={`/${otherPortal}`}>
            <span className="staff-navicon" aria-hidden>↗</span>{otherPortal === "staff" ? "Staff portal" : "Student portal"}
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
      <main className="content staff-content">{children}</main>
    </div>;
  }
  return <div className="shell">
    <aside className="aside" aria-label="Academy navigation">
      <Link href="/" className="brand"><span className="brandmark" aria-hidden>◈</span> luma.</Link>
      <p className="text-xs tracking-widest text-cyan-200 w-full mt-3 mb-2">PHYSICS CUBE ACADEMY</p>
      {active === "staff" ? <>
        <Link className="navitem active" aria-current="page" href="/staff">Staff portal</Link>
        {otherPortal && <Link className="navitem" href={`/${otherPortal}`}>Student portal</Link>}
      </> : <Link className={`navitem ${active === "home" ? "active" : ""}`} href="/"
        aria-current={active === "home" ? "page" : undefined}>Platform home</Link>}
      <Image className="brandtile" src="/physics-cube-academy-logo.jpg" alt="Physics Cube Academy" width={560} height={300} priority />
    </aside>
    <main className="content">{children}</main>
  </div>;
}
