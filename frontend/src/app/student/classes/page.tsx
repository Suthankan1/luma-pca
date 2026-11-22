import { redirect } from "next/navigation";

export default async function LegacyClassesPage({ searchParams }: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const query = await searchParams;
  const params = new URLSearchParams();
  for (const key of ["month", "from", "day"]) {
    const value = query[key];
    if (typeof value === "string") params.set(key, value);
  }
  redirect(params.size ? `/student/calendar?${params}` : "/student/calendar");
}
