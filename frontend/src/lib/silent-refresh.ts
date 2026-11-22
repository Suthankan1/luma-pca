/** Pure helpers for renewing a signed-in session in the proxy, before a page renders. Unit-tested. */

/**
 * The access cookie lives exactly as long as the short access token (minutes). When it is gone but the refresh cookie
 * is still there, the person is still signed in: renew quietly instead of sending them to the sign-in page.
 */
export function needsRefresh(has: (name: string) => boolean, names: { access: string; refresh: string }) {
  return !has(names.access) && has(names.refresh);
}

/** Session handlers and static assets do not need proxy renewal. */
export function refreshesOn(pathname: string) {
  return !pathname.startsWith("/api/session/") && !pathname.startsWith("/_next/") && !/\.[a-z0-9]+$/i.test(pathname);
}

/** The request's Cookie header with some cookies replaced or added, so this very request already sees the new session. */
export function cookieHeaderWith(original: string | null, updates: Record<string, string>) {
  const kept = (original ?? "").split(";").map(part => part.trim()).filter(Boolean)
    .filter(part => !Object.hasOwn(updates, part.slice(0, part.indexOf("=") < 0 ? part.length : part.indexOf("="))));
  return [...kept, ...Object.entries(updates).map(([name, value]) => `${name}=${value}`)].join("; ");
}
