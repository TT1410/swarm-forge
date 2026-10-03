const { test, expect } = require("@playwright/test");
const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const repoRoot = path.resolve(__dirname, "../..");
const packWeb = path.join(repoRoot, "swarmforge/scripts/pack_web.sh");

function writeFile(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
}

function seedProject(project) {
  writeFile(
    path.join(project, ".swarmforge/roles.tsv"),
    `specifier\tmaster\t${project}\tspecifier\tSpecifier\tcodex\ttask\n` +
      `coder\tcoder\t${project}/.worktrees/coder\tcoder\tCoder\tcodex\ttask\n`
  );
  writeFile(
    path.join(project, ".swarmforge/board/tasks.tsv"),
    "HTW\tspecifier\t2026-01-01T00:00:00Z\t2026-01-01T00:00:00Z\t20260101T000000Z-htw\t0\n"
  );
  writeFile(path.join(project, ".swarmforge/board/HTW.txt"), "Integrate the cave.\n");
  writeFile(path.join(project, "mission.md"), "Hunt the wumpus from the cave.\n");
  writeFile(path.join(project, "tasks/HTW.md"), "# HTW\n\nIntegrate the cave.\n");
  writeFile(path.join(project, "features/console.feature"), "Feature: console\n");
  writeFile(
    path.join(project, ".swarmforge/handoffs/pending_approval/50_hello.handoff"),
    "from: specifier\n" +
      "to: coder\n" +
      "type: git_handoff\n" +
      "task_id: 20260101T000000Z-htw\n" +
      "task: HTW\n" +
      "artifacts: features/console.feature,tasks/HTW.md\n" +
      "\n" +
      "payload\n"
  );
  writeFile(
    path.join(project, ".swarmforge/dashboard/clarifications/pending/clar-1.request"),
    "id: clar-1\n" +
      "status: pending\n" +
      "role: specifier\n" +
      "created_at: 2026-01-01T00:00:00Z\n" +
      "\n" +
      "Does the bat drop to any of 20 rooms?\n"
  );
}

function seedForge(root) {
  writeFile(
    path.join(root, "packs/four-pack/swarmforge/swarmforge.conf"),
    "window specifier grok master\nwindow coder grok coder\n"
  );
  writeFile(path.join(root, "packs/four-pack/swarmforge/roles/specifier.prompt"), "spec\n");
  writeFile(path.join(root, "packs/four-pack/swarmforge/roles/coder.prompt"), "coder\n");
  fs.mkdirSync(path.join(root, "projects"), { recursive: true });
  const project = path.join(root, "projects/htw");
  seedProject(project);
  writeFile(path.join(root, ".swarmforge/open-projects"), "htw\n");
  writeFile(
    path.join(root, ".swarmforge/roles.tsv"),
    `lieutenant\tmaster\t${root}\tswarmforge-lieutenant\tLieutenant\tgrok\ttask\tforward-only\n`
  );
}

async function startDashboard() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "swarmforge-dashboard."));
  seedForge(root);
  const child = spawn(packWeb, ["--serve", root, "0"], {
    cwd: repoRoot,
    stdio: ["ignore", "pipe", "pipe"]
  });
  const url = await new Promise((resolve, reject) => {
    let buf = "";
    const timer = setTimeout(() => reject(new Error("pack_web --serve timed out")), 10000);
    child.stdout.on("data", (chunk) => {
      buf += chunk.toString();
      const line = buf.split("\n").find((item) => item.startsWith("http://"));
      if (line) {
        clearTimeout(timer);
        resolve(line.trim());
      }
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error("pack_web exited " + code + " " + buf));
    });
  });
  return { root, child, url };
}

async function stopDashboard(handle) {
  if (handle && handle.child && !handle.child.killed) {
    handle.child.kill("SIGTERM");
    await new Promise((resolve) => handle.child.once("exit", resolve));
  }
  if (handle && handle.root) {
    fs.rmSync(handle.root, { recursive: true, force: true });
  }
}

test.describe("pack dashboard", () => {
  let handle;

  test.beforeAll(async () => {
    handle = await startDashboard();
  });

  test.afterAll(async () => {
    await stopDashboard(handle);
  });

  test("places Teardown with the pack title and New Task in the actions", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator(".pack-identity #teardown-btn")).toBeVisible();
    await expect(page.locator(".pack-actions #btn-new-project")).toBeVisible();
    await expect(page.locator(".pack-actions #btn-open-project")).toBeVisible();
    await expect(page.locator(".pack-identity #teardown-btn")).toBeVisible();
    await expect(page.locator(".pack-actions #btn-new-task")).toHaveCount(1);
    await expect(page.locator(".project-header button", { hasText: "New Task" })).toBeVisible();
    await expect(page.locator(".board-toolbar")).toHaveCount(0);
  });

  test("clicking the project name opens a growable mission window", async ({ page, context }) => {
    await page.goto(handle.url);
    const popupPromise = context.waitForEvent("page");
    await page.locator(".project-header .project-name", { hasText: "htw" }).click();
    const win = await popupPromise;
    await win.waitForLoadState("domcontentloaded");
    await expect(win.locator("#mission-body")).toContainText("Hunt the wumpus from the cave.");
  });

  test("Work Queue / lieutenant split is draggable", async ({ page }) => {
    await page.goto(handle.url);
    const work = page.locator(".work-sec");
    const chat = page.locator(".ts");
    const split = page.locator(".rail-splitter");
    await expect(split).toBeVisible();
    const beforeWork = await work.boundingBox();
    const beforeChat = await chat.boundingBox();
    const box = await split.boundingBox();
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2, box.y + 80, { steps: 5 });
    await page.mouse.up();
    const afterWork = await work.boundingBox();
    const afterChat = await chat.boundingBox();
    expect(afterWork.height).toBeGreaterThan(beforeWork.height);
    expect(afterChat.height).toBeLessThan(beforeChat.height);
  });

  test("New Task focuses the name field", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await expect(page.locator("#nt-name")).toBeFocused();
  });

  test("New Task sends the chosen role and priority", async ({ page }) => {
    let posted = null;
    await page.route("**/api/tasks", async (route) => {
      posted = JSON.parse(route.request().postData() || "{}");
      await route.fulfill({ status: 200, contentType: "application/json", body: "{\"ok\":true}" });
    });
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await expect(page.locator("#nt-name")).toHaveAttribute("maxlength", "80");
    await expect(page.locator("#nt-role option")).toHaveText(["Master (default)", "Specifier", "Coder"]);
    await page.locator("#nt-name").fill("D02");
    await page.locator("#nt-role").selectOption("coder");
    await page.locator("#nt-priority").fill("10");
    await page.locator("#nt-ok").click();
    await expect.poll(() => posted).not.toBeNull();
    expect(posted).toMatchObject({ name: "D02", project: "htw", role: "coder", priority: "10" });
  });

  test("queued card shows Queued and its menu reorders, renames, and removes it", async ({ page }) => {
    const local = await startDashboard();
    const project = path.join(local.root, "projects/htw");
    try {
      fs.appendFileSync(
        path.join(project, ".swarmforge/board/tasks.tsv"),
        "Q1\tcoder\t2026-01-01T00:00:00Z\t2026-01-01T00:00:00Z\t20260101T000000Z-q1\t0\n"
      );
      writeFile(
        path.join(project, ".worktrees/coder/.swarmforge/handoffs/inbox/new/50_q1_from_specifier_to_coder.handoff"),
        "from: specifier\nto: coder\npriority: 50\ntype: git_handoff\n" +
          "task_id: 20260101T000000Z-q1\ntask: Q1\n\npayload\n"
      );
      await page.goto(local.url);
      const card = page.locator(".card[data-task-name=\"Q1\"]");
      await expect(card.locator(".pill-queued")).toHaveText("Queued · P50");
      await card.locator(".card-menu-btn").click();
      page.once("dialog", (dialog) => dialog.accept("10"));
      await card.locator(".card-menu .menu-list button", { hasText: "Change priority" }).click();
      await expect(page.locator(".card[data-task-name=\"Q1\"] .pill-queued")).toHaveText("Queued · P10");
      await page.locator(".card[data-task-name=\"Q1\"] .card-menu-btn").click();
      page.once("dialog", (dialog) => dialog.accept("Q1 renamed"));
      await page.locator(".card[data-task-name=\"Q1\"] .card-menu .menu-list button", { hasText: "Rename" }).click();
      const renamed = page.locator(".card[data-task-name=\"Q1 renamed\"]");
      await expect(renamed).toHaveCount(1);
      await renamed.locator(".card-menu-btn").click();
      page.once("dialog", (dialog) => dialog.accept());
      await renamed.locator(".card-menu .menu-list button", { hasText: "Remove from queue" }).click();
      await expect(page.locator(".card[data-task-name=\"Q1 renamed\"]")).toHaveCount(0);
      expect(fs.readdirSync(path.join(project, ".worktrees/coder/.swarmforge/handoffs/inbox/new"))).toEqual([]);
    } finally {
      await stopDashboard(local);
    }
  });

  test("Clarification answer can also go to other roles", async ({ page }) => {
    let posted = null;
    await page.route("**/api/clarifications/**", async (route) => {
      posted = JSON.parse(route.request().postData() || "{}");
      await route.fulfill({ status: 200, contentType: "application/json", body: "{\"ok\":true}" });
    });
    await page.goto(handle.url);
    const row = page.locator("#attention-clarifications .att-row");
    await expect(row.locator("[data-also-role]")).toHaveCount(1);
    await row.locator("[data-also-role=\"coder\"]").check();
    await row.locator("textarea.clar-answer").fill("Yes, all 20.");
    await row.locator("button", { hasText: "Submit" }).click();
    await expect.poll(() => posted).not.toBeNull();
    expect(posted).toEqual({ text: "Yes, all 20.", also: ["coder"] });
  });

  test("Attention lists approvals and clarifications", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator("#attention-approvals .att-row")).toContainText("HTW");
    await expect(page.locator("#attention-approvals .att-row")).toContainText("Approve");
    await expect(page.locator("#attention-approvals .att-row")).toContainText("Reject");
    await expect(page.locator("#attention-approvals .att-row")).toContainText("Documents");
    await expect(page.locator("#attention-clarifications .att-row")).toContainText(
      "Clarification requested from: specifier"
    );
    await expect(page.locator("#attention-clarifications .att-row")).toContainText(
      "Does the bat drop to any of 20 rooms?"
    );
  });

  test("Approve is disabled when a document has comments", async ({ page }) => {
    writeFile(
      path.join(handle.root, "projects/htw/.swarmforge/handoffs/pending_approval/50_hello.reviews.json"),
      JSON.stringify({ "features/console.feature": "use an RNG" })
    );
    await page.goto(handle.url);
    await expect(page.locator("#attention-approvals .btn-approve")).toBeDisabled();
    fs.unlinkSync(path.join(handle.root, "projects/htw/.swarmforge/handoffs/pending_approval/50_hello.reviews.json"));
  });

  test("Documents fetch /doc?path= into a window with Save and Cancel", async ({ page, context }) => {
    await page.goto(handle.url);
    await page.locator("#attention-approvals .menu > button").click();
    const popupPromise = context.waitForEvent("page");
    await page.locator("#attention-approvals .menu-list button", { hasText: "console.feature" }).click();
    const doc = await popupPromise;
    await doc.waitForLoadState("domcontentloaded");
    await expect(doc.locator("pre")).toContainText("Feature: console");
    await expect(doc.locator("#doc-history")).toBeVisible();
    await expect(doc.locator("#doc-history")).toHaveClass(/empty/);
    const histBox = await doc.locator("#doc-history").boundingBox();
    const bodyBox = await doc.locator("#doc-body").boundingBox();
    expect(histBox.height).toBeLessThan(bodyBox.height);
    expect(histBox.height).toBeLessThan(80);
    const split = doc.locator("#doc-split-body");
    const splitBox = await split.boundingBox();
    await doc.mouse.move(splitBox.x + splitBox.width / 2, splitBox.y + splitBox.height / 2);
    await doc.mouse.down();
    await doc.mouse.move(splitBox.x + splitBox.width / 2, splitBox.y - 80, { steps: 5 });
    await doc.mouse.up();
    const afterHist = await doc.locator("#doc-history").boundingBox();
    const afterBody = await doc.locator("#doc-body").boundingBox();
    expect(afterHist.height).toBeGreaterThan(histBox.height);
    expect(afterBody.height).toBeLessThan(bodyBox.height);
    await expect(doc.locator("#doc-diff")).toBeDisabled();
    await expect(doc.locator("#doc-comments")).toBeVisible();
    await expect(doc.locator("#doc-save")).toHaveText("Save");
    await expect(doc.locator("#doc-cancel")).toHaveText("Cancel");
    await doc.locator("#doc-comments").fill("needs an RNG");
    await doc.locator("#doc-cancel").click();
    await expect(doc.isClosed()).toBeTruthy();
    const reviewsPath = path.join(handle.root, "projects/htw/.swarmforge/handoffs/pending_approval/50_hello.reviews.json");
    expect(fs.existsSync(reviewsPath)).toBeFalsy();

    await page.locator("#attention-approvals .menu > button").click();
    const savedPromise = context.waitForEvent("page");
    await page.locator("#attention-approvals .menu-list button", { hasText: "console.feature" }).click();
    const saved = await savedPromise;
    await saved.waitForLoadState("domcontentloaded");
    await saved.locator("#doc-comments").fill("needs an RNG");
    await saved.locator("#doc-save").click();
    await expect.poll(() => fs.existsSync(reviewsPath)).toBeTruthy();
    const reviews = JSON.parse(fs.readFileSync(reviewsPath, "utf8"));
    expect(reviews["features/console.feature"]).toBe("needs an RNG");
    await page.reload();
    await expect(page.locator("#attention-approvals .btn-approve")).toBeDisabled();
    await expect(page.locator("#attention-approvals .doc-mark-bad")).toHaveCount(1);
    const historyPath = path.join(
      handle.root, "projects/htw",
      ".swarmforge/rejected-tasks/20260101T000000Z-htw/reviews.json"
    );
    await expect.poll(() => fs.existsSync(historyPath)).toBeTruthy();
    const history = JSON.parse(fs.readFileSync(historyPath, "utf8"));
    expect(history["features/console.feature"][0].text).toBe("needs an RNG");
    fs.unlinkSync(reviewsPath);
  });

  test("Reject opens the retry dialog", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator("#attention-approvals button", { hasText: "Reject" }).click();
    await expect(page.locator("#reject-layer")).toHaveClass(/open/);
    await expect(page.locator("#rt-title")).toHaveText("HTW");
    await expect(page.locator("#rt-retry")).toHaveText("Retry");
    await expect(page.locator("#rt-accept")).toHaveText("Accept Unchanged");
    await expect(page.locator("#rt-delete")).toHaveText("Delete");
  });

  test("retry dialog comments appear in document history", async ({ page, context }) => {
    const local = await startDashboard();
    try {
      await page.goto(local.url);
      await page.locator("#attention-approvals .menu > button").click();
      const popupPromise = context.waitForEvent("page");
      await page.locator("#attention-approvals .menu-list button", { hasText: "console.feature" }).click();
      const doc = await popupPromise;
      await doc.waitForLoadState("domcontentloaded");
      await doc.locator("#doc-comments").fill("needs an RNG");
      await doc.locator("#doc-save").click();
      await page.locator("#attention-approvals button", { hasText: "Reject" }).click();
      await page.locator("#rt-text").fill("dialog note");
      await page.locator("#rt-retry").click();
      await expect(page.locator("#attention-approvals .att-row")).toHaveCount(0);
      writeFile(
        path.join(local.root, "projects/htw/.swarmforge/handoffs/pending_approval/50_hello.handoff"),
        "from: specifier\n" +
          "to: coder\n" +
          "type: git_handoff\n" +
          "task_id: 20260101T000000Z-htw\n" +
          "task: HTW\n" +
          "artifacts: features/console.feature,tasks/HTW.md\n" +
          "\n" +
          "payload\n"
      );
      await page.reload();
      await page.locator("#attention-approvals .menu > button").click();
      const againPromise = context.waitForEvent("page");
      await page.locator("#attention-approvals .menu-list button", { hasText: "console.feature" }).click();
      const again = await againPromise;
      await again.waitForLoadState("domcontentloaded");
      await expect(again.locator("#doc-history")).toContainText("needs an RNG");
      await expect(again.locator("#doc-history")).toContainText("dialog note");
      await expect(again.locator("#doc-history .hist-sep")).toHaveCount(2);
    } finally {
      await stopDashboard(local);
    }
  });

  test("Attention shows underlined project/task with a bold project", async ({ page }) => {
    await page.goto(handle.url);
    const pair = page.locator("#attention-approvals .att-work");
    await expect(pair).toContainText("htw/HTW");
    await expect(page.locator("#attention-approvals .att-project")).toHaveCSS("font-weight", "700");
    await expect(pair).toHaveCSS("text-decoration-line", "underline");
    await expect(page.locator("#attention-clarifications .att-project")).toHaveText("htw");
  });

  test("chimes once when a new Attention row appears", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator("#attention-approvals .att-row")).toHaveCount(1);
    const before = await page.evaluate(() => window.__swarmChime || 0);
    writeFile(
      path.join(handle.root, "projects/htw/.swarmforge/handoffs/pending_approval/50_second.handoff"),
      "from: specifier\n" +
        "to: coder\n" +
        "type: git_handoff\n" +
        "task_id: 20260101T000001Z-htw\n" +
        "task: HTW\n" +
        "artifacts: features/console.feature\n" +
        "\n" +
        "payload\n"
    );
    const second = path.join(
      handle.root,
      "projects/htw/.swarmforge/handoffs/pending_approval/50_second.handoff"
    );
    try {
      await expect.poll(() => page.evaluate(() => window.__swarmChime || 0), { timeout: 5000 })
        .toBe(before + 1);
      await page.waitForTimeout(2500);
      expect(await page.evaluate(() => window.__swarmChime || 0)).toBe(before + 1);
    } finally {
      fs.rmSync(second, { force: true });
    }
  });

  test("Clarification Open posts the answer from an in-page dialog", async ({ page }) => {
    const local = await startDashboard();
    try {
      await page.goto(local.url);
      await page.locator("#attention-clarifications button", { hasText: "Open" }).click();
      await expect(page.locator("#clar-layer")).toBeVisible();
      await expect(page.locator("#clar-request")).toContainText("Does the bat drop to any of 20 rooms?");
      await page.locator("#clar-response").fill("Yes, any of the 20 rooms.");
      await expect(page.locator("#attention-clarifications textarea.clar-answer"))
        .toHaveValue("Yes, any of the 20 rooms.");
      await page.locator("#clar-ok").click();
      await expect(page.locator("#attention-clarifications .att-row")).toHaveCount(0);
      await expect(page.locator("#clar-layer")).toBeHidden();
    } finally {
      await stopDashboard(local);
    }
  });

  test("Clarification keeps the question's line breaks and takes a multi-line answer", async ({ page }) => {
    const local = await startDashboard();
    let posted = null;
    try {
      writeFile(
        path.join(local.root, "projects/htw/.swarmforge/dashboard/clarifications/pending/clar-1.request"),
        "id: clar-1\nstatus: pending\nrole: specifier\ncreated_at: 2026-01-01T00:00:00Z\n\n" +
          "Two questions:\n1. Does the bat drop to any of 20 rooms?\n2. Can it drop into a pit?\n"
      );
      await page.route("**/api/clarifications/**", async (route) => {
        posted = JSON.parse(route.request().postData() || "{}");
        await route.fulfill({ status: 200, contentType: "application/json", body: "{\"ok\":true}" });
      });
      await page.goto(local.url);
      const body = page.locator("#attention-clarifications .clar-body");
      await expect(body).toHaveCSS("white-space", "pre-wrap");
      expect(await body.evaluate((el) => el.textContent)).toContain("questions:\n1. Does");
      const answer = page.locator("#attention-clarifications textarea.clar-answer");
      await answer.fill("Yes.");
      await answer.press("Enter");
      await answer.type("No pits.");
      expect(posted).toBeNull();
      await answer.press("Control+Enter");
      await expect.poll(() => posted).not.toBeNull();
      expect(posted).toEqual({ text: "Yes.\nNo pits." });
    } finally {
      await stopDashboard(local);
    }
  });

  test("a paused project shows a banner and a Resume button", async ({ page }) => {
    const local = await startDashboard();
    try {
      writeFile(path.join(local.root, "projects/htw/.swarmforge/paused"), "now\n");
      await page.goto(local.url);
      const banner = page.locator(".project-band [data-banner=\"paused\"]");
      await expect(banner).toContainText("PAUSED");
      await expect(banner).toContainText("No task moves until Resume.");
      await expect(page.locator(".project-header [data-pause-toggle=\"resume\"]")).toHaveText("Resume");
      fs.rmSync(path.join(local.root, "projects/htw/.swarmforge/paused"));
      await expect(banner).toHaveCount(0);
      await expect(page.locator(".project-header [data-pause-toggle=\"pause\"]")).toHaveText("Pause");
    } finally {
      await stopDashboard(local);
    }
  });

  test("pending lieutenant chat shows green status under the request", async ({ page }) => {
    const local = await startDashboard();
    try {
      writeFile(
        path.join(local.root, ".swarmforge/dashboard/requests/pending/req-1.request"),
        "id: req-1\nstatus: pending\ncreated_at: 2026-01-01T00:00:00Z\n\nhi\n"
      );
      writeFile(
        path.join(local.root, ".swarmforge/sessions/lieutenant/pane.txt"),
        "I'm listing the open projects.\nI'll summarize HTW next.\n"
      );
      await page.goto(local.url);
      const status = page.locator("#chat-history [data-chat-id=\"req-1\"] .bubble-status");
      await expect(status).toContainText("| I'm listing the open projects.");
      await expect(status).toContainText("| I'll summarize HTW next.");
      await expect(status).toHaveCSS("color", "rgb(47, 107, 58)");
    } finally {
      await stopDashboard(local);
    }
  });

  test("chat stays put unless already at the bottom", async ({ page }) => {
    const local = await startDashboard();
    try {
      for (let i = 0; i < 12; i++) {
        writeFile(
          path.join(local.root, ".swarmforge/dashboard/requests/done/req-" + i + ".request"),
          "id: req-" + i + "\nstatus: done\ncreated_at: 2026-01-01T00:00:00Z\nresponse: reply " + i + "\\nmore\\n\n\nrequest " + i + " " + "word ".repeat(20) + "\n"
        );
      }
      await page.goto(local.url);
      const history = page.locator("#chat-history");
      await expect(history.locator("[data-chat-id]")).toHaveCount(12);
      await history.evaluate((el) => { el.scrollTop = 0; });
      const top = await history.evaluate((el) => el.scrollTop);
      await page.waitForTimeout(2500);
      expect(await history.evaluate((el) => el.scrollTop)).toBe(top);
      await history.evaluate((el) => { el.scrollTop = el.scrollHeight; });
      writeFile(
        path.join(local.root, ".swarmforge/dashboard/requests/done/req-bottom.request"),
        "id: req-bottom\nstatus: done\ncreated_at: 2026-01-01T00:01:00Z\nresponse: last\\n\n\nnew bottom\n"
      );
      await expect(history.locator("[data-chat-id=\"req-bottom\"]")).toBeVisible({ timeout: 5000 });
      const gap = await history.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight);
      expect(gap).toBeLessThanOrEqual(64);
    } finally {
      await stopDashboard(local);
    }
  });
});
