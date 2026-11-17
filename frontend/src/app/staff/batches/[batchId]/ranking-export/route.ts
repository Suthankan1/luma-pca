import { staffApi, staffDownload } from "@/lib/staff-api";
import { uuidOrNull } from "@/lib/staff-forms";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The engagement ranking (this batch or its whole course) as CSV, through the server so the access token stays here. */
export async function GET(request: Request, { params }: { params: Promise<{ batchId: string }> }) {
  const batchId = uuidOrNull((await params).batchId);
  const query = new URL(request.url).searchParams;
  const from = query.get("from") ?? "", to = query.get("to") ?? "", limit = query.get("limit") === "100" ? "100" : "10";
  if (!batchId) return new Response("Not found", { status: 404 });
  if (!DATE.test(from) || !DATE.test(to)) return new Response("Choose a start and end date.", { status: 400 });
  let scope = `/api/v1/batches/${batchId}` as `/api/v1/${string}`;
  if (query.get("scope") === "course") {
    const batch = await staffApi<{ courseId: string }>(`/api/v1/batches/${batchId}`);
    if (!batch.ok) return new Response("Not found", { status: 404 });
    scope = `/api/v1/courses/${batch.data.courseId}`;
  }
  return staffDownload(`${scope}/engagement-ranking/export?${new URLSearchParams({ from, to, limit })}`);
}
