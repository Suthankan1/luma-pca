import { staffApi } from "@/lib/staff-api";
import type { AcademyClass } from "@/lib/academy";
import type { WebinarMonitor } from "@/lib/webinars";
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

export function ClassTypeRosterControls({ monitor, classes }: {
  monitor?: WebinarMonitor; classes: AcademyClass[] | null;
}) {
  if (!monitor?.active) return <span className="status off">Zoom link not tracked</span>;
  if (!classes) return <span className="status off">Sync unavailable</span>;
  return <PaymentRosterForm key={monitor.id} monitorId={monitor.id}
    assigned={monitor.academyClassName ?? ""} classes={classes} />;
}
