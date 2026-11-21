"use client";

import Link from "next/link";
import Image from "next/image";
import { createContext, useContext, useId, useState, type ReactNode } from "react";

const paths = {
  home: "m3 10 9-7 9 7v9a2 2 0 0 1-2 2h-4v-6H9v6H5a2 2 0 0 1-2-2z",
  calendar: "M7 3v4m10-4v4M4 10h16M5 5h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z",
  layers: "m12 3 9 5-9 5-9-5zm-9 9 9 5 9-5M3 16l9 5 9-5",
  users: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m20 0v-2a4 4 0 0 0-3-3.9M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm8-8a4 4 0 0 1 0 8",
  chart: "M4 3v17h17M9 16v-3m5 3V9m5 7V5",
  external: "M13 5h6v6m0-6-9 9m8 0v5H5V6h5",
  lock: "M7 11V7a5 5 0 0 1 10 0v4m-12 0h14v10H5z",
  exit: "M13 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h8m3-4 4-4-4-4m-6 4h10",
  collapse: "M4 4h16v16H4zM9 4v16m6-11-3 3 3 3",
  expand: "M4 4h16v16H4zM9 4v16m4-11 3 3-3 3",
} as const;

export function PortalIcon({ name }: { name: keyof typeof paths }) {
  return <svg aria-hidden="true" focusable="false" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round"><path d={paths[name]} /></svg>;
}

const RailState = createContext<{ collapsed: boolean; toggle: () => void }>({ collapsed: false, toggle: () => {} });

export function PortalRailProvider({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(false);
  return <RailState.Provider value={{ collapsed, toggle: () => setCollapsed(value => !value) }}>{children}</RailState.Provider>;
}

export function PortalFrame({ children, className = "" }: { children: ReactNode; className?: string }) {
  const { collapsed } = useContext(RailState);
  return <div className={`shell portal-frame ${className}`} data-rail-collapsed={collapsed}>{children}</div>;
}

export function PortalRail({ children, portal }: { children: ReactNode; portal: "staff" | "student" }) {
  const { collapsed, toggle } = useContext(RailState);
  const id = useId();
  const label = collapsed ? "Expand sidebar" : "Collapse sidebar";
  return <aside className="aside staff-rail portal-rail" aria-label={portal === "staff" ? "Staff navigation" : "Academy navigation"}>
    <div className="portal-rail-top">
      <button type="button" className="portal-rail-toggle" onClick={toggle} aria-label={label} title={label}
        aria-controls={id} aria-expanded={!collapsed}><PortalIcon name={collapsed ? "expand" : "collapse"} /></button>
      <Link href={`/${portal}`} className="portal-rail-brand" aria-label={collapsed ? "LUMA PCA Attendance System" : undefined}>
        <Image src="/physics-cube-academy-logo.jpg" alt="" width={33} height={33} />
        <span className="portal-rail-copy"><strong>LUMA <small>PCA</small></strong>{" "}<span>Attendance System</span></span>
      </Link>
    </div>
    <div className="portal-rail-menu" id={id}>{children}</div>
    <div className="staff-railfoot portal-rail-foot">
      <Link href="/account/password" className="portal-rail-action" title="Change password" aria-label="Change password">
        <PortalIcon name="lock" /><span className="portal-rail-copy">Change password</span>
      </Link>
      <form method="post" action="/api/session/logout">
        <button className="portal-rail-action" type="submit" title="Sign out" aria-label="Sign out">
          <PortalIcon name="exit" /><span className="portal-rail-copy">Sign out</span></button>
      </form>
    </div>
  </aside>;
}
