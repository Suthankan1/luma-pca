"use server";

import { config } from "@/lib/auth-server";
import { requireSameOriginAction, staffApi } from "@/lib/staff-api";
import { problemMessage, uuidOrNull, type FormState } from "@/lib/staff-forms";

/**
 * Issues an activation invitation for a pending staff or student account. With delivery
 * EMAIL the API emails the link to the account's address and never returns it; otherwise
 * the link is returned once, to the staff member only. The web app stores neither;
 * issuing again cancels the previous link. The API decides who may invite whom.
 */
export async function issueInvitation(_previous: FormState, form: FormData): Promise<FormState> {
  await requireSameOriginAction();
  const accountId = uuidOrNull(form.get("accountId"));
  const delivery = form.get("delivery") === "EMAIL" ? "EMAIL" : "LINK";
  if (!accountId) return { ok: false, message: "That request was not valid. Reload the page." };
  const result = await staffApi<{ activationPath: string | null; expiresAt: string; delivery: string }>(
    `/api/v1/accounts/${accountId}/invitations`, { method: "POST", body: { delivery } });
  if (!result.ok) {
    const message = result.status === 503 ? "Email isn’t set up for the academy yet. Create a link to copy instead."
      : problemMessage(result.status, result.detail);
    return { ok: false, message };
  }
  if (delivery === "EMAIL") {
    return { ok: true, message: "Invitation emailed.", values: { delivery, expiresAt: result.data.expiresAt } };
  }
  if (!/^\/activate#token=[A-Za-z0-9_-]{20,128}$/.test(result.data.activationPath ?? "")) {
    return { ok: false, message: problemMessage(502, undefined) };
  }
  return { ok: true, message: "Invitation created.",
    values: { delivery, link: `${config().origin}${result.data.activationPath}`, expiresAt: result.data.expiresAt } };
}
