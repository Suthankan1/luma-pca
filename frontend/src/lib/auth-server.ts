import { cache } from "react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  cookieNames,
  hasPortal,
  openAccessSession,
  openMfa,
  openRecovery,
  portalPath,
  sessionKey,
  type Identity,
  type MfaChallengeState,
  type Portal,
  type RecoveryCodesState,
} from "./session-policy";
import {
  type SessionTokens,
  type MfaChallengeResponse,
  type MfaEnrollmentResponse,
  type MfaVerifiedTokens,
} from "./auth-parsers";

export type { SessionTokens, MfaChallengeResponse, MfaEnrollmentResponse, MfaVerifiedTokens };
export {
  validateTokens,
  readTokens,
  readMfaChallenge,
  readMfaEnrollment,
  readMfaVerification,
} from "./auth-parsers";

export function config() {
  const origin = new URL(process.env.LUMA_WEB_ORIGIN ?? "http://localhost:3000");
  if (origin.username || origin.password || origin.pathname !== "/" || origin.search || origin.hash) {
    throw new Error("LUMA_WEB_ORIGIN must be an origin without a path or credentials");
  }
  const secure = origin.protocol === "https:";
  if (origin.protocol !== "http:" && !secure) throw new Error("Invalid LUMA_WEB_ORIGIN");
  if (!secure && !["localhost", "127.0.0.1", "[::1]"].includes(origin.hostname)) {
    throw new Error("LUMA_WEB_ORIGIN must use HTTPS except for local development");
  }
  return { origin: origin.origin, secure, names: cookieNames(secure), key: sessionKey(process.env.LUMA_SESSION_KEY) };
}

export async function authRequest(
  path: "login" | "refresh" | "logout" | "me" | "mfa/enroll" | "mfa/verify" | "activate"
    | "password-reset/request" | "password-reset/confirm",
  options?: ({ body: object } | { access: string }) & { client?: string | null }
) {
  const url = new URL(`/api/v1/auth/${path}`, process.env.API_INTERNAL_URL ?? "http://localhost:8080");
  const timeoutMs = Number(process.env.API_AUTH_TIMEOUT_MS ?? 15000);
  return fetch(url, {
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(timeoutMs),
    // The visitor's address, for the API's per-client limits; the API trusts it only from configured proxies.
    ...(options && "body" in options
      ? { method: "POST", headers: { "Content-Type": "application/json", ...(options.client ? { "X-Forwarded-For": options.client } : {}) },
          body: JSON.stringify(options.body) }
      : { headers: options ? { Authorization: `Bearer ${options.access}` } : undefined }),
  });
}

export const readIdentity = cache(async (access: string): Promise<Identity | null> => {
  const result = await authRequest("me", { access });
  if (result.status === 401 || result.status === 403) return null;
  if (!result.ok) throw new Error("Identity service unavailable");
  const data = await result.json();
  if (typeof data?.accountId !== "string" || typeof data?.academyId !== "string"
      || !Array.isArray(data?.roles) || !data.roles.every((role: unknown) => typeof role === "string")) {
    throw new Error("Invalid identity response");
  }
  return data;
});

export async function currentSession() {
  const cookieJar = await cookies();
  const hasCookie = (kind: "access" | "refresh") => [false, true].some(secure => cookieJar.has(cookieNames(secure)[kind]));
  if (!hasCookie("access") && !hasCookie("refresh")) return { identity: null, canRefresh: false, unavailable: false };
  try {
    const { names, key } = config();
    const sealed = cookieJar.get(names.access)?.value;
    const session = sealed ? openAccessSession(sealed, key) : null;
    const identity = session?.identity ?? (session?.token ? await readIdentity(session.token) : null);
    return {
      identity,
      canRefresh: cookieJar.has(names.refresh),
      unavailable: false,
    };
  } catch {
    return { identity: null, canRefresh: hasCookie("refresh"), unavailable: true };
  }
}

export async function currentMfaChallenge(): Promise<MfaChallengeState | null> {
  const cookieJar = await cookies();
  try {
    const { names, key } = config();
    const sealed = cookieJar.get(names.mfa)?.value;
    return sealed ? openMfa(sealed, key) : null;
  } catch {
    return null;
  }
}

export async function currentRecoveryCodes(): Promise<RecoveryCodesState | null> {
  const cookieJar = await cookies();
  try {
    const { names, key } = config();
    const sealed = cookieJar.get(names.recovery)?.value;
    return sealed ? openRecovery(sealed, key) : null;
  } catch {
    return null;
  }
}

export async function requirePortal(portal: Portal) {
  const { identity, unavailable } = await currentSession();
  if (!identity) redirect(`/sign-in?portal=${portal}${unavailable ? "&error=unavailable" : ""}`);
  if (!hasPortal(identity, portal)) redirect(portalPath(identity));
  return identity;
}
