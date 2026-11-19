import type { Locator, Page } from "@playwright/test";

/**
 * Opens a collapsed section (a <details> whose summary contains {@code label}) inside {@code scope} if it is closed;
 * leaves it open otherwise. Secondary forms and settings are collapsed by default since the UX overhaul.
 */
export async function openSection(scope: Page | Locator, label: string | RegExp) {
  const isLocator = "page" in scope && typeof scope.page === "function";
  const page = isLocator ? (scope as Locator).page() : scope as Page;
  if (isLocator) await (scope as Locator).first().waitFor(); // a page just navigated to may not have rendered yet
  const section = scope.locator("details").filter({ has: page.locator("summary", { hasText: label }) }).first();
  if (await section.count() === 0) return; // not collapsible here
  if (!(await section.evaluate(el => (el as HTMLDetailsElement).open))) {
    await section.locator("summary").first().click();
  }
}
