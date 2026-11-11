import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

export const staffRoles = ["ACADEMY_OWNER", "ACADEMY_ADMIN", "INSTRUCTOR", "ATTENDANCE_OFFICER", "ANALYST"];
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
  return { access: `${prefix}luma-access`, refresh: `${prefix}luma-refresh` };
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

export function sealAccess(accessToken: string, key: Buffer) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(purpose);
  const encrypted = Buffer.concat([cipher.update(accessToken, "utf8"), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString("base64url");
}

export function openAccess(cookie: string, key: Buffer) {
  if (!/^[A-Za-z0-9_-]{39,3800}$/.test(cookie)) return null;
  try {
    const decoded = Buffer.from(cookie, "base64url");
    if (decoded.length < 29 || decoded.toString("base64url") !== cookie) return null;
    const decipher = createDecipheriv("aes-256-gcm", key, decoded.subarray(0, 12));
    decipher.setAAD(purpose);
    decipher.setAuthTag(decoded.subarray(12, 28));
    return Buffer.concat([decipher.update(decoded.subarray(28)), decipher.final()]).toString("utf8");
  } catch {
    return null;
  }
}
