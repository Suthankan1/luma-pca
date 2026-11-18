import Link from "next/link";
import { Shell } from "@/components/Shell";

export default function Home() {
  return <Shell active="home">
    <p className="crumbs">LUMA · Webinar attendance</p>
    <h1 className="heading">Physics Cube Academy</h1>
    <p className="sub">Your classes, Zoom links and attendance in one place. Choose how you sign in.</p>
    <div className="entry-cards">
      <Link className="entry-card" href="/sign-in?portal=student">
        <span className="entry-icon" aria-hidden>▶</span>
        <strong>I’m a student</strong>
        <span>See your next class, join it on Zoom, and check your attendance.</span>
        <span className="entry-go" aria-hidden>Sign in →</span>
      </Link>
      <Link className="entry-card" href="/sign-in?portal=staff">
        <span className="entry-icon" aria-hidden>▦</span>
        <strong>I’m academy staff</strong>
        <span>Classes, live attendance, the attendance dashboard and Zoom reports.</span>
        <span className="entry-go" aria-hidden>Sign in →</span>
      </Link>
    </div>
  </Shell>;
}
