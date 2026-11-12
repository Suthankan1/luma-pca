import type { Metadata } from "next";
import Link from "next/link";
import { Shell } from "@/components/Shell";

export const metadata: Metadata = { title: "Forgot your password · LUMA" };

const messages = {
  email: "Enter the email address you sign in with.",
  disabled: "Password reset by email isn’t available yet. Ask a Physics Cube Academy administrator to help you.",
  limited: "Too many attempts. Wait a moment and try again.",
  unavailable: "We couldn’t reach the account service. Please try again shortly.",
};

export default async function ForgotPassword({ searchParams }: { searchParams: Promise<{ error?: string; sent?: string }> }) {
  const { error, sent } = await searchParams;
  const message = error && Object.hasOwn(messages, error) ? messages[error as keyof typeof messages] : undefined;

  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Forgot your password</h1>
      {message && <p className="session-error" role="alert">{message}</p>}
      {sent === "1" ? <>
        {/* The same words whether or not the address has an account. */}
        <p className="sub" role="status">If an active LUMA account uses that address, we’ve emailed it a link to choose a new
          password. The link works once, for 30 minutes. Check your spam folder if it hasn’t arrived in a few minutes.</p>
        <p className="sub">Not activated your account yet? Use your invitation link instead.</p>
      </> : <>
        <p className="sub">Enter your email address and we’ll send you a link to choose a new password.</p>
        <form method="post" action="/api/session/reset-request" className="session-form">
          <label htmlFor="email">Email</label>
          <input id="email" name="email" type="email" autoComplete="username" maxLength={254} required />
          <button className="action" type="submit">Email me a reset link</button>
        </form>
      </>}
      <p className="sub"><Link href="/sign-in">Back to sign in</Link></p>
    </section>
  </Shell>;
}
