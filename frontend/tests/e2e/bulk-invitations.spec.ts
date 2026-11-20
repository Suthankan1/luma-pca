import { openSection } from "./support/ui";
import { test, expect, type Page } from "@playwright/test";

async function signInAsAdmin(page: Page) {
  await page.goto("/sign-in");
  await page.fill("#email", "admin-enrolled@example.invalid");
  await page.fill("#password", "correct-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/staff$/);
}

test.describe("Bulk invitation emails", () => {
  // The mock backend is shared across projects and specs: codes are unique per project.
  let p = "bd";
  test.beforeEach(({}, testInfo) => {
    p = (testInfo.project.name === "mobile" ? "bm" : "bd") + (testInfo.retry ? String(testInfo.retry) : "");
  });

  test("An administrator emails a batch's students, sees progress, and resends only on purpose", async ({ page }) => {
    await signInAsAdmin(page);
    const P = p.toUpperCase();
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`${p}-inv`);
    await page.locator("#course-title").fill("Invite course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${P}-INV · Invite course` });
    await page.locator("#batch-code").fill(`${p}-wed`);
    await page.locator("#batch-name").fill("Wednesday invite");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${P}-WED created.`);

    await page.goto("/staff/students/import");
    await page.locator("#import-file").setInputFiles({ name: "s.csv", mimeType: "text/csv", buffer: Buffer.from(
      `number,name,email\n${p}-i1,One,${p}.i1@example.invalid\n${p}-i2,Two,${p}.i2@example.invalid\n${p}-i3,Three,${p}.i3@example.invalid\n`) });
    await page.locator("#import-batch").selectOption({ label: `${P}-INV · ${P}-WED · Wednesday invite` });
    await page.getByRole("button", { name: "Check file" }).click();
    await page.getByRole("button", { name: "Import 3 students" }).click();
    await expect(page.locator("#import-feedback")).toContainText("Imported 3 students");

    await page.goto("/staff/courses");
    await page.getByRole("link", { name: new RegExp(`${P}-WED`) }).first().click();
    const panel = page.getByRole("region", { name: "INVITE BY EMAIL" });
    await openSection(panel, "Email sign-in links");
    await expect(panel).toContainText("Emails a sign-in link to this batch’s current students");

    await panel.getByRole("button", { name: "Check who will be emailed" }).click();
    await expect(panel.locator("#bulk-invite-feedback")).toHaveText("3 students will be emailed. Nothing has been sent yet.");
    await panel.getByRole("button", { name: "Email 3 invitations" }).click();
    await expect(panel.locator("#bulk-invite-feedback")).toHaveText("3 invitations queued. They are sent in the background.");
    // Progress refreshes while emails wait, then shows them sent.
    await expect(panel.locator("#bulk-invite-progress")).toContainText(/\d+ waiting/);
    await expect(panel.locator("#bulk-invite-progress")).toContainText(/0 waiting · \d+ sent · \d+ failed/, { timeout: 10000 });

    // Asking again reaches nobody new; resending is a deliberate choice.
    await panel.getByRole("button", { name: "Check who will be emailed" }).click();
    await expect(panel.locator("#bulk-invite-feedback")).toHaveText("Nobody to email. 3 students already have a usable link. Nothing has been sent yet.");
    await expect(panel.getByRole("button", { name: "Check who will be emailed" })).toBeVisible();
    await panel.getByLabel(/Also send a new link/).check();
    await panel.getByRole("button", { name: "Check who will be emailed" }).click();
    await expect(panel.locator("#bulk-invite-feedback")).toHaveText("3 students will be emailed. Nothing has been sent yet.");
    // Changing the choice after a check needs a new check.
    await panel.getByLabel(/Also send a new link/).uncheck();
    await expect(panel.getByRole("button", { name: "Check who will be emailed" })).toBeVisible();

    // The students page offers the same for every active student.
    await page.goto("/staff/students/manage");
    await expect(page.getByRole("region", { name: "INVITE BY EMAIL" })).toContainText("Emails a sign-in link to every active student");
  });

  test("A student whose invitation email fails is listed for staff to follow up", async ({ page }) => {
    await signInAsAdmin(page);
    const P = p.toUpperCase();
    await page.goto("/staff/courses");
    await page.locator("#course-code").fill(`${p}-bnc`);
    await page.locator("#course-title").fill("Bounce course");
    await page.getByRole("button", { name: "Create course" }).click();
    await page.locator("#batch-course").selectOption({ label: `${P}-BNC · Bounce course` });
    await page.locator("#batch-code").fill(`${p}-fri`);
    await page.locator("#batch-name").fill("Friday bounce");
    await page.locator("#batch-starts").fill("2027-01-10");
    await page.getByRole("button", { name: "Create batch" }).click();
    await expect(page.locator("#batch-feedback")).toHaveText(`Batch ${P}-FRI created.`);
    await page.goto("/staff/students/import");
    await page.locator("#import-file").setInputFiles({ name: "s.csv", mimeType: "text/csv", buffer: Buffer.from(
      `number,name,email\n${p}-b1,Bounced Student,${p}.bounce@example.invalid\n${p}-b2,Fine Student,${p}.fine@example.invalid\n`) });
    await page.locator("#import-batch").selectOption({ label: `${P}-BNC · ${P}-FRI · Friday bounce` });
    await page.getByRole("button", { name: "Check file" }).click();
    await page.getByRole("button", { name: "Import 2 students" }).click();
    await expect(page.locator("#import-feedback")).toContainText("Imported 2 students");

    await page.goto("/staff/courses");
    await page.getByRole("link", { name: new RegExp(`${P}-FRI`) }).first().click();
    const panel = page.getByRole("region", { name: "INVITE BY EMAIL" });
    await openSection(panel, "Email sign-in links");
    await panel.getByRole("button", { name: "Check who will be emailed" }).click();
    await panel.getByRole("button", { name: "Email 2 invitations" }).click();
    await expect(panel.locator("#bulk-invite-feedback")).toHaveText("2 invitations queued. They are sent in the background.");

    // Sending is in the background: the failure shows once it has been tried.
    const failed = page.getByRole("region", { name: "EMAILS THAT FAILED" });
    await expect(async () => {
      await page.goto("/staff/students/manage");
      await expect(failed).toContainText(`${P}-B1 Bounced Student`, { timeout: 1000 });
    }).toPass({ timeout: 20000 });
    await expect(failed).toContainText(`${p}.bounce@example.invalid · 5 tries`);
    await expect(failed).not.toContainText(`${P}-B2`);
    await expect(failed.locator(".status").first()).toHaveText("Mail server refused or unreachable");
  });
});
