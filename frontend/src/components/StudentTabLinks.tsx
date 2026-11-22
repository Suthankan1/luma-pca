"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LinkPending } from "./LinkPending";
import { PortalIcon } from "./PortalRail";

const tabs = [
  { label: "Calendar", href: "/student/calendar", icon: "calendar" },
  { label: "My Report", href: "/student", icon: "chart" },
  { label: "Live Now", href: "/student/live", icon: "live" },
] as const;

export function studentTab(pathname: string) {
  const selected = pathname === "/student/attendance" ? "/student"
    : pathname === "/student/classes" ? "/student/calendar"
    : pathname.startsWith("/student/classes/") ? "/student/live" : pathname;
  return tabs.find(tab => tab.href === selected);
}

export function StudentTabLinks({ hasStaffPortal }: { hasStaffPortal: boolean }) {
  const selected = studentTab(usePathname());
  return <nav className="staff-nav student-tab-nav" aria-label="Student portal">
    {tabs.map(tab => {
      const active = selected?.href === tab.href;
      return <Link key={tab.href} href={tab.href} title={tab.label} aria-label={tab.label}
        prefetch={active ? false : tab.icon === "live" ? undefined : true}
        className={`staff-navitem ${active ? "active" : ""}`} aria-current={active ? "page" : undefined}>
        <PortalIcon name={tab.icon} /><span className="portal-rail-copy">{tab.label}</span><LinkPending />
      </Link>;
    })}
    {hasStaffPortal && <Link className="staff-navitem" href="/staff" title="Staff portal" aria-label="Staff portal">
      <PortalIcon name="external" /><span className="portal-rail-copy">Staff portal</span>
    </Link>}
  </nav>;
}
