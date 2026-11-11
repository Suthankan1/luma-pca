import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { cookieNames, hasPortal, openAccess, portalPath, sessionKey, type Identity, type Portal } from "./session-policy";

export type SessionTokens = { accessToken: string; refreshToken: string; expiresIn: number; refreshExpiresIn: number };

export function config() {
  const origin = new URL(process.env.LUMA_WEB_ORIGIN ?? "http://localhost:3000");
  if (origin.username || origin.password || origin.pathname !== "/" || origin.search || origin.hash) {
    throw new Error("LUMA_WEB_ORIGIN must be an origin without a path or credentials");
  }
  const secure = origin.protocol === "https:";
  if (origin.protocol !== "http:" && !secure) throw new Error("Invalid LUMA_WEB_ORIGIN");
  if (!secure && (process.env.NODE_ENV === "production" || !["localhost", "127.0.0.1", "[::1]"].includes(origin.hostname))) {
    throw new Error("LUMA_WEB_ORIGIN must use HTTPS except for local development");
  }
  return { origin: origin.origin, secure, names: cookieNames(secure), key: sessionKey(process.env.LUMA_SESSION_KEY) };
}

export async function authRequest(path: "login" | "refresh" | "logout" | "me", options?: { body: object } | { access: string }) {
  const url = new URL(`/api/v1/auth/${path}`, process.env.API_INTERNAL_URL ?? "http://localhost:8080");
  return fetch(url, {
    cache: "no-store",
    redirect: "error",
    signal: AbortSignal.timeout(5000),
    ...(options && "body" in options
      ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(options.body) }
      : { headers: options ? { Authorization: `Bearer ${options.access}` } : undefined }),
  });
}

export async function readIdentity(access: string): Promise<Identity | null> {
  const result = await authRequest("me", { access });
  if (result.status === 401 || result.status === 403) return null;
  if (!result.ok) throw new Error("Identity service unavailable");
  const data = await result.json();
  if (typeof data?.accountId !== "string" || typeof data?.academyId !== "string"
      || !Array.isArray(data?.roles) || !data.roles.every((role: unknown) => typeof role === "string")) {
    throw new Error("Invalid identity response");
  }
  return data;
}

export async function readTokens(response: Response): Promise<SessionTokens> {
  const data = await response.json();
  if (typeof data?.accessToken !== "string" || !data.accessToken || data.accessToken.length > 2750
      || typeof data?.refreshToken !== "string" || !data.refreshToken || data.refreshToken.length > 256
      || !Number.isSafeInteger(data.expiresIn) || data.expiresIn <= 0
      || !Number.isSafeInteger(data.refreshExpiresIn) || data.refreshExpiresIn <= 0) {
    throw new Error("Invalid session response");
  }
  return data;
}

export async function currentSession() {
  const cookieJar = await cookies();
  const hasCookie = (kind: "access" | "refresh") => [false, true].some(secure => cookieJar.has(cookieNames(secure)[kind]));
  if (!hasCookie("access") && !hasCookie("refresh")) return { identity: null, canRefresh: false, unavailable: false };
  try {
    const { names, key } = config();
    const sealed = cookieJar.get(names.access)?.value;
    const access = sealed && openAccess(sealed, key);
    return {
      identity: access ? await readIdentity(access) : null,
      canRefresh: cookieJar.has(names.refresh),
      unavailable: false,
    };
  } catch {
    return { identity: null, canRefresh: hasCookie("refresh"), unavailable: true };
  }
}

export async function requirePortal(portal: Portal) {
  const { identity, unavailable } = await currentSession();
  if (!identity) redirect(`/sign-in?portal=${portal}${unavailable ? "&error=unavailable" : ""}`);
  if (!hasPortal(identity, portal)) redirect(portalPath(identity));
  return identity;
}
