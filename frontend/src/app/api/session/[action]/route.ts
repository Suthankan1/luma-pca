import { NextRequest, NextResponse } from "next/server";
import {
  authRequest,
  config,
  currentSession,
  readIdentity,
  readTokens,
  studentIdLoginEnabled,
} from "@/lib/auth-server";
import { clientAddressFrom, trustedProxyHops } from "@/lib/client-address";
import {
  activationPath,
  activationProblem,
  extractActivationToken,
  hasPortal,
  isSameOrigin,
  openAccess,
  passwordChangeProblem,
  portalPath,
  resetPath,
  sealAccess,
  sessionCookie,
  type Identity,
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
  const allowedActions = ["login", "student-login", "refresh", "logout", "activate", "reset-request", "reset-confirm",
    "password-change"];
  if (!allowedActions.includes(action)) {
    return new NextResponse(null, { status: 404, headers: noStore });
  }

  const go = (path: string) => new NextResponse(null, { status: 303, headers: { ...noStore, Location: path } });
  let safePreferred: "staff" | "student" | undefined = action === "student-login" ? "student" : undefined;
  const signInPath = (error?: string) => {
    const params = new URLSearchParams();
    if (error) params.set("error", error);
    if (safePreferred) params.set("portal", safePreferred);
    return params.size ? `/sign-in?${params}` : "/sign-in";
  };
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

    const refreshToken = request.cookies.get(names.refresh)?.value;
    const client = clientAddressFrom(request.headers.get("x-forwarded-for"), trustedProxyHops());

    if (action === "logout") {
      if (refreshToken) {
        const result = await authRequest("logout", { body: { refreshToken } });
        if (result.status !== 204) return go("/sign-in?error=sign-out");
      }
      return clearAll("/?signedOut=1");
    }

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
    if (action !== "student-login" && (preferred === "staff" || preferred === "student")) safePreferred = preferred;
    if ((action === "login" || action === "student-login") && refreshToken) return go(signInPath());
    if (action === "refresh" && !refreshToken) return clearAll(signInPath("expired"));

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
    const studentId = form.get("studentId")?.trim();
    if (action === "login" && (!email || email.length > 254 || !password || password.length > 256)) {
      return go(signInPath("invalid"));
    }
    if (action === "student-login") {
      if (!studentIdLoginEnabled()) return new NextResponse(null, { status: 404, headers: noStore });
      if (!studentId || studentId.length > 60) return go(signInPath("student"));
    }

    const result = action === "student-login"
      ? await authRequest("student-id-login", { body: { studentId: studentId! }, client })
      : await authRequest(
        action as "login" | "refresh",
        { body: action === "login" ? { email: email!, password: password! } : { refreshToken: refreshToken! }, client }
      );

    if (result.status === 401 || result.status === 400) {
      if (action === "student-login") return go(signInPath("student"));
      return action === "login" ? go(signInPath("invalid")) : clearAll(signInPath("expired"));
    }
    if (result.status === 429) return go(signInPath("limited"));
    if (!result.ok) return go(signInPath("unavailable"));

    const tokens = await readTokens(result);

    let destination: string;
    let identity: Identity | null = null;
    try {
      identity = await readIdentity(tokens.accessToken);
      destination = identity ? portalPath(identity, safePreferred) : signInPath("expired");
    } catch {
      destination = signInPath("unavailable");
    }

    const wrongPortal = identity && safePreferred && !hasPortal(identity, safePreferred);
    if (action !== "refresh" && (wrongPortal || !identity || destination.startsWith("/sign-in"))) {
      const revoked = await authRequest("logout", { body: { refreshToken: tokens.refreshToken } });
      if (revoked.status !== 204) return go(signInPath("unavailable"));
      return go(wrongPortal ? signInPath("portal") : destination);
    }

    const response = go(wrongPortal ? signInPath("portal") : destination);
    response.cookies.set(names.access, sealAccess({ token: tokens.accessToken, identity }, key), sessionCookie(secure, tokens.expiresIn));
    response.cookies.set(names.refresh, tokens.refreshToken, sessionCookie(secure, tokens.refreshExpiresIn));
    return response;
  } catch {
    if (action === "activate") return go(activationPath("unavailable"));
    if (action === "reset-request") return go("/forgot-password?error=unavailable");
    if (action === "reset-confirm") return go(resetPath("unavailable"));
    return go(action === "logout" ? "/sign-in?error=sign-out" : signInPath("unavailable"));
  }
}
