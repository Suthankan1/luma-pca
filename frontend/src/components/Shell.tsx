import Link from "next/link";
import Image from "next/image";
import type { ReactNode } from "react";
import type { Portal } from "@/lib/session-policy";

export function Shell({ children, active, otherPortal }: {
  children: ReactNode;
  active?: "home" | Portal;
  otherPortal?: Portal;
}) {
  return <div className="shell">
    <aside className="aside" aria-label="Academy navigation">
      <Link href="/" className="brand"><span className="brandmark" aria-hidden>◈</span> luma.</Link>
      <p className="text-xs tracking-widest text-cyan-200 w-full mt-3 mb-2">PHYSICS CUBE ACADEMY</p>
      {active === "staff" || active === "student" ? <>
        <Link className="navitem active" aria-current="page" href={`/${active}`}>
          {active === "staff" ? "Staff portal" : "Student portal"}
        </Link>
        {otherPortal && <Link className="navitem" href={`/${otherPortal}`}>
          {otherPortal === "staff" ? "Staff portal" : "Student portal"}
        </Link>}
      </> : <Link className={`navitem ${active === "home" ? "active" : ""}`} href="/"
        aria-current={active === "home" ? "page" : undefined}>Platform home</Link>}
      <Image className="brandtile" src="/physics-cube-academy-logo.jpg" alt="Physics Cube Academy" width={560} height={300} priority />
    </aside>
    <main className="content">{children}</main>
  </div>;
}
