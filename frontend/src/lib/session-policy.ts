import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

/** LUMA has two roles (owner decision 2026-11-20): STAFF opens the staff portal, STUDENT the student portal. */
export const staffRoles = ["STAFF"];
export type Portal = "staff" | "student";
export type Identity = { accountId: string; academyId: string; roles: string[] };

export function hasPortal(identity: Identity, portal: Portal) {
  return portal === "student"
    ? identity.roles.includes("STUDENT")
    : identity.roles.some(role => staffRoles.includes(role));
}

export function portalPath(identity: Identity, preferred?: unknown) {
  if ((preferred === "staff" || preferred === "student") && hasPortal(identity, preferred)) return `/${preferred}`;
  if (hasPortal(identity, "staff")) return "/staff";
  if (hasPortal(identity, "student")) return "/student";
  return "/sign-in?error=access";
}

export function isSameOrigin(origin: string | null, configuredOrigin: string, fetchSite: string | null) {
  return origin === configuredOrigin && (fetchSite === null || fetchSite === "same-origin");
}

export function cookieNames(secure: boolean) {
  const prefix = secure ? "__Host-" : "";
  return {
    access: `${prefix}luma-access`,
    refresh: `${prefix}luma-refresh`,
  };
}

export function sessionCookie(secure: boolean, maxAge: number) {
  return { path: "/", httpOnly: true, secure, sameSite: "strict" as const, maxAge };
}

export function sessionKey(value: string | undefined) {
  if (!value || !/^[A-Za-z0-9_-]{43}$/.test(value)) throw new Error("Invalid LUMA_SESSION_KEY");
  const key = Buffer.from(value, "base64url");
  if (key.toString("base64url") !== value) throw new Error("Invalid LUMA_SESSION_KEY");
  return key;
}

const purpose = Buffer.from("luma/bff/access/v1");

function sealWithPurpose(data: string, key: Buffer, aad: Buffer) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(aad);
  const encrypted = Buffer.concat([cipher.update(data, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString("base64url");
}

function openWithPurpose(cookie: string, key: Buffer, aad: Buffer) {
  if (!/^[A-Za-z0-9_-]{39,3800}$/.test(cookie)) return null;
  try {
    const decoded = Buffer.from(cookie, "base64url");
    if (decoded.length < 29 || decoded.toString("base64url") !== cookie) return null;
    const decipher = createDecipheriv("aes-256-gcm", key, decoded.subarray(0, 12));
    decipher.setAAD(aad);
    decipher.setAuthTag(decoded.subarray(12, 28));
    return Buffer.concat([decipher.update(decoded.subarray(28)), decipher.final()]).toString("utf8");
  } catch {
    return null;
  }
}

export interface AccessSession {
  token: string;
  identity?: Identity | null;
}

export function sealAccess(accessTokenOrSession: string | AccessSession, key: Buffer) {
  const payload = typeof accessTokenOrSession === "string"
    ? accessTokenOrSession
    : JSON.stringify(accessTokenOrSession);
  return sealWithPurpose(payload, key, purpose);
}

export function openAccessSession(cookie: string, key: Buffer): AccessSession | null {
  const plain = openWithPurpose(cookie, key, purpose);
  if (!plain) return null;
  if (plain.startsWith("{")) {
    try {
      const parsed = JSON.parse(plain);
      if (typeof parsed?.token === "string") {
        return { token: parsed.token, identity: parsed.identity ?? null };
      }
    } catch {
      // not JSON, fallback to plain token string
    }
  }
  return { token: plain, identity: null };
}

export function openAccess(cookie: string, key: Buffer): string | null {
  const session = openAccessSession(cookie, key);
  return session ? session.token : null;
}

const activationTokenShape = /^[A-Za-z0-9_-]{20,128}$/;

/**
 * The activation code from what the person entered: the bare code, or a whole
 * invitation link ending in `#token=<code>`. Anything else is rejected.
 */
export function extractActivationToken(input: string | null | undefined): string | null {
  if (!input) return null;
  let value = input.trim();
  const marker = value.lastIndexOf("token=");
  if (marker >= 0) value = value.slice(marker + "token=".length);
  value = value.split(/[&#\s]/)[0];
  return activationTokenShape.test(value) ? value : null;
}

export type ActivationProblem = "token" | "mismatch" | "weak";

/** Checks that need no server; the backend still applies the full password policy. */
export function activationProblem(token: string | null, password: string, confirm: string): ActivationProblem | null {
  if (!token) return "token";
  if (password !== confirm) return "mismatch";
  const length = [...password].length;
  if (length < 12 || length > 128) return "weak";
  return null;
}

/** The reset page, like the activation page: the code stays in the fragment. */
export function resetPath(error?: string, token?: string | null) {
  return `/reset-password${error ? `?error=${encodeURIComponent(error)}` : ""}${token ? `#token=${token}` : ""}`;
}

/** The activation page, keeping the code in the fragment so it never reaches a server or log. */
export function activationPath(error?: string, token?: string | null) {
  return `/activate${error ? `?error=${encodeURIComponent(error)}` : ""}${token ? `#token=${token}` : ""}`;
}

/** Checks before asking the API to change a password: the current one given, the new one confirmed and 12–128 characters. */
export function passwordChangeProblem(current: string, next: string, confirm: string): "current" | "mismatch" | "weak" | "same" | null {
  if (!current) return "current";
  if (next !== confirm) return "mismatch";
  const length = [...next].length;
  if (length < 12 || length > 128) return "weak";
  if (next === current) return "same";
  return null;
}

/** Paths where an unauthenticated or guest visitor sits, which should redirect once another tab signs in. */
export function isGuestPath(pathname: string): boolean {
  const clean = pathname.split(/[?#]/)[0];
  return (
    clean === "/" ||
    clean === "/sign-in" ||
    clean === "/activate" ||
    clean === "/forgot-password" ||
    clean === "/reset-password"
  );
}

