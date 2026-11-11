import Link from "next/link";
import Image from "next/image";
import type { ReactNode } from "react";

export function Shell({ children, active = "home" }: { children: ReactNode; active?: "home" | "staff" | "student" }) {
  return <div className="shell">
    <aside className="aside" aria-label="Preview navigation">
      <Link href="/" className="brand"><span className="brandmark">◈</span> luma.</Link>
      <p className="text-xs tracking-widest text-cyan-200 w-full mt-3 mb-2">PHYSICS CUBE ACADEMY</p>
      <Link className={`navitem ${active === "home" ? "active" : ""}`} href="/">Platform home</Link>
      <Link className={`navitem ${active === "staff" ? "active" : ""}`} href="/staff">Staff preview</Link>
      <Link className={`navitem ${active === "student" ? "active" : ""}`} href="/student">Student preview</Link>
      <Image className="brandtile" src="/physics-cube-academy-logo.jpg" alt="Physics Cube Academy supplied logo" width={560} height={300} />
    </aside>
    <main className="content">
      <div className="statusbar"><span>ACADEMY / PRESENCE INTELLIGENCE</span><span>BOOTSTRAP BUILD · NO LIVE DATA</span></div>
      {children}
      <footer>Prototype UI only · JWT authentication and Zoom synchronization are not implemented yet.</footer>
    </main>
  </div>;
}
