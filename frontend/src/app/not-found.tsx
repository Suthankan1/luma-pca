import type { Metadata } from "next";
import Link from "next/link";
import { Shell } from "@/components/Shell";

export const metadata: Metadata = { title: "Not found · LUMA" };

/** Shown for unknown addresses and for records the signed-in person may not see (the API never says which). */
export default function NotFound() {
  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Page not found</h1>
      <p className="sub">This page doesn’t exist, or it isn’t available to your account.</p>
      <p className="link-row"><Link className="text-link" href="/">Go to LUMA home</Link></p>
    </section>
  </Shell>;
}
