"use client";

import { usePathname } from "next/navigation";
import { studentTab } from "@/components/StudentTabLinks";
/** Shown the moment a student link is clicked, while the page's data loads. */
export default function StudentLoading() {
  const pathname = usePathname();
  const title = pathname.startsWith("/student/classes/") ? "Take part" : studentTab(pathname)?.label ?? "Student portal";
  return <>
    <h1 className="heading">{title}</h1>
    <p className="sub" role="status">Loading…</p>
    <div className="panel" aria-hidden="true">
      <div className="skeleton skeleton-title" style={{ width: "35%" }} />
      <div className="skeleton skeleton-line" />
      <div className="skeleton skeleton-line" style={{ width: "80%" }} />
    </div>
  </>;
}
