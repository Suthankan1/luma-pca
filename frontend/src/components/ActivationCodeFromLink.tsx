"use client";

import { useEffect } from "react";

/**
 * Copies the code from an invitation link's fragment (`#token=...`) into the form,
 * then removes it from the address bar so it is not left in history or bookmarks.
 * Without JavaScript the person can still paste the code or the whole link.
 */
export function ActivationCodeFromLink({ inputId }: { inputId: string }) {
  useEffect(() => {
    const match = /(?:^#|&)token=([A-Za-z0-9_-]{20,128})(?:&|$)/.exec(window.location.hash);
    if (!match) return;
    const input = document.getElementById(inputId);
    if (input instanceof HTMLInputElement && !input.value) input.value = match[1];
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
  }, [inputId]);
  return null;
}
