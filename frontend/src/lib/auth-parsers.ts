export type SessionTokens = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  refreshExpiresIn: number;
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
