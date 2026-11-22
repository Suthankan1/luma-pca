import { staffApi } from "@/lib/staff-api";
import type { AcademyClass } from "@/lib/academy";
import type { WebinarMonitor } from "@/lib/webinars";
import type { ClassSession } from "@/lib/staff-forms";
import { PaymentRosterForm } from "./PaymentRosterForm";

export function readClassTypeRosters() {
  return {
    classes: staffApi<AcademyClass[]>("/api/v1/academy/classes", undefined, 25000),
    monitors: staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
  };
}

export function matchClassTypeWebinar(sessions: ClassSession[], monitors: WebinarMonitor[]) {
  const ids = new Set(sessions.flatMap(session => session.zoomWebinarId ? [session.zoomWebinarId] : []));
  const webinarId = ids.size === 1 ? [...ids][0] : undefined;
  return { webinarId, monitor: monitors.find(monitor => monitor.webinarId === webinarId) };
}

export async function ClassTypeRosterControls({ monitor, webinarId, classes }: {
  monitor?: WebinarMonitor; webinarId?: string; classes: ReturnType<typeof readClassTypeRosters>["classes"];
}) {
  const source = await classes;
  return <PaymentRosterForm monitorId={monitor?.active ? monitor.id : undefined}
    webinarId={monitor?.webinarId ?? webinarId} assigned={monitor?.academyClassName ?? ""} classes={source.ok ? source.data : null} />;
}
