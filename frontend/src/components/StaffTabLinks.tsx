"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { createContext, useContext, useState, type ReactNode } from "react";
import { LinkPending } from "./LinkPending";

const staffNav = [
  { key: "overview", label: "Home", href: "/staff", icon: "◷", rememberUrl: false },
  { key: "sessions", label: "Classes", href: "/staff/sessions", icon: "▶", rememberUrl: true },
  { key: "class-types", label: "Class Types", href: "/staff/class-types", icon: "▦", rememberUrl: true },
  { key: "students", label: "Students", href: "/staff/students", icon: "☺", rememberUrl: true },
  { key: "reports", label: "Reports", href: "/staff/reports", icon: "▤", rememberUrl: true },
] as const;

export type StaffSection = (typeof staffNav)[number]["key"]
  | "attendance" | "academy" | "courses" | "batches" | "zoom" | "activity" | "dashboard";

const StaffTabHistory = createContext<{
  urls: Readonly<Record<string, string>>;
  remember: (path: string, url: string) => void;
} | null>(null);

export function StaffTabProvider({ children }: { children: ReactNode }) {
  const [urls, setUrls] = useState<Record<string, string>>({});
  function remember(path: string, url: string) {
    setUrls(previous => previous[path] === url ? previous : { ...previous, [path]: url });
  }
  return <StaffTabHistory.Provider value={{ urls, remember }}>{children}</StaffTabHistory.Provider>;
}

export function StaffTabLinks({ active, hasStudentPortal }: { active: StaffSection; hasStudentPortal?: boolean }) {
  const history = useContext(StaffTabHistory);
  const pathname = usePathname();
  const params = useSearchParams();
  const search = params.toString();
  const currentUrl = search ? `${pathname}?${search}` : pathname;

  function rememberCurrentTab() {
    if (staffNav.some(item => item.href === pathname && item.rememberUrl)) {
      history?.remember(pathname, currentUrl);
    }
  }

  return <nav className="staff-nav" aria-label="Staff sections">
    {staffNav.map(item => {
      const here = item.href === pathname;
      const href = item.rememberUrl && here ? currentUrl : history?.urls[item.href] ?? item.href;
      return <Link key={item.key} href={href} onNavigate={rememberCurrentTab}
        prefetch={!here && href.includes("?") ? true : undefined}
        className={`staff-navitem ${item.key === active ? "active" : ""}`}
        aria-current={item.key === active ? "page" : undefined}>
        <span className="staff-navicon" aria-hidden>{item.icon}</span>{item.label}<LinkPending />
      </Link>;
    })}
    {hasStudentPortal && <Link href="/student" className="staff-navitem">
      <span className="staff-navicon" aria-hidden>↗</span>Student portal
    </Link>}
  </nav>;
}
