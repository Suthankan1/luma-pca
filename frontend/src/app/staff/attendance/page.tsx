import { StaffShell } from "@/components/StaffShell";
import { PolicyForm } from "@/components/staff/AttendanceForms";
import { requirePortal } from "@/lib/auth-server";
import { hasPortal } from "@/lib/session-policy";
import { staffApi } from "@/lib/staff-api";
import { formatInstant, type AttendancePolicy } from "@/lib/staff-forms";

export const metadata = { title: "Attendance · LUMA" };

/** The academy's attendance rule. Everyone who sees attendance can read it; only an owner records it. */
export default async function AttendancePage() {
  const identity = await requirePortal("staff");
  const owner = identity.roles.includes("ACADEMY_OWNER");
  const shell = { active: "attendance" as const, crumb: "Oversight", title: "Attendance",
    subtitle: "The academy’s rule for final attendance, applied only to Zoom’s official participant report.",
    hasStudentPortal: hasPortal(identity, "student") };
  const policy = await staffApi<AttendancePolicy | null>("/api/v1/attendance-policy");
  if (!policy.ok) {
    return <StaffShell {...shell}><p className="session-error" role="alert">
      {policy.status === 403 ? "Your role does not include attendance." : "We couldn’t load the attendance rule right now. Please reload shortly."}</p></StaffShell>;
  }
  const rule = policy.data;
  return <StaffShell {...shell}>
    <section className="panel" aria-labelledby="rule-title">
      <h2 id="rule-title" className="panel-title">ATTENDANCE RULE</h2>
      {rule ? <>
        <p className="rule-summary"><strong>Present</strong> when connected for at least {rule.minConnectedPercent}% of the class time;
          {" "}<strong>late</strong> when also first joining more than {rule.lateAfterMinutes} minute{rule.lateAfterMinutes === 1 ? "" : "s"} after
          {" "}the start; otherwise <strong>absent</strong>.</p>
        <p className="panel-sub">{rule.description} · last changed {formatInstant(rule.updatedAt)}. Classes finalised earlier keep the rule they used.</p>
      </> : <p className="banner">No rule has been recorded yet, so no class can be finalised. The rule is the academy’s decision; LUMA only applies it.</p>}
      {owner ? <PolicyForm policy={rule} /> : <p className="panel-sub">Only an academy owner can record or change the rule.</p>}
    </section>
  </StaffShell>;
}
