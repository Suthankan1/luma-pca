"use client";

import { useState } from "react";

export function CopyRecoveryCodes({ codes }: { codes: string[] }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(codes.join("\n"));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // If clipboard access is blocked, user can still manually copy from the screen.
    }
  };

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="action secondary"
      style={{ justifySelf: "start", marginTop: "8px" }}
      aria-live="polite"
    >
      {copied ? "Copied to clipboard!" : "Copy all codes"}
    </button>
  );
}
