"use client";

import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { createContext, useContext, useState, type ReactNode } from "react";
import { LinkPending } from "./LinkPending";
import { PortalIcon } from "./PortalRail";

const staffNav = [
  { key: "overview", label: "Home", href: "/staff", icon: "home", rememberUrl: false },
  { key: "sessions", label: "Classes", href: "/staff/sessions", icon: "calendar", rememberUrl: true },
  { key: "class-types", label: "Class Types", href: "/staff/class-types", icon: "layers", rememberUrl: true },
  { key: "students", label: "Students", href: "/staff/students", icon: "users", rememberUrl: true },
  { key: "reports", label: "Reports", href: "/staff/reports", icon: "chart", rememberUrl: true },
] as const;

export type StaffSection = (typeof staffNav)[number]["key"]
  | "attendance" | "academy" | "courses" | "batches" | "zoom" | "activity";

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
  const month = params.get("month");

  function rememberCurrentTab() {
    if (staffNav.some(item => item.href === pathname && item.rememberUrl)) {
      history?.remember(pathname, currentUrl);
    }
  }

  return <nav className="staff-nav" aria-label="Staff sections">
    {staffNav.map(item => {
      const here = item.href === pathname;
      const sectionMonth = item.key === active && (active === "sessions" || active === "class-types") && month;
      const href = item.rememberUrl && here ? currentUrl
        : sectionMonth ? `${item.href}?${new URLSearchParams({ month: sectionMonth })}`
        : history?.urls[item.href] ?? item.href;
      return <Link key={item.key} href={href} onNavigate={rememberCurrentTab} title={item.label} aria-label={item.label}
        prefetch={!here && href.includes("?") ? true : undefined}
        className={`staff-navitem ${item.key === active ? "active" : ""}`}
        aria-current={item.key === active ? "page" : undefined}>
        <PortalIcon name={item.icon} /><span className="portal-rail-copy">{item.label}</span><LinkPending />
      </Link>;
    })}
    {hasStudentPortal && <Link href="/student" className="staff-navitem" title="Student portal" aria-label="Student portal">
      <PortalIcon name="external" /><span className="portal-rail-copy">Student portal</span>
    </Link>}
  </nav>;
}
