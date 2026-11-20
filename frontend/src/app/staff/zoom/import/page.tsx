import { redirect } from "next/navigation";

/** Classes are no longer imported from Zoom: Zoom owns schedules; links are tracked on the Classes screen. */
export default function ZoomImportMoved() {
  redirect("/staff/sessions");
}
