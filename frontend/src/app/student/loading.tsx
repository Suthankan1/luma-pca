import { Shell } from "@/components/Shell";
import { StudentSignInFeedback } from "@/components/StudentSignInFeedback";

/** Shown the moment a student link is clicked, while the page's data loads. */
export default function StudentLoading() {
  return <Shell active="student">
    <h1 className="heading">Student portal</h1>
    <StudentSignInFeedback status="Loading your student portal…" />
  </Shell>;
}
