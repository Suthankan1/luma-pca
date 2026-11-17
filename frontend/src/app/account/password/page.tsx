import type { Metadata } from "next";
import Link from "next/link";
import { redirect } from "next/navigation";
import { Shell } from "@/components/Shell";
import { currentSession } from "@/lib/auth-server";

export const metadata: Metadata = { title: "Change password · LUMA" };

const messages = {
  current: "That isn’t your current password.",
  mismatch: "The two new passwords don’t match.",
  weak: "Choose a password of 12 to 128 characters that doesn’t contain your email name or repeat a single character.",
  same: "Choose a password different from your current one.",
  limited: "Too many attempts. Wait a moment and try again.",
  unavailable: "We couldn’t reach the account service. Please try again shortly.",
};

/** For anyone signed in, staff or student: change the password with the current one; every device is then signed out. */
export default async function ChangePassword({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { identity } = await currentSession();
  if (!identity) redirect("/sign-in");
  const { error } = await searchParams;
  const message = error && Object.hasOwn(messages, error) ? messages[error as keyof typeof messages] : undefined;
  const home = identity.roles.some(r => r !== "STUDENT") ? "/staff" : "/student";
  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Change your password</h1>
      {message && <p className="session-error" role="alert">{message}</p>}
      <p className="sub">You’ll be signed out on every device and sign in again with the new password.</p>
      <form method="post" action="/api/session/password-change" className="session-form">
        <label htmlFor="current">Current password</label>
        <input id="current" name="current" type="password" autoComplete="current-password" maxLength={256} required />
        <label htmlFor="password">New password</label>
        <input id="password" name="password" type="password" autoComplete="new-password" minLength={12} maxLength={128} required
          aria-describedby="password-help" />
        <p id="password-help" className="sub">At least 12 characters. A few unrelated words make a strong, memorable password.</p>
        <label htmlFor="confirm">Confirm new password</label>
        <input id="confirm" name="confirm" type="password" autoComplete="new-password" minLength={12} maxLength={128} required />
        <button className="action" type="submit">Change password</button>
      </form>
      <p className="sub"><Link href={home}>Back</Link></p>
    </section>
  </Shell>;
}
