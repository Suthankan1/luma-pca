import { redirect } from "next/navigation";
import Link from "next/link";
import { Shell } from "@/components/Shell";
import { currentMfaChallenge, currentSession } from "@/lib/auth-server";
import { portalPath } from "@/lib/session-policy";

const messages = {
  invalid: "Check your email and password and try again.",
  expired: "Your session ended. Sign in to continue.",
  unavailable: "We couldn’t reach the sign-in service. Please try again shortly.",
  limited: "Too many sign-in attempts. Wait a moment and try again.",
  access: "Your account has no portal access. Contact Physics Cube Academy for help.",
  "sign-out": "We couldn’t sign you out. Please try again.",
};

export default async function SignIn({ searchParams }: {
  searchParams: Promise<{ error?: string; portal?: string; signedOut?: string }>;
}) {
  const { error, portal, signedOut } = await searchParams;
  const { identity, canRefresh, unavailable } = await currentSession();
  const destination = identity ? portalPath(identity, portal) : null;
  if (destination && error !== "sign-out" && (destination === "/staff" || destination === "/student")) {
    redirect(destination);
  }
  const mfa = !identity && !canRefresh ? await currentMfaChallenge() : null;
  const message = error && Object.hasOwn(messages, error)
    ? messages[error as keyof typeof messages]
    : unavailable ? messages.unavailable : undefined;

  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Sign in to LUMA</h1>
      {message && <p className="session-error" role="alert">{message}</p>}
      {signedOut === "1" && <p className="sub" role="status">You’re signed out.</p>}
      {error === "sign-out" || identity || canRefresh ? <>
        {canRefresh && !identity && error !== "sign-out" && <form method="post" action="/api/session/refresh" className="session-form">
          {(portal === "staff" || portal === "student") && <input type="hidden" name="portal" value={portal} />}
          <p className="sub">Continue with your existing session.</p>
          <button className="action" type="submit">Continue session</button>
        </form>}
        <form method="post" action="/api/session/logout" className="session-form">
          <button className="action secondary" type="submit">Sign out</button>
        </form>
      </> : mfa ? <>
        <p className="sub">Two-factor authentication is required to complete sign-in.</p>
        <div className="nav-links">
          <Link href="/sign-in/mfa" className="action">Continue verification</Link>
          <form method="post" action="/api/session/mfa-cancel">
            <button className="action secondary" type="submit">Cancel and restart</button>
          </form>
        </div>
      </> : <form method="post" action="/api/session/login" className="session-form">
        {(portal === "staff" || portal === "student") && <input type="hidden" name="portal" value={portal} />}
        <label htmlFor="email">Email</label>
        <input id="email" name="email" type="email" autoComplete="username" maxLength={254} required />
        <label htmlFor="password">Password</label>
        <input id="password" name="password" type="password" autoComplete="current-password" maxLength={256} required />
        <button className="action" type="submit">Sign in</button>
      </form>}
    </section>
  </Shell>;
}
