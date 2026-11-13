import { staffDownload } from "@/lib/staff-api";
import { uuidOrNull } from "@/lib/staff-forms";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The batch's attendance for a date range as CSV, downloaded through the server so the access token stays here. */
export async function GET(request: Request, { params }: { params: Promise<{ batchId: string }> }) {
  const batchId = uuidOrNull((await params).batchId);
  const query = new URL(request.url).searchParams;
  const from = query.get("from") ?? "", to = query.get("to") ?? "";
  if (!batchId) return new Response("Not found", { status: 404 });
  if (!DATE.test(from) || !DATE.test(to)) {
    return new Response("Choose a start and end date.", { status: 400, headers: { "Content-Type": "text/plain; charset=utf-8" } });
  }
  return staffDownload(`/api/v1/batches/${batchId}/attendance/export?${new URLSearchParams({ from, to })}`);
}
