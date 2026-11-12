import { isIP } from "node:net";

/**
 * The visitor's address as seen by the proxies in front of this server, for the API's
 * per-client limits. `hops` is how many trusted proxies (load balancers) sit in front of
 * Next.js: each appends the address it received from, so the visitor is the hops-th entry
 * from the right. Entries further left were written by the client and are never trusted.
 * With 0 hops (the default, also when nothing sits in front) there is nothing to trust and
 * no address is forwarded; the API then counts all visitors as this server.
 */
export function clientAddressFrom(forwardedFor: string | null, hops: number): string | null {
  if (!forwardedFor || !Number.isInteger(hops) || hops < 1 || hops > 5) return null;
  const entries = forwardedFor.split(",").map(entry => entry.trim());
  if (entries.length < hops) return null;
  const candidate = entries[entries.length - hops];
  return isIP(candidate) ? candidate : null;
}

export function trustedProxyHops(raw = process.env.LUMA_TRUSTED_PROXY_HOPS) {
  const hops = Number(raw ?? "0");
  return Number.isInteger(hops) && hops >= 0 && hops <= 5 ? hops : 0;
}
