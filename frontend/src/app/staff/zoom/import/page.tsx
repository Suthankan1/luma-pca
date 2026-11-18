import { redirect } from "next/navigation";

/** Classes are no longer imported from Zoom: Zoom owns schedules and each actual run is recorded (Webinars). */
export default function ZoomImportMoved() {
  redirect("/staff/webinars");
}
