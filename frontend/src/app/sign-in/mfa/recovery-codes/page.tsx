import { redirect } from "next/navigation";
import { Shell } from "@/components/Shell";
import { CopyRecoveryCodes } from "@/components/CopyRecoveryCodes";
import { currentRecoveryCodes, currentSession } from "@/lib/auth-server";
import { portalPath } from "@/lib/session-policy";

export default async function RecoveryCodesPage() {
  const { identity } = await currentSession();
  if (!identity) {
    redirect("/sign-in");
  }

  const recoveryState = await currentRecoveryCodes();
  if (!recoveryState || !recoveryState.codes || recoveryState.codes.length === 0) {
    redirect(portalPath(identity));
  }

  return (
    <Shell active="staff">
      <section className="panel session-panel" style={{ maxWidth: "560px" }}>
        <span className="eyebrow">ONE-TIME BACKUP ACCESS</span>
        <h1 className="heading">Save recovery codes</h1>
        <p className="sub">
          If you lose access to your authenticator app, these one-time recovery codes can be used to sign in to
          your account. Store them in a secure password manager or offline location.
        </p>

        <div className="banner" style={{ margin: "16px 0", fontSize: "13px" }}>
          <strong>Important:</strong> Each code can only be used once. These codes will not be shown again.
        </div>

        <div className="recovery-grid" aria-label="Recovery codes list">
          {recoveryState.codes.map((code, index) => (
            <div key={index} className="recovery-code">
              <code>{code}</code>
            </div>
          ))}
        </div>

        <CopyRecoveryCodes codes={recoveryState.codes} />

        <form method="post" action="/api/session/mfa-recovery" className="session-form" style={{ marginTop: "24px" }}>
          <label className="checkbox-label">
            <input type="checkbox" name="confirmed" required />
            <span>I have safely saved or written down these recovery codes</span>
          </label>
          <button className="action" type="submit">Continue to staff portal</button>
        </form>
      </section>
    </Shell>
  );
}
