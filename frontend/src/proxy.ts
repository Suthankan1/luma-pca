import { NextResponse, type NextRequest } from "next/server";
import { config as sessionConfig } from "@/lib/auth-server";
import { clientAddressFrom, trustedProxyHops } from "@/lib/client-address";
import { sealAccess, sessionCookie } from "@/lib/session-policy";
import { cookieHeaderWith, needsRefresh, refreshesOn } from "@/lib/silent-refresh";
import { validateTokens } from "@/lib/auth-parsers";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Named pages that live beside the `[id]` pages and are not IDs. */
const NAMED = new Set(["/staff/students/import", "/staff/students/manage", "/staff/sessions/types"]);
const RECORD_PAGES = /^\/(staff\/(sessions|students|team|batches)|student\/classes)\/[^/]+$/;

type Renewed = { sealed: string; refreshToken: string; expiresIn: number; refreshExpiresIn: number } | "expired" | null;
/**
 * Renewals in progress, by refresh token: a page and the pages it prefetches arrive together with the same expired
 * session, and share one renewal instead of each asking the API.
 */
const inFlight = new Map<string, Promise<Renewed>>();

/**
 * Runs before every page:
 * 1. A malformed record link gets a real 404 before any rendering starts (pages stream a loading screen at once, after
 *    which the status is already sent).
 * 2. A signed-in person whose short access token has expired is renewed quietly with the refresh cookie, so nobody is
 *    sent to the sign-in page while their session lasts. Signing out still ends it at once: the API checks the session
 *    on every request, and sign-out revokes it and clears both cookies.
 */
export async function proxy(request: NextRequest) {
  const started = performance.now();
  const response = await routeRequest(request);
  response.headers.append("Server-Timing", `next-proxy;dur=${(performance.now() - started).toFixed(1)}`);
  return response;
}

async function routeRequest(request: NextRequest) {
  const { pathname } = request.nextUrl;
  if (RECORD_PAGES.test(pathname)) {
    const id = pathname.split("/")[3];
    if (id && !UUID.test(id) && !NAMED.has(pathname)) {
      return NextResponse.rewrite(new URL("/_not-found", request.url), { status: 404 });
    }
  }
  if (!refreshesOn(pathname)) return NextResponse.next();

  let session;
  try {
    session = sessionConfig();
  } catch {
    return NextResponse.next();
  }
  const { names, secure, key } = session;
  if (!needsRefresh(name => request.cookies.has(name), names)) return NextResponse.next();

  const presented = request.cookies.get(names.refresh)!.value;
  let renewal = inFlight.get(presented);
  if (!renewal) {
    renewal = renew(presented, clientAddressFrom(request.headers.get("x-forwarded-for"), trustedProxyHops()), key);
    inFlight.set(presented, renewal);
    // A success is kept briefly so requests a moment behind reuse it; a failure (for example the API briefly down) is
    // forgotten at once so the next page load tries again.
    renewal.then(result => {
      if (result && result !== "expired") setTimeout(() => inFlight.delete(presented), 5000);
      else inFlight.delete(presented);
    });
  }
  const renewed = await renewal;
  if (!renewed) return NextResponse.next();
  if (renewed === "expired") {
    // Protected requests still go through their normal access checks. Clear stale state on sign-in so the form can open.
    if (pathname !== "/sign-in") return NextResponse.next();
    const headers = new Headers(request.headers);
    const remaining = request.cookies.getAll().filter(cookie => cookie.name !== names.refresh);
    headers.set("cookie", remaining.map(cookie => `${cookie.name}=${cookie.value}`).join("; "));
    const response = NextResponse.next({ request: { headers } });
    response.cookies.set(names.refresh, "", sessionCookie(secure, 0));
    return response;
  }
  const headers = new Headers(request.headers);
  headers.set("cookie", cookieHeaderWith(request.headers.get("cookie"), { [names.access]: renewed.sealed, [names.refresh]: renewed.refreshToken }));
  const response = NextResponse.next({ request: { headers } });
  response.cookies.set(names.access, renewed.sealed, sessionCookie(secure, renewed.expiresIn));
  response.cookies.set(names.refresh, renewed.refreshToken, sessionCookie(secure, renewed.refreshExpiresIn));
  return response;
}

async function renew(refreshToken: string, client: string | null, key: Buffer): Promise<Renewed> {
  const api = process.env.API_INTERNAL_URL ?? "http://localhost:8080";
  try {
    const refreshed = await fetch(new URL("/api/v1/auth/refresh", api), {
      method: "POST", cache: "no-store", redirect: "error", signal: AbortSignal.timeout(10000),
      headers: { "Content-Type": "application/json", ...(client ? { "X-Forwarded-For": client } : {}) },
      body: JSON.stringify({ refreshToken }),
    });
    if (refreshed.status === 401) return "expired";
    if (!refreshed.ok) return null;
    const tokens = validateTokens(await refreshed.json());
    // The page checks the renewed token with the API before showing any protected content.
    return { sealed: sealAccess({ token: tokens.accessToken, identity: null }, key), refreshToken: tokens.refreshToken,
      expiresIn: tokens.expiresIn, refreshExpiresIn: tokens.refreshExpiresIn };
  } catch {
    return null;
  }
}

export const config = {
  // Every page and route handler except Next.js's own files and static assets.
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\.(?:png|jpg|jpeg|svg|ico|webp|css|js|map|txt)$).*)"],
};
