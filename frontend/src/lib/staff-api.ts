import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
import { config } from "./auth-server";
import { isSameOrigin, openAccess } from "./session-policy";
import { logIfSlow } from "./slow-log";

/**
 * Server-side calls from staff screens to the LUMA API, as the signed-in person.
 * The access token never leaves the server: it is read from the sealed HttpOnly
 * cookie and sent only to API_INTERNAL_URL. The API enforces every permission; this
 * module only makes the call.
 */
export type ApiResult<T> = { ok: true; status: number; data: T } | { ok: false; status: number; detail?: string };

async function accessToken(portal: "staff" | "student") {
  const { names, key } = config();
  const sealed = (await cookies()).get(names.access)?.value;
  const access = sealed ? openAccess(sealed, key) : null;
  // An expired access token with a live refresh cookie shows "Continue session" on sign-in.
  if (!access) redirect(`/sign-in?portal=${portal}`);
  return access;
}

/** @param timeoutMs longer only for bulk requests such as a student import */
export function staffApi<T>(path: `/api/v1/${string}`,
                            init?: { method: "POST" | "PUT"; body: unknown } | { method: "DELETE" }, timeoutMs = 10000): Promise<ApiResult<T>> {
  return callApi<T>("staff", path, init, timeoutMs);
}

/** The student portal reaches only the signed-in student's own records (`/api/v1/me/...`), such as an appeal. */
export function studentApi<T>(path: `/api/v1/me/${string}`, init?: { method: "POST" | "PUT"; body: unknown }, timeoutMs = 10000): Promise<ApiResult<T>> {
  return callApi<T>("student", path, init, timeoutMs);
}

async function callApi<T>(portal: "staff" | "student", path: `/api/v1/${string}`,
                          init?: { method: "POST" | "PUT"; body: unknown } | { method: "DELETE" }, timeoutMs = 10000): Promise<ApiResult<T>> {
  const requestBody = init && "body" in init ? JSON.stringify(init.body) : undefined;
  const access = await accessToken(portal);
  const started = performance.now();
  let response: Response;
  try {
    response = await fetch(new URL(path, process.env.API_INTERNAL_URL ?? "http://localhost:8080"), {
      method: init?.method ?? "GET",
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(timeoutMs),
      headers: { Authorization: `Bearer ${access}`, ...(requestBody ? { "Content-Type": "application/json" } : {}) },
      body: requestBody,
    });
  } catch {
    logIfSlow(init?.method ?? "GET", path, 503, performance.now() - started);
    return { ok: false, status: 503 };
  }
  if (response.status === 401) redirect(`/sign-in?portal=${portal}&error=expired`);
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  logIfSlow(init?.method ?? "GET", path, response.status, performance.now() - started);
  if (!response.ok) {
    const detail = typeof body?.detail === "string" ? body.detail.slice(0, 300) : undefined;
    return { ok: false, status: response.status, detail };
  }
  return { ok: true, status: response.status, data: body as T };
}

/**
 * A file download (an attendance CSV) for a route handler, as the signed-in staff member.
 * The API decides access; only its file headers are passed on, and never cached.
 */
export async function staffDownload(path: `/api/v1/${string}`): Promise<Response> {
  const access = await accessToken("staff");
  let response: Response;
  try {
    response = await fetch(new URL(path, process.env.API_INTERNAL_URL ?? "http://localhost:8080"), {
      cache: "no-store", redirect: "error", signal: AbortSignal.timeout(30000), headers: { Authorization: `Bearer ${access}` },
    });
  } catch {
    return new Response("The export could not be prepared right now. Please try again shortly.", { status: 503, headers: plainText });
  }
  if (response.status === 401) redirect("/sign-in?portal=staff&error=expired");
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    const detail = response.status === 400 && typeof body?.detail === "string" ? body.detail.slice(0, 300)
      : response.status === 403 || response.status === 404 ? "You can’t export this." : "The export could not be prepared right now.";
    return new Response(detail, { status: response.status, headers: plainText });
  }
  return new Response(response.body, { status: 200, headers: {
    "Content-Type": response.headers.get("Content-Type") ?? "text/csv; charset=utf-8",
    "Content-Disposition": response.headers.get("Content-Disposition") ?? "attachment; filename=\"attendance.csv\"",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  } });
}

const plainText = { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };

/**
 * Server Actions already require the Origin to match the Host. This repeats the
 * check against the configured public origin, as the session routes do, so a
 * misconfigured proxy cannot widen it.
 */
export async function requireSameOriginAction() {
  const requestHeaders = await headers();
  if (!isSameOrigin(requestHeaders.get("origin"), config().origin, requestHeaders.get("sec-fetch-site"))) {
    throw new Error("Cross-origin request refused");
  }
}
