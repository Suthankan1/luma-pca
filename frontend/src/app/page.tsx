import Link from "next/link";
import { Shell } from "@/components/Shell";

export default function Home() {
  return <Shell active="home">
    <h1 className="heading">Physics Cube Academy</h1>
    <p className="sub">Sign in with your academy account to open your staff or student portal.</p>
    <div className="nav-links"><Link className="action" href="/sign-in">Sign in</Link></div>
  </Shell>;
}
