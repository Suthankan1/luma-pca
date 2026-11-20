import { requirePortal } from "@/lib/auth-server";
import { staffApi } from "@/lib/staff-api";
import { uuidOrNull, type ClassEngagement, type ProvisionalAttendance } from "@/lib/staff-forms";
import type { LiveSessionSnapshot } from "@/lib/staff-live";
import type { ClassRun } from "@/lib/webinars";

const privateResponse = { "Cache-Control": "private, no-store" };

export async function GET(request: Request, { params }: { params: Promise<{ sessionId: string }> }) {
  await requirePortal("staff");
  const id = uuidOrNull((await params).sessionId);
  if (!id) return new Response(null, { status: 404, headers: privateResponse });
  const includeEngagement = new URL(request.url).searchParams.get("engagement") === "true";
  const [attendance, run, engagement] = await Promise.all([
    staffApi<ProvisionalAttendance>(`/api/v1/class-sessions/${id}/attendance?includeOnlineHistory=true`, undefined, 25000),
    staffApi<ClassRun | null>(`/api/v1/class-sessions/${id}/zoom-run`),
    includeEngagement ? staffApi<ClassEngagement>(`/api/v1/class-sessions/${id}/engagement`) : null,
  ]);
  if (!attendance.ok) return new Response(null, { status: attendance.status, headers: privateResponse });
  const result: LiveSessionSnapshot = { attendance: attendance.data, run: run.ok ? run.data : undefined,
    engagement: engagement?.ok ? engagement.data : undefined };
  return Response.json(result, { headers: privateResponse });
}
