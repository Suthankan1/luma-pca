import { NextRequest, NextResponse } from "next/server";
import { authRequest, config, readIdentity, readTokens } from "@/lib/auth-server";
import { isSameOrigin, portalPath, sealAccess, sessionCookie } from "@/lib/session-policy";

const noStore = { "Cache-Control": "no-store" };

export async function POST(request: NextRequest, context: { params: Promise<{ action: string }> }) {
  const { action } = await context.params;
  if (action !== "login" && action !== "refresh" && action !== "logout") {
    return new NextResponse(null, { status: 404, headers: noStore });
  }

  const go = (path: string) => new NextResponse(null, { status: 303, headers: { ...noStore, Location: path } });
  try {
    const { origin, names, secure, key } = config();
    if (!isSameOrigin(request.headers.get("origin"), origin, request.headers.get("sec-fetch-site"))) {
      return new NextResponse(null, { status: 403, headers: noStore });
    }

    const clear = (path: string) => {
      const response = go(path);
      for (const name of Object.values(names)) response.cookies.set(name, "", sessionCookie(secure, 0));
      return response;
    };
    const refreshToken = request.cookies.get(names.refresh)?.value;

    if (action === "logout") {
      if (refreshToken) {
        const result = await authRequest("logout", { body: { refreshToken } });
        if (result.status !== 204) return go("/sign-in?error=sign-out");
      }
      return clear("/sign-in?signedOut=1");
    }

    if (action === "login" && refreshToken) return go("/sign-in");
    if (action === "refresh" && !refreshToken) return clear("/sign-in?error=expired");
    if (request.headers.get("content-type")?.split(";")[0] !== "application/x-www-form-urlencoded") {
      return new NextResponse(null, { status: 415, headers: noStore });
    }
    const reader = request.body?.pipeThrough(new TextDecoderStream()).getReader();
    let encoded = "";
    if (reader) {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        encoded += value;
        if (encoded.length > 4096) {
          await reader.cancel();
          return new NextResponse(null, { status: 413, headers: noStore });
        }
      }
    }
    const form = new URLSearchParams(encoded);
    const preferred = form.get("portal");
    const errorPath = (error: string) => `/sign-in?error=${error}${preferred === "staff" || preferred === "student" ? `&portal=${preferred}` : ""}`;
    const email = form.get("email")?.trim();
    const password = form.get("password");
    if (action === "login" && (!email || email.length > 254 || !password || password.length > 256)) {
      return go(errorPath("invalid"));
    }

    const result = await authRequest(action, { body: action === "login" ? { email, password } : { refreshToken } });
    if (result.status === 401 || result.status === 400) {
      return action === "login" ? go(errorPath("invalid")) : clear(errorPath("expired"));
    }
    if (result.status === 429) return go(errorPath("limited"));
    if (!result.ok) return go(errorPath("unavailable"));

    const tokens = await readTokens(result);
    let destination: string;
    try {
      const identity = await readIdentity(tokens.accessToken);
      destination = identity ? portalPath(identity, preferred) : errorPath("expired");
    } catch {
      destination = errorPath("unavailable");
    }

    // Preserve a successful rotation even if the separate identity lookup fails.
    const response = go(destination);
    response.cookies.set(names.access, sealAccess(tokens.accessToken, key), sessionCookie(secure, tokens.expiresIn));
    response.cookies.set(names.refresh, tokens.refreshToken, sessionCookie(secure, tokens.refreshExpiresIn));
    return response;
  } catch {
    return go(action === "logout" ? "/sign-in?error=sign-out" : "/sign-in?error=unavailable");
  }
}
