import { NextResponse, type NextRequest } from "next/server";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Named pages that live beside the `[id]` pages and are not IDs. */
const NAMED = new Set(["import"]);

/**
 * A malformed record link gets a real 404 before any rendering starts. Pages under /staff and /student stream a loading
 * screen at once, so after that point the status is already sent; well-formed but unknown IDs still show "Page not found".
 */
export function proxy(request: NextRequest) {
  const id = request.nextUrl.pathname.split("/")[3];
  if (id && !UUID.test(id) && !NAMED.has(id)) {
    return NextResponse.rewrite(new URL("/_not-found", request.url), { status: 404 });
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/staff/sessions/:id", "/staff/students/:id", "/staff/team/:id", "/staff/batches/:id", "/student/classes/:id"],
};
