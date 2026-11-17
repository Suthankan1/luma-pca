"use client";

import Link from "next/link";
import { Shell } from "@/components/Shell";

/** An unexpected failure while showing a page. Details stay in the server log; the digest helps staff find them. */
export default function PageError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Something went wrong</h1>
      <p className="session-error" role="alert">We couldn’t show this page. Nothing you entered was lost by this error; try again.</p>
      {error.digest && <p className="sub">If it keeps happening, tell an administrator this reference: <code>{error.digest}</code></p>}
      <div className="button-row">
        <button className="action" type="button" onClick={() => reset()}>Try again</button>
        <Link className="text-link" href="/">Go to LUMA home</Link>
      </div>
    </section>
  </Shell>;
}
