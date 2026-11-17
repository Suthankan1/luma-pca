import { NextRequest, NextResponse } from "next/server";
import {
  authRequest,
  config,
  currentSession,
  readIdentity,
  readMfaChallenge,
  readMfaEnrollment,
  readMfaVerification,
  readTokens,
} from "@/lib/auth-server";
import { clientAddressFrom, trustedProxyHops } from "@/lib/client-address";
import {
  activationPath,
  activationProblem,
  extractActivationToken,
  isSameOrigin,
  openAccess,
  openMfa,
  passwordChangeProblem,
  portalPath,
  resetPath,
  sealAccess,
  sealMfa,
  sealRecovery,
  sessionCookie,
  type Identity,
  type MfaChallengeState,
  type RecoveryCodesState,
} from "@/lib/session-policy";

const noStore = { "Cache-Control": "no-store" };

export async function GET(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  if (action === "status") {
    const { identity } = await currentSession();
    return NextResponse.json(
      {
        authenticated: Boolean(identity),
        destination: identity ? portalPath(identity) : null,
      },
      { headers: noStore }
    );
  }
  return new NextResponse(null, { status: 404, headers: noStore });
}

export async function POST(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  const allowedActions = ["login", "refresh", "logout", "mfa-verify", "mfa-cancel", "mfa-recovery", "activate", "reset-request", "reset-confirm",
    "password-change"];
  if (!allowedActions.includes(action)) {
    return new NextResponse(null, { status: 404, headers: noStore });
  }

  const go = (path: string) => new NextResponse(null, { status: 303, headers: { ...noStore, Location: path } });
  try {
    const { origin, names, secure, key } = config();
    if (!isSameOrigin(request.headers.get("origin"), origin, request.headers.get("sec-fetch-site"))) {
      return new NextResponse(null, { status: 403, headers: noStore });
    }

    const clearAll = (path: string) => {
      const response = go(path);
      for (const name of Object.values(names)) response.cookies.set(name, "", sessionCookie(secure, 0));
      return response;
    };

    const clearMfaOnly = (path: string) => {
      const response = go(path);
      response.cookies.set(names.mfa, "", sessionCookie(secure, 0));
      response.cookies.set(names.recovery, "", sessionCookie(secure, 0));
      return response;
    };

    const refreshToken = request.cookies.get(names.refresh)?.value;
    const client = clientAddressFrom(request.headers.get("x-forwarded-for"), trustedProxyHops());

    if (action === "logout") {
      if (refreshToken) {
        const result = await authRequest("logout", { body: { refreshToken } });
        if (result.status !== 204) return go("/sign-in?error=sign-out");
      }
      return clearAll("/sign-in?signedOut=1");
    }

    if (action === "mfa-cancel") {
      return clearMfaOnly("/sign-in");
    }

    if (action === "mfa-recovery") {
      const response = go("/staff");
      response.cookies.set(names.recovery, "", sessionCookie(secure, 0));
      return response;
    }

    if (action === "login" && refreshToken) return go("/sign-in");
    if (action === "refresh" && !refreshToken) return clearAll("/sign-in?error=expired");

    if (request.headers.get("content-type")?.split(";")[0] !== "application/x-www-form-urlencoded") {
      return new NextResponse(null, { status: 415, headers: noStore });
    }

    const reader = request.body?.pipeThrough(new TextDecoderStream()).getReader();
    let encoded = "";
    if (reader) {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        encoded += value;
        if (encoded.length > 4096) {
          await reader.cancel();
          return new NextResponse(null, { status: 413, headers: noStore });
        }
      }
    }
    const form = new URLSearchParams(encoded);
    const preferred = form.get("portal");
    const safePreferred = preferred === "staff" || preferred === "student" ? preferred : undefined;
    const errorPath = (error: string) =>
      `/sign-in?error=${error}${safePreferred ? `&portal=${safePreferred}` : ""}`;

    if (action === "mfa-verify") {
      const code = form.get("code")?.trim();
      if (!code || code.length > 32) {
        return go("/sign-in/mfa?error=code");
      }

      const sealedMfa = request.cookies.get(names.mfa)?.value;
      const mfaState = sealedMfa ? openMfa(sealedMfa, key) : null;
      if (!mfaState || Date.now() > mfaState.expiresAt) {
        return clearMfaOnly("/sign-in?error=expired");
      }

      const verifyResult = await authRequest("mfa/verify", {
        client,
        body: { mfaToken: mfaState.mfaToken, code },
      });

      if (verifyResult.status === 401 || verifyResult.status === 400) {
        return go("/sign-in/mfa?error=code");
      }
      if (verifyResult.status === 429) return go("/sign-in/mfa?error=limited");
      if (!verifyResult.ok) return go("/sign-in/mfa?error=unavailable");

      const verified = await readMfaVerification(verifyResult);
      let destination: string;
      let identity: Identity | null = null;
      try {
        identity = await readIdentity(verified.accessToken);
        destination = identity ? portalPath(identity, mfaState.portal) : errorPath("expired");
      } catch {
        destination = errorPath("unavailable");
      }

      const hasRecoveryCodes = Array.isArray(verified.recoveryCodes) && verified.recoveryCodes.length > 0;
      const response = go(hasRecoveryCodes ? "/sign-in/mfa/recovery-codes" : destination);
      response.cookies.set(names.access, sealAccess({ token: verified.accessToken, identity }, key), sessionCookie(secure, verified.expiresIn));
      response.cookies.set(names.refresh, verified.refreshToken, sessionCookie(secure, verified.refreshExpiresIn));
      response.cookies.set(names.mfa, "", sessionCookie(secure, 0));

      if (hasRecoveryCodes) {
        const recoveryState: RecoveryCodesState = {
          codes: verified.recoveryCodes!,
          portal: mfaState.portal,
          expiresAt: Date.now() + 300_000,
        };
        response.cookies.set(names.recovery, sealRecovery(recoveryState, key), sessionCookie(secure, 300));
      } else {
        response.cookies.set(names.recovery, "", sessionCookie(secure, 0));
      }
      return response;
    }

    if (action === "activate") {
      // Independent of any session in this browser: it only sets the invited account's password.
      const token = extractActivationToken(form.get("token"));
      const password = form.get("password") ?? "";
      const problem = activationProblem(token, password, form.get("confirm") ?? "");
      if (problem) return go(activationPath(problem, problem === "token" ? null : token));
      const activated = await authRequest("activate", { body: { token, password }, client });
      if (activated.status === 204) return go("/sign-in?activated=1");
      if (activated.status === 400) return go(activationPath("invalid"));
      if (activated.status === 422) return go(activationPath("weak", token));
      if (activated.status === 429) return go(activationPath("limited", token));
      return go(activationPath("unavailable", token));
    }

    if (action === "password-change") {
      // Signed in: the API re-checks the current password, then ends every session, so this browser signs in again.
      const sealed = request.cookies.get(names.access)?.value;
      const access = sealed ? openAccess(sealed, key) : null;
      if (!access) return clearAll("/sign-in?error=expired");
      const current = form.get("current") ?? "";
      const next = form.get("password") ?? "";
      const problem = passwordChangeProblem(current, next, form.get("confirm") ?? "");
      if (problem) return go(`/account/password?error=${problem}`);
      let changed: Response;
      try {
        changed = await fetch(new URL("/api/v1/auth/password", process.env.API_INTERNAL_URL ?? "http://localhost:8080"), {
          method: "POST", cache: "no-store", redirect: "error", signal: AbortSignal.timeout(15000),
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${access}`, ...(client ? { "X-Forwarded-For": client } : {}) },
          body: JSON.stringify({ currentPassword: current, newPassword: next }),
        });
      } catch {
        return go("/account/password?error=unavailable");
      }
      if (changed.status === 204) return clearAll("/sign-in?changed=1");
      if (changed.status === 400) return go("/account/password?error=current");
      if (changed.status === 422) return go("/account/password?error=weak");
      if (changed.status === 429) return go("/account/password?error=limited");
      if (changed.status === 401) return clearAll("/sign-in?error=expired");
      return go("/account/password?error=unavailable");
    }

    if (action === "reset-request") {
      // The address is never put in a URL; every outcome for a well-formed address looks the same.
      const address = form.get("email")?.trim();
      if (!address || address.length > 254) return go("/forgot-password?error=email");
      const requested = await authRequest("password-reset/request", { body: { email: address }, client });
      if (requested.status === 202) return go("/forgot-password?sent=1");
      if (requested.status === 503) return go("/forgot-password?error=disabled");
      if (requested.status === 429) return go("/forgot-password?error=limited");
      return go("/forgot-password?error=unavailable");
    }

    if (action === "reset-confirm") {
      // Like activation, independent of any session here; the backend signs the account out everywhere.
      const token = extractActivationToken(form.get("token"));
      const newPassword = form.get("password") ?? "";
      const problem = activationProblem(token, newPassword, form.get("confirm") ?? "");
      if (problem) return go(resetPath(problem, problem === "token" ? null : token));
      const reset = await authRequest("password-reset/confirm", { body: { token, password: newPassword }, client });
      if (reset.status === 204) return go("/sign-in?reset=1");
      if (reset.status === 400) return go(resetPath("invalid"));
      if (reset.status === 422) return go(resetPath("weak", token));
      if (reset.status === 429) return go(resetPath("limited", token));
      return go(resetPath("unavailable", token));
    }

    const email = form.get("email")?.trim();
    const password = form.get("password");
    if (action === "login" && (!email || email.length > 254 || !password || password.length > 256)) {
      return go(errorPath("invalid"));
    }

    const result = await authRequest(
      action as "login" | "refresh",
      { body: action === "login" ? { email: email!, password: password! } : { refreshToken: refreshToken! }, client }
    );

    if (result.status === 401 || result.status === 400) {
      return action === "login" ? go(errorPath("invalid")) : clearAll(errorPath("expired"));
    }
    if (result.status === 429) return go(errorPath("limited"));
    if (!result.ok) return go(errorPath("unavailable"));

    const data = await result.json();

    if (action === "login" && data?.mfaRequired === true) {
      const challenge = readMfaChallenge(data);
      let secret: string | undefined;
      let otpauthUri: string | undefined;

      if (!challenge.mfaEnrolled) {
        const enrollResult = await authRequest("mfa/enroll", {
          client,
          body: { mfaToken: challenge.mfaToken },
        });
        if (!enrollResult.ok) {
          return go(errorPath("unavailable"));
        }
        const enrollment = await readMfaEnrollment(enrollResult);
        secret = enrollment.secret;
        otpauthUri = enrollment.otpauthUri;
      }

      const mfaState: MfaChallengeState = {
        mfaToken: challenge.mfaToken,
        enrolled: challenge.mfaEnrolled,
        portal: safePreferred ?? null,
        secret,
        otpauthUri,
        expiresAt: Date.now() + challenge.mfaExpiresIn * 1000,
      };

      const response = go("/sign-in/mfa");
      response.cookies.set(names.mfa, sealMfa(mfaState, key), sessionCookie(secure, challenge.mfaExpiresIn));
      if (request.cookies.has(names.access)) response.cookies.set(names.access, "", sessionCookie(secure, 0));
      if (request.cookies.has(names.refresh)) response.cookies.set(names.refresh, "", sessionCookie(secure, 0));
      if (request.cookies.has(names.recovery)) response.cookies.set(names.recovery, "", sessionCookie(secure, 0));
      return response;
    }

    const tokens = typeof data?.accessToken === "string" ? await readTokens(new Response(JSON.stringify(data))) : null;
    if (!tokens) throw new Error("Invalid session response");

    let destination: string;
    let identity: Identity | null = null;
    try {
      identity = await readIdentity(tokens.accessToken);
      destination = identity ? portalPath(identity, safePreferred) : errorPath("expired");
    } catch {
      destination = errorPath("unavailable");
    }

    const response = go(destination);
    response.cookies.set(names.access, sealAccess({ token: tokens.accessToken, identity }, key), sessionCookie(secure, tokens.expiresIn));
    response.cookies.set(names.refresh, tokens.refreshToken, sessionCookie(secure, tokens.refreshExpiresIn));
    if (request.cookies.has(names.mfa)) response.cookies.set(names.mfa, "", sessionCookie(secure, 0));
    if (request.cookies.has(names.recovery)) response.cookies.set(names.recovery, "", sessionCookie(secure, 0));
    return response;
  } catch {
    if (action === "activate") return go(activationPath("unavailable"));
    if (action === "reset-request") return go("/forgot-password?error=unavailable");
    if (action === "reset-confirm") return go(resetPath("unavailable"));
    return go(action === "logout" ? "/sign-in?error=sign-out" : "/sign-in?error=unavailable");
  }
}
