"use server";

import { config } from "@/lib/auth-server";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { problemMessage, uuidOrNull, type FormState } from "@/lib/staff-forms";

/**
 * Issues an activation invitation for a pending staff or student account and returns
 * its link once, to the administrator only. The web app does not store it; issuing
 * again cancels the previous link. The API decides who may invite whom.
 */
export async function issueInvitation(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const accountId = uuidOrNull(form.get("accountId"));
  if (!accountId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi<{ activationPath: string; expiresAt: string }>(`/api/v1/accounts/${accountId}/invitations`,
    { method: "POST", body: {} });
  if (!result.ok) return { ok: false, message: problemMessage(result.status, result.detail) };
  if (!/^\/activate#token=[A-Za-z0-9_-]{20,128}$/.test(result.data.activationPath)) {
    return { ok: false, message: problemMessage(502, undefined) };
  }
  return { ok: true, message: "Invitation created.",
    values: { link: `${config().origin}${result.data.activationPath}`, expiresAt: result.data.expiresAt } };
}
