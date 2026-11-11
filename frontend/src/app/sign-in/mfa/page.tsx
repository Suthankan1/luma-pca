import { redirect } from "next/navigation";
import QRCode from "qrcode";
import { Shell } from "@/components/Shell";
import { currentMfaChallenge, currentSession } from "@/lib/auth-server";
import { portalPath } from "@/lib/session-policy";

const messages = {
  code: "The code entered is invalid or expired. Check your authenticator app and try again.",
  unavailable: "We couldn’t reach the authentication service. Please try again shortly.",
  limited: "Too many verification attempts. Wait a moment and try again.",
  expired: "Your verification challenge has expired. Please sign in again.",
};

export default async function MfaPage({ searchParams }: {
  searchParams: Promise<{ error?: string }>;
}) {
  const { error } = await searchParams;
  const { identity } = await currentSession();
  if (identity) {
    redirect(portalPath(identity));
  }

  const mfa = await currentMfaChallenge();
  if (!mfa) {
    redirect("/sign-in?error=expired");
  }

  const message = error && Object.hasOwn(messages, error)
    ? messages[error as keyof typeof messages]
    : undefined;

  let qrSvg: string | null = null;
  if (!mfa.enrolled && mfa.otpauthUri) {
    try {
      qrSvg = await QRCode.toString(mfa.otpauthUri, {
        type: "svg",
        margin: 1,
        width: 200,
        color: { dark: "#22206a", light: "#ffffff" },
      });
    } catch {
      qrSvg = null;
    }
  }

  const formattedSecret = mfa.secret ? mfa.secret.match(/.{1,4}/g)?.join(" ") : null;

  return (
    <Shell>
      <section className="panel session-panel">
        {!mfa.enrolled ? (
          <>
            <span className="eyebrow">TWO-FACTOR ENROLLMENT</span>
            <h1 className="heading">Set up authenticator</h1>
            <p className="sub">
              To secure your academy account, configure an authenticator app such as Google Authenticator,
              Microsoft Authenticator, or 1Password.
            </p>

            {message && <p className="session-error" role="alert">{message}</p>}

            {qrSvg && (
              <div
                className="mfa-qr"
                aria-label="Authenticator setup QR code"
                dangerouslySetInnerHTML={{ __html: qrSvg }}
              />
            )}

            {formattedSecret && (
              <div>
                <p className="sub" style={{ fontSize: "13px", marginTop: "12px" }}>
                  Unable to scan the QR code? Enter this key manually in your authenticator app:
                </p>
                <code className="mfa-secret" aria-label="Manual entry key">{formattedSecret}</code>
              </div>
            )}

            <form method="post" action="/api/session/mfa-verify" className="session-form">
              <label htmlFor="code">Enter the 6-digit code</label>
              <input
                id="code"
                name="code"
                type="text"
                inputMode="numeric"
                autoComplete="one-time-code"
                pattern="[0-9]{6}"
                maxLength={32}
                placeholder="123456"
                required
                autoFocus
              />
              <button className="action" type="submit">Verify and save</button>
            </form>

            <form method="post" action="/api/session/mfa-cancel" className="session-form" style={{ marginTop: "10px" }}>
              <button className="action secondary" type="submit">Cancel</button>
            </form>
          </>
        ) : (
          <>
            <span className="eyebrow">SECURITY CHECK</span>
            <h1 className="heading">Enter your code</h1>
            <p className="sub">
              Enter the 6-digit verification code from your authenticator app, or a one-time recovery code.
            </p>

            {message && <p className="session-error" role="alert">{message}</p>}

            <form method="post" action="/api/session/mfa-verify" className="session-form">
              <label htmlFor="code">Authenticator or recovery code</label>
              <input
                id="code"
                name="code"
                type="text"
                inputMode="text"
                autoComplete="one-time-code"
                maxLength={32}
                placeholder="6-digit code or recovery code"
                required
                autoFocus
              />
              <button className="action" type="submit">Verify</button>
            </form>

            <form method="post" action="/api/session/mfa-cancel" className="session-form" style={{ marginTop: "10px" }}>
              <button className="action secondary" type="submit">Cancel</button>
            </form>
          </>
        )}
      </section>
    </Shell>
  );
}
