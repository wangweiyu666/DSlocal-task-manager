import { expect, test } from "@playwright/test";

test("creates a draft task and reaches DST1 preview", async ({ page }, testInfo) => {
  await page.goto("/#/create");
  await expect(page.getByRole("heading", { name: "创建任务" })).toBeVisible();
  await page.getByRole("button", { name: "在未分组添加任务" }).click();
  const taskName = page.getByLabel("任务名称 *");
  await taskName.pressSequentially("fastInputABC123", { delay: 0 });
  await expect(taskName).toHaveValue("fastInputABC123");
  await taskName.fill("");
  await taskName.pressSequentially("中文输入测试", { delay: 5 });
  await expect(taskName).toHaveValue("中文输入测试");
  if (testInfo.project.name !== "android-chrome") {
    const draftName = page.getByLabel("草稿名称");
    await draftName.fill("");
    await draftName.pressSequentially("中文草稿", { delay: 5 });
    await expect(draftName).toHaveValue("中文草稿");
  }
  await page.getByRole("button", { name: "生成预览" }).click();
  const preview = page.getByRole("dialog", { name: "生成预览" });
  await expect(preview).toBeVisible();
  await expect(preview.getByText(/个任务/u).first()).toBeVisible();
  await expect(page.locator("textarea.envelope-preview")).toHaveValue(/^DST1\./u);
});

test("navigates through the complete local management surface", async ({ page }, testInfo) => {
  const pageErrors: string[] = [];
  const networkErrors: string[] = [];
  const consoleErrors: string[] = [];
  page.on("pageerror", (error) => pageErrors.push(error.message));
  page.on("requestfailed", (request) => networkErrors.push(`${request.url()} ${request.failure()?.errorText ?? "failed"}`));
  page.on("response", (response) => { if (response.status() >= 400) networkErrors.push(`${response.status()} ${response.url()}`); });
  page.on("console", (message) => { if (message.type() === "error") consoleErrors.push(message.text()); });
  await page.goto("/#/library");
  await expect(page.getByRole("heading", { name: "任务库", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "模板", exact: true }).click();
  await expect(page.getByRole("heading", { name: "任务模板", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "积分组", exact: true }).click();
  await expect(page.getByRole("heading", { name: "积分组", exact: true })).toBeVisible();
  await page.getByRole("link", { name: testInfo.project.name === "android-chrome" ? "历史" : "批次历史", exact: true }).click();
  await expect(page.getByRole("heading", { name: "批次历史", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "设置", exact: true }).click();
  await expect(page).toHaveURL(/#\/settings$/u);
  const settingsHeading = page.getByRole("heading", { name: "设置", exact: true });
  await settingsHeading.waitFor({ state: "visible", timeout: 10_000 }).catch(async (error: unknown) => {
    throw new Error(`设置页未渲染；页面异常：${pageErrors.join(" | ")}；网络异常：${networkErrors.join(" | ")}；控制台：${consoleErrors.join(" | ")}；URL：${page.url()}；正文：${await page.locator("body").innerText()}`, { cause: error });
  });
  expect(pageErrors).toEqual([]);
});

test("persists a generated batch and exports configuration", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name === "android-chrome", "完整管理流在桌面浏览器验收");
  await page.goto("/#/groups");
  await page.getByRole("button", { name: "新建积分组" }).click();
  await page.getByLabel("名称 *").fill("验收积分组");
  await page.getByLabel("全部完成文案", { exact: true }).fill("今天全部完成");
  await page.getByRole("button", { name: "保存", exact: true }).click();
  await expect(page.getByRole("heading", { name: "验收积分组" })).toBeVisible();

  await page.getByRole("link", { name: "创建", exact: true }).click();
  await page.getByRole("button", { name: "在验收积分组添加任务" }).click();
  await page.getByLabel("任务名称 *").fill("保存历史验收任务");
  await page.getByRole("button", { name: "生成预览" }).click();
  await page.getByRole("button", { name: "保存并复制" }).click();

  await page.getByRole("link", { name: "批次历史", exact: true }).click();
  await expect(page.getByText("1 个任务", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "再次复制" }).first()).toBeVisible();

  await page.getByRole("link", { name: "设置", exact: true }).click();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出完整配置" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.dsdom\.json$/u);
});
