import { redirect } from "next/navigation";
import Link from "next/link";
import { Shell } from "@/components/Shell";
import { currentSession, studentIdLoginEnabled } from "@/lib/auth-server";
import { portalPath } from "@/lib/session-policy";

const messages = {
  invalid: "Check your email and password and try again.",
  student: "Check your student ID and try again.",
  expired: "Your session ended. Sign in to continue.",
  unavailable: "We couldn’t reach the sign-in service. Please try again shortly.",
  limited: "Too many sign-in attempts. Wait a moment and try again.",
  access: "Your account has no portal access. Contact Physics Cube Academy for help.",
  "sign-out": "We couldn’t sign you out. Please try again.",
};

export default async function SignIn({ searchParams }: {
  searchParams: Promise<{ error?: string; portal?: string; signedOut?: string; activated?: string; reset?: string; changed?: string }>;
}) {
  const { error, portal, signedOut, activated, reset, changed } = await searchParams;
  const { identity, canRefresh, unavailable } = await currentSession();
  const destination = identity ? portalPath(identity, portal) : null;
  if (destination && error !== "sign-out" && (destination === "/staff" || destination === "/student")) {
    redirect(destination);
  }
  const message = error && Object.hasOwn(messages, error)
    ? messages[error as keyof typeof messages]
    : unavailable ? messages.unavailable : undefined;
  const showStudentIdForm = studentIdLoginEnabled() && portal !== "staff";
  const showEmailForm = !showStudentIdForm || portal !== "student";
  const showBothForms = showStudentIdForm && showEmailForm;

  return <Shell>
    <section className={`panel session-panel ${showBothForms && !identity && !canRefresh ? "session-panel-wide" : ""}`}>
      <p className="eyebrow">{portal === "staff" ? "STAFF PORTAL" : portal === "student" ? "STUDENT PORTAL" : "PHYSICS CUBE ACADEMY"}</p>
      <h1 className="heading">Sign in to LUMA</h1>
      {portal === "student" && !showStudentIdForm && <p className="sub">Sign in with your academy account to view your attendance.</p>}
      {message && <p className="session-error" role="alert">{message}</p>}
      {signedOut === "1" && <p className="sub" role="status">You’re signed out.</p>}
      {activated === "1" && <p className="sub" role="status">Your account is active. Sign in with your new password.</p>}
      {(reset === "1" || changed === "1") && <p className="sub" role="status">Your password has been changed and you were signed out everywhere. Sign in with your new password.</p>}
      {error === "sign-out" || identity || canRefresh ? <>
        {canRefresh && !identity && error !== "sign-out" && <form method="post" action="/api/session/refresh" className="session-form">
          {(portal === "staff" || portal === "student") && <input type="hidden" name="portal" value={portal} />}
          <p className="sub">Continue with your existing session.</p>
          <button className="action" type="submit">Continue session</button>
        </form>}
        <form method="post" action="/api/session/logout" className="session-form">
          <button className="action secondary" type="submit">Sign out</button>
        </form>
      </> : <div className={showBothForms ? "signin-cards" : undefined}>
      {showStudentIdForm && <form method="post" action="/api/session/student-login" className="session-form"
        aria-labelledby="student-sign-in">
        <h2 id="student-sign-in" className="eyebrow">Students</h2>
        <input type="hidden" name="portal" value="student" />
        <label htmlFor="student-id">Username</label>
        <input id="student-id" name="studentId" type="text" autoComplete="username" autoCapitalize="characters" spellCheck={false}
          maxLength={60} required />
        <label htmlFor="student-password">Password</label>
        <input id="student-password" name="password" type="password" autoComplete="current-password" maxLength={60} required />
        <p className="sub">Enter your registration ID for both username and password.</p>
        <button className="action" type="submit">Open my student portal</button>
      </form>}
      {showEmailForm && <form method="post" action="/api/session/login" className="session-form"
        aria-labelledby={showBothForms ? "account-sign-in" : undefined}>
        {showBothForms && <h2 id="account-sign-in" className="eyebrow">Academy account</h2>}
        {(portal === "staff" || portal === "student") && <input type="hidden" name="portal" value={portal} />}
        <label htmlFor="email">Email</label>
        <input id="email" name="email" type="email" autoComplete="username" maxLength={254} required />
        <label htmlFor="password">Password</label>
        <input id="password" name="password" type="password" autoComplete="current-password" maxLength={256} required />
        <button className="action" type="submit">Sign in</button>
        <p className="sub"><Link href="/forgot-password">Forgot your password?</Link></p>
      </form>}
      </div>}
    </section>
  </Shell>;
}
