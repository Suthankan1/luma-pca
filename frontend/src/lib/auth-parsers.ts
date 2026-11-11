export type SessionTokens = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  refreshExpiresIn: number;
};

export type MfaChallengeResponse = {
  mfaRequired: true;
  mfaToken: string;
  mfaExpiresIn: number;
  mfaEnrolled: boolean;
};

export type MfaEnrollmentResponse = {
  secret: string;
  otpauthUri: string;
};

export type MfaVerifiedTokens = SessionTokens & {
  recoveryCodes: string[] | null;
};

export function validateTokens(data: unknown): SessionTokens {
  const record = data as Record<string, unknown> | null;
  if (
    typeof record?.accessToken !== "string" ||
    !record.accessToken ||
    record.accessToken.length > 2750 ||
    typeof record?.refreshToken !== "string" ||
    !record.refreshToken ||
    record.refreshToken.length > 256 ||
    !Number.isSafeInteger(record?.expiresIn) ||
    (record.expiresIn as number) <= 0 ||
    !Number.isSafeInteger(record?.refreshExpiresIn) ||
    (record.refreshExpiresIn as number) <= 0
  ) {
    throw new Error("Invalid session response");
  }
  return {
    accessToken: record.accessToken,
    refreshToken: record.refreshToken,
    expiresIn: record.expiresIn as number,
    refreshExpiresIn: record.refreshExpiresIn as number,
  };
}

export async function readTokens(response: Response): Promise<SessionTokens> {
  const data = await response.json();
  return validateTokens(data);
}

export function readMfaChallenge(data: unknown): MfaChallengeResponse {
  const record = data as Record<string, unknown> | null;
  if (
    record?.mfaRequired !== true ||
    typeof record?.mfaToken !== "string" ||
    !record.mfaToken ||
    record.mfaToken.length > 128 ||
    !Number.isSafeInteger(record?.mfaExpiresIn) ||
    (record.mfaExpiresIn as number) <= 0 ||
    typeof record?.mfaEnrolled !== "boolean"
  ) {
    throw new Error("Invalid MFA challenge response");
  }
  return {
    mfaRequired: true,
    mfaToken: record.mfaToken,
    mfaExpiresIn: record.mfaExpiresIn as number,
    mfaEnrolled: record.mfaEnrolled,
  };
}

export async function readMfaEnrollment(response: Response): Promise<MfaEnrollmentResponse> {
  const data = await response.json();
  if (
    typeof data?.secret !== "string" ||
    !data.secret ||
    data.secret.length > 128 ||
    typeof data?.otpauthUri !== "string" ||
    !data.otpauthUri.startsWith("otpauth://totp/") ||
    data.otpauthUri.length > 512
  ) {
    throw new Error("Invalid MFA enrollment response");
  }
  return {
    secret: data.secret,
    otpauthUri: data.otpauthUri,
  };
}

export async function readMfaVerification(response: Response): Promise<MfaVerifiedTokens> {
  const data = await response.json();
  const tokens = validateTokens(data);
  let recoveryCodes: string[] | null = null;
  if (data?.recoveryCodes !== undefined && data?.recoveryCodes !== null) {
    if (
      !Array.isArray(data.recoveryCodes) ||
      !data.recoveryCodes.every((c: unknown) => typeof c === "string" && c.length > 0 && c.length <= 32)
    ) {
      throw new Error("Invalid recovery codes in verification response");
    }
    recoveryCodes = data.recoveryCodes;
  }
  return {
    ...tokens,
    recoveryCodes,
  };
}
