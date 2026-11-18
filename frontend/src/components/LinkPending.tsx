"use client";

import { useLinkStatus } from "next/link";

/** Inside a `<Link>`: a small pulsing dot while that link's page is on its way, so a click is visibly registered. */
export function LinkPending() {
  const { pending } = useLinkStatus();
  return <span aria-hidden className={`link-pending ${pending ? "is-pending" : ""}`} />;
}
