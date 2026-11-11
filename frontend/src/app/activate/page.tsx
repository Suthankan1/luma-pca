import type { Metadata } from "next";
import Link from "next/link";
import { ActivationCodeFromLink } from "@/components/ActivationCodeFromLink";
import { Shell } from "@/components/Shell";

// No special referrer policy: browsers never send the #token fragment in a Referer, and
// "no-referrer" would make form posts send "Origin: null", which the session route refuses.
export const metadata: Metadata = { title: "Activate your account · LUMA" };

const messages = {
  token: "Enter the activation code from your invitation, or paste the whole invitation link.",
  invalid: "This activation code is invalid, already used or expired. Ask Physics Cube Academy for a new invitation.",
  mismatch: "The two passwords don’t match.",
  weak: "Choose a password of 12 to 128 characters that doesn’t contain your email name or repeat a single character.",
  limited: "Too many attempts. Wait a moment and try again.",
  unavailable: "We couldn’t reach the account service. Please try again shortly.",
};

export default async function Activate({ searchParams }: { searchParams: Promise<{ error?: string }> }) {
  const { error } = await searchParams;
  const message = error && Object.hasOwn(messages, error) ? messages[error as keyof typeof messages] : undefined;

  return <Shell>
    <section className="panel session-panel">
      <h1 className="heading">Activate your account</h1>
      {message && <p className="session-error" role="alert">{message}</p>}
      <p className="sub">Choose a password to finish setting up your LUMA account.</p>
      <form method="post" action="/api/session/activate" className="session-form">
        <label htmlFor="token">Activation code</label>
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
        <button className="action" type="submit">Activate account</button>
      </form>
      <ActivationCodeFromLink inputId="token" />
      <p className="sub">Already activated? <Link href="/sign-in">Sign in</Link></p>
    </section>
  </Shell>;
}
