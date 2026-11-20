import Link from "next/link";
import { shiftMonth } from "@/lib/staff-home";

export function ClassMonthPicker({ href, month, currentMonth, label = "Class month" }: {
  href: string; month: string; currentMonth: boolean; label?: string;
}) {
  return <nav className="classes-month-picker" aria-label="Browse months">
    <Link className="home-step" href={`${href}?month=${shiftMonth(month, -1)}`} aria-label="Previous month">‹</Link>
    <form action={href}>
      <input aria-label={label} type="month" name="month" key={month} defaultValue={month}
        pattern="[0-9]{4}-(0[1-9]|1[0-2])" required />
      <button className="home-step" type="submit">View</button>
    </form>
    <Link className="home-step" href={`${href}?month=${shiftMonth(month, 1)}`} aria-label="Next month">›</Link>
    {!currentMonth && <Link className="text-link" href={href}>This month</Link>}
  </nav>;
}
