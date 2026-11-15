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
  return {
    access: `${prefix}luma-access`,
    refresh: `${prefix}luma-refresh`,
    mfa: `${prefix}luma-mfa`,
    recovery: `${prefix}luma-recovery`,
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

export type MfaChallengeState = {
  mfaToken: string;
  enrolled: boolean;
  portal?: Portal | null;
  secret?: string;
  otpauthUri?: string;
  expiresAt: number;
};

export type RecoveryCodesState = {
  codes: string[];
  portal?: Portal | null;
  expiresAt: number;
};

const purpose = Buffer.from("luma/bff/access/v1");
const mfaPurpose = Buffer.from("luma/bff/mfa/v1");
const recoveryPurpose = Buffer.from("luma/bff/recovery/v1");

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

export function sealAccess(accessToken: string, key: Buffer) {
  return sealWithPurpose(accessToken, key, purpose);
}

export function openAccess(cookie: string, key: Buffer) {
  return openWithPurpose(cookie, key, purpose);
}

export function sealMfa(state: MfaChallengeState, key: Buffer) {
  return sealWithPurpose(JSON.stringify(state), key, mfaPurpose);
}

export function openMfa(cookie: string, key: Buffer): MfaChallengeState | null {
  const plain = openWithPurpose(cookie, key, mfaPurpose);
  if (!plain) return null;
  try {
    const parsed = JSON.parse(plain);
    if (typeof parsed?.mfaToken !== "string" || !parsed.mfaToken || parsed.mfaToken.length > 128
        || typeof parsed?.enrolled !== "boolean"
        || !Number.isSafeInteger(parsed?.expiresAt) || parsed.expiresAt <= 0) {
      return null;
    }
    if (Date.now() > parsed.expiresAt) return null;
    if (parsed.portal !== undefined && parsed.portal !== null && parsed.portal !== "staff" && parsed.portal !== "student") {
      return null;
    }
    if (parsed.secret !== undefined && (typeof parsed.secret !== "string" || parsed.secret.length > 128)) {
      return null;
    }
    if (parsed.otpauthUri !== undefined && (typeof parsed.otpauthUri !== "string" || parsed.otpauthUri.length > 512)) {
      return null;
    }
    return {
      mfaToken: parsed.mfaToken,
      enrolled: parsed.enrolled,
      portal: parsed.portal ?? null,
      secret: parsed.secret,
      otpauthUri: parsed.otpauthUri,
      expiresAt: parsed.expiresAt,
    };
  } catch {
    return null;
  }
}

export function sealRecovery(state: RecoveryCodesState, key: Buffer) {
  return sealWithPurpose(JSON.stringify(state), key, recoveryPurpose);
}

export function openRecovery(cookie: string, key: Buffer): RecoveryCodesState | null {
  const plain = openWithPurpose(cookie, key, recoveryPurpose);
  if (!plain) return null;
  try {
    const parsed = JSON.parse(plain);
    if (!Array.isArray(parsed?.codes) || parsed.codes.length === 0 || parsed.codes.length > 20
        || !parsed.codes.every((c: unknown) => typeof c === "string" && c.length > 0 && c.length <= 32)
        || !Number.isSafeInteger(parsed?.expiresAt) || parsed.expiresAt <= 0) {
      return null;
    }
    if (Date.now() > parsed.expiresAt) return null;
    if (parsed.portal !== undefined && parsed.portal !== null && parsed.portal !== "staff" && parsed.portal !== "student") {
      return null;
    }
    return {
      codes: parsed.codes,
      portal: parsed.portal ?? null,
      expiresAt: parsed.expiresAt,
    };
  } catch {
    return null;
  }
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
