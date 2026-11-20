import Link from "next/link";
import { StaffShell } from "@/components/StaffShell";
import { Disclosure } from "@/components/Disclosure";
import { AddStudentForm } from "@/components/staff/StudentForms";
import { BulkInviteForm } from "@/components/staff/BulkInviteForm";
import { requirePortal, studentIdLoginEnabled } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { accountStatusLabels, formatInstant, studentListHref } from "@/lib/staff-forms";

type Student = { id: string; studentNumber: string; fullName: string; accountStatus: string | null; active: boolean };
type Page = { items: Student[]; nextAfter: string | null };
type FailedInvitation = { studentId: string; studentNumber: string; fullName: string; email: string; reason: string; failedAt: string; attempts: number };

export const metadata = { title: "Students · LUMA" };

const filters = [
  { key: "active", label: "Active", query: "&active=true" },
  { key: "inactive", label: "Inactive", query: "&active=false" },
  { key: "all", label: "All", query: "" },
] as const;

export default async function StudentsPage({ searchParams }: { searchParams: Promise<{ after?: string; status?: string; q?: string }> }) {
  const identity = await requirePortal("staff");
  const { after, status, q } = await searchParams;
  const search = typeof q === "string" ? q.trim().slice(0, 100) : "";
  const filter = filters.find(f => f.key === status) ?? filters[0];
  const cursor = typeof after === "string" && /^[A-Za-z0-9._-]{1,60}$/.test(after) ? after : null;
  const shell = { active: "students" as const, crumb: "Students", title: "Students",
    subtitle: "Each student has one academy record and one sign-in, whatever batches they join.",
    hasStudentPortal: hasPortal(identity, "student") };

  const [failures, result] = await Promise.all([
    staffApi<FailedInvitation[]>("/api/v1/student-invitations/failures"),
    staffApi<Page>(`/api/v1/students?limit=50${filter.query}${cursor ? `&after=${encodeURIComponent(cursor)}` : ""}${search ? `&q=${encodeURIComponent(search)}` : ""}`),
  ]);
  return <StaffShell {...shell}>
    <div className="staff-grid">
      <section className="panel" aria-labelledby="students-title">
        <h2 id="students-title" className="panel-title">STUDENT DIRECTORY</h2>
        <form className="filter-form" method="get" action="/staff/students" role="search">
          {filter.key !== "active" && <input type="hidden" name="status" value={filter.key} />}
          <label htmlFor="student-search">Find a student</label>
          <input id="student-search" name="q" type="search" maxLength={100} defaultValue={search} placeholder="Name, student number or whole email" />
          <button className="action secondary" type="submit">Search</button>
          {search && <Link className="text-link" href={studentListHref({ status: filter.key })}>Clear</Link>}
        </form>
        <nav className="filter-row" aria-label="Filter students">
          {filters.map(f => <Link key={f.key} href={studentListHref({ status: f.key, q: search })}
            className={`chip ${f.key === filter.key ? "cyan" : ""}`} aria-current={f.key === filter.key ? "true" : undefined}>{f.label}</Link>)}
        </nav>
        {!result.ok ? <p className="session-error" role="alert">We couldn’t load students right now. Please reload the page shortly.</p> : <>
          <p className="panel-sub">{result.data.items.length === 0
            ? (search ? `No students match “${search}”.` : cursor ? "No more students." : "No students here yet.")
            : `Showing ${result.data.items.length}${search ? ` matching “${search}”` : ""}${cursor ? ` after ${cursor}` : ""}, by student number.`}</p>
          <ul className="dir-list">
            {result.data.items.map(student => <li key={student.id} className="dir-row dir-course">
              <div><Link className="text-link" href={`/staff/students/${student.id}`}><strong>{student.studentNumber}</strong> <span>{student.fullName}</span></Link></div>
              <div className="dir-meta">
                <span className="status">{student.accountStatus ? accountStatusLabels[student.accountStatus] ?? student.accountStatus : ""}</span>
                {!student.active && <span className="status off">Inactive</span>}
              </div>
            </li>)}
          </ul>
          <div className="nav-links">
            {cursor && <Link className="action secondary" href={studentListHref({ status: filter.key, q: search })}>← First page</Link>}
            {result.data.nextAfter && <Link className="action secondary" href={studentListHref({ status: filter.key, after: result.data.nextAfter, q: search })}>Next page →</Link>}
          </div>
        </>}
      </section>
      <div>
      <section className="panel" aria-labelledby="add-student-title">
        <h2 id="add-student-title" className="panel-title">ADD</h2>
        <p className="panel-sub academy-hint">Students in the Academy’s records are added for you: set up and sync their class under{" "}
          <Link className="text-link" href="/staff/academy">Academy classes →</Link></p>
        <Disclosure summary="Add a student who is not in the Academy records">
          <p className="panel-sub">This also creates their sign-in, waiting for an invitation. Student numbers are stored in capitals and
            cannot be changed later.</p>
          <AddStudentForm />
        </Disclosure>
        <p className="link-row"><Link className="text-link" href="/staff/students/import">Import many students from a CSV file →</Link></p>
      </section>
      <section className="panel panel-gap" aria-labelledby="invite-all-title">
        <h2 id="invite-all-title" className="panel-title">INVITE BY EMAIL</h2>
        <Disclosure summary="Email sign-in links to students">
          {studentIdLoginEnabled() && <p className="panel-sub">Students can sign in with their student ID, so invitations are only needed
            for email sign-in. Students without an email of their own are never emailed.</p>}
          <BulkInviteForm batchId={null} scope="every active student" />
        </Disclosure>
      </section>
      {failures.ok && failures.data.length > 0 && <section className="panel panel-gap" aria-labelledby="failed-invites-title">
        <h2 id="failed-invites-title" className="panel-title">EMAILS THAT FAILED</h2>
        <p className="panel-sub">These invitation emails could not be delivered after several tries. Check the address on the student’s
          record, then email again from here, or create a link on their record and send it another way.</p>
        <ul className="dir-list">
          {failures.data.map(f => <li key={f.studentId} className="dir-row dir-course">
            <div>
              <Link className="text-link" href={`/staff/students/${f.studentId}`}><strong>{f.studentNumber}</strong> <span>{f.fullName}</span></Link>
              <div className="dir-dates muted">{f.email} · {f.attempts} tries, last {formatInstant(f.failedAt)}</div>
            </div>
            <span className="status off">{/Mail|Smtp|Socket|Connect/i.test(f.reason) ? "Mail server refused or unreachable" : "Sending failed"}</span>
          </li>)}
        </ul>
      </section>}
      </div>
    </div>
  </StaffShell>;
}
