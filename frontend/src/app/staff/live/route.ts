import { requirePortal } from "@/lib/auth-server";
import { MAX_LIVE_CLASSES, readLiveAttendance } from "@/lib/staff-live";
import { uuidOrNull } from "@/lib/staff-forms";

const privateResponse = { "Cache-Control": "private, no-store" };

export async function GET(request: Request) {
  await requirePortal("staff");
  const requested = new URL(request.url).searchParams.getAll("session");
  const ids = [...new Set(requested.map(uuidOrNull))];
  if (requested.length === 0 || requested.length > MAX_LIVE_CLASSES || ids.includes(null)) {
    return Response.json({ detail: "Select up to three live classes." }, { status: 400, headers: privateResponse });
  }
  // The attendance endpoint checks each session against the staff member's academy on every call.
  const result = await readLiveAttendance(ids.filter((id): id is string => id !== null));
  return Response.json(result, { headers: privateResponse });
}
