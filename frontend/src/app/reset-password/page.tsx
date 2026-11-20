import type { Metadata } from "next";
import Link from "next/link";
import { ActivationCodeFromLink } from "@/components/ActivationCodeFromLink";
import { Shell } from "@/components/Shell";

// As on the activation page: the #token fragment is never sent in a Referer, and a
// "no-referrer" policy would make the form post send "Origin: null".
export const metadata: Metadata = { title: "Choose a new password · LUMA" };

const messages = {
  token: "Enter the code from your reset email, or paste the whole link.",
  invalid: "This reset link is invalid, already used or expired. Ask for a new one.",
  mismatch: "The two passwords don’t match.",
  weak: "Choose a password of 12 to 128 characters that doesn’t contain your email name or repeat a single character.",
  limited: "Too many attempts. Wait a moment and try again.",
  unavailable: "We couldn’t reach the account service. Please try again shortly.",
};

export default async function ResetPassword({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const message = error && Object.hasOwn(messages, error) ? messages[error as keyof typeof messages] : undefined;

  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Choose a new password</h1>
      {message && <p className="session-error" role="alert">{message}</p>}
      {error === "invalid" && <p className="sub"><Link href="/forgot-password">Email me a new reset link</Link></p>}
      <p className="sub">After this you’ll be signed out on every device.</p>
      <form method="post" action="/api/session/reset-confirm" className="session-form">
        <label htmlFor="token">Reset code</label>
        <input id="token" name="token" type="text" autoComplete="off" autoCapitalize="off" spellCheck={false}
          maxLength={2048} required />
        <label htmlFor="password">New password</label>
        <input id="password" name="password" type="password" autoComplete="new-password" minLength={12} maxLength={128}
          required aria-describedby="password-help" />
        <p id="password-help" className="sub">
          At least 12 characters. A few unrelated words make a strong, memorable password.
        </p>
        <label htmlFor="confirm">Confirm new password</label>
        <input id="confirm" name="confirm" type="password" autoComplete="new-password" minLength={12} maxLength={128} required />
        <button className="action" type="submit">Change password</button>
      </form>
      <ActivationCodeFromLink inputId="token" />
      <p className="sub"><Link href="/sign-in">Back to sign in</Link></p>
    </section>
  </Shell>;
}
