import { staffApi } from "@/lib/staff-api";
import type { AcademyClass } from "@/lib/academy";
import type { WebinarMonitor } from "@/lib/webinars";
import type { ClassSession } from "@/lib/staff-forms";
import { PaymentRosterForm } from "./PaymentRosterForm";

export async function readClassTypeRosters() {
  const [classes, monitors] = await Promise.all([
    staffApi<AcademyClass[]>("/api/v1/academy/classes", undefined, 25000),
    staffApi<WebinarMonitor[]>("/api/v1/zoom/monitors"),
  ]);
  return {
    classes: classes.ok ? classes.data : null,
    monitors: monitors.ok ? monitors.data : [],
    monitorsFailed: !monitors.ok,
  };
}

export function matchClassTypeWebinar(sessions: ClassSession[], monitors: WebinarMonitor[]) {
  const ids = new Set(sessions.flatMap(session => session.zoomWebinarId ? [session.zoomWebinarId] : []));
  const webinarId = ids.size === 1 ? [...ids][0] : undefined;
  return { webinarId, monitor: monitors.find(monitor => monitor.webinarId === webinarId) };
}

export function ClassTypeRosterControls({ monitor, webinarId, classes }: {
  monitor?: WebinarMonitor; webinarId?: string; classes: AcademyClass[] | null;
}) {
  return <PaymentRosterForm monitorId={monitor?.active ? monitor.id : undefined}
    webinarId={monitor?.webinarId ?? webinarId} assigned={monitor?.academyClassName ?? ""} classes={classes} />;
}
