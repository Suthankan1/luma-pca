import { redirect } from "next/navigation";

/** Webinars are tracked on the Classes screen now (one screen for classes); old links land there. */
export default function WebinarsPage() {
  redirect("/staff/sessions");
}
