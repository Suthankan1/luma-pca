import { staffDownload } from "@/lib/staff-api";
import { uuidOrNull } from "@/lib/staff-forms";

/** The class's attendance as CSV, downloaded through the server so the access token stays here. */
export async function GET(_request: Request, { params }: { params: Promise<{ sessionId: string }> }) {
  const sessionId = uuidOrNull((await params).sessionId);
  if (!sessionId) return new Response("Not found", { status: 404 });
  return staffDownload(`/api/v1/class-sessions/${sessionId}/attendance/export`);
}
