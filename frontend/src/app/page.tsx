import Link from "next/link";
import { ClassroomScene } from "@/components/ClassroomScene";
import { Shell } from "@/components/Shell";

export default function Home() {
  return <Shell active="home">
    <div className="landing-grid">
      <section className="attendance-intro">
        <p className="eyebrow">ATTENDANCE TRACKING</p>
        <h1 className="heading">Track your attendance.</h1>
        <p className="sub">Physics Cube Academy</p>
      </section>
      <ClassroomScene />
      <div className="entry-cards">
        <Link className="entry-card" href="/sign-in?portal=student" prefetch={false}>
          <span className="entry-card-top"><span className="entry-icon" aria-hidden>↗</span><span className="entry-number">STUDENT</span></span>
          <strong>Student sign in</strong>
          <span className="entry-go" aria-hidden>View attendance <span>↗</span></span>
        </Link>
        <Link className="entry-card" href="/sign-in?portal=staff" prefetch={false}>
          <span className="entry-card-top"><span className="entry-icon" aria-hidden>▦</span><span className="entry-number">STAFF</span></span>
          <strong>Staff sign in</strong>
          <span className="entry-go" aria-hidden>Manage attendance <span>↗</span></span>
        </Link>
      </div>
    </div>
  </Shell>;
}
