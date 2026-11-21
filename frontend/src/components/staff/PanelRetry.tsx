"use client";

import { useTransition } from "react";
import { useRouter } from "next/navigation";

export function PanelRetry() {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  return <button type="button" className="link-button" disabled={pending}
    onClick={() => startTransition(() => router.refresh())}>{pending ? "Retrying…" : "Retry"}</button>;
}
