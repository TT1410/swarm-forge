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
    path.join(project, ".swarmforge/routes.tsv"),
    "utility\tspecifier,coder\n" +
      "component\tspecifier,coder\n" +
      "QA\tspecifier,coder\n" +
      "review\tspecifier,coder\n"
  );
  writeFile(
    path.join(project, ".swarmforge/board/tasks.tsv"),
    "HTW\tspecifier\t2026-01-01T00:00:00Z\t2026-01-01T00:00:00Z\t20260101T000000Z-htw\t0\tQA\n" +
      "UiShim\twaiting\t2026-01-01T00:00:00Z\t2026-01-01T00:00:00Z\t20260101T000000Z-uishim\t0\tcomponent\n"
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
    path.join(root, ".swarmforge/project-pack/swarmforge/swarmforge.conf"),
    "window specifier grok master\nwindow coder grok coder\n"
  );
  writeFile(path.join(root, ".swarmforge/project-pack/swarmforge/roles/specifier.prompt"), "spec\n");
  writeFile(path.join(root, ".swarmforge/project-pack/swarmforge/roles/coder.prompt"), "coder\n");
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
    let errBuf = "";
    const timer = setTimeout(
      () => reject(new Error("pack_web --serve timed out\n" + errBuf)),
      10000
    );
    child.stdout.on("data", (chunk) => {
      buf += chunk.toString();
      const line = buf.split("\n").find((item) => item.startsWith("http://"));
      if (line) {
        clearTimeout(timer);
        resolve(line.trim());
      }
    });
    child.stderr.on("data", (chunk) => {
      errBuf += chunk.toString();
    });
    child.on("error", reject);
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error("pack_web exited " + code + "\n" + buf + errBuf));
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

  test("has no Work Queue and shows a waiting lane", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator(".work-sec")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Work Queue" })).toHaveCount(0);
    await expect(page.locator('.col[data-lane="waiting"] h3')).toHaveText("Waiting");
    await expect(page.locator('.col[data-lane="waiting"] .card .name')).toHaveText("UiShim");
    await expect(page.locator('.col[data-lane="waiting"] .lane-title')).toHaveCount(0);
  });

  test("role lane title opens the agent session", async ({ page, context }) => {
    await page.goto(handle.url);
    const popupPromise = context.waitForEvent("page");
    await page.locator('.col[data-lane="specifier"] .lane-title').click();
    const win = await popupPromise;
    await win.waitForLoadState("domcontentloaded");
    await expect(win.locator("h1")).toContainText("specifier");
  });

  test("card window shows the task, audits, and Directory", async ({ page, context }) => {
    const project = path.join(handle.root, "projects/htw");
    writeFile(
      path.join(project, ".swarmforge/board/audits/20260101T000000Z-htw/1.md"),
      "# Audit 1\n\nRefused candidate.\n"
    );
    await page.goto(handle.url);
    const popupPromise = context.waitForEvent("page");
    await page.locator('.col[data-lane="specifier"] .card .name', { hasText: "HTW" }).click();
    const win = await popupPromise;
    await win.waitForLoadState("domcontentloaded");
    await expect(win.locator("#task-body")).toContainText("Integrate the cave.");
    await expect(win.locator(".audit")).toContainText("Audit 1");
    await expect(win.locator("#dir-btn")).toHaveCount(0);
    await expect(win.locator("#tree")).toContainText("tasks");
    await win.locator(".dir-row", { hasText: "tasks" }).locator("button.toggle").click();
    const filePromise = context.waitForEvent("page");
    await win.locator("button.leaf", { hasText: "HTW.md" }).click();
    const fileWin = await filePromise;
    await fileWin.waitForLoadState("domcontentloaded");
    await expect(fileWin.locator("#file-body")).toContainText("Integrate the cave.");
  });

  test("New Task has five type radios including LT", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await expect(page.locator("input[name=nt-type]")).toHaveCount(5);
    await expect(page.locator("input[name=nt-type][value=LT]")).toBeVisible();
  });

  test("lane titles show heat, cards do not", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator('.col[data-lane="specifier"] .lane-title .wif-therm')).toHaveCount(1);
    await expect(page.locator(".card .wif-therm")).toHaveCount(0);
    await expect(page.locator('.col[data-lane="waiting"] .wif-therm')).toHaveCount(0);
  });

  test("New Task focuses the name field", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await expect(page.locator("#nt-name")).toBeFocused();
    await expect(page.locator("input[name=nt-type][value=LT]")).toBeChecked();
    await expect(page.locator("#nt-note")).toContainText("Does not create a card");
    await expect(page.locator("input[name=nt-type]")).toHaveCount(5);
  });

  test("New Project has no pack radios", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator("#btn-new-project").click();
    await expect(page.locator("#np-packs")).toHaveCount(0);
    await expect(page.locator("input[name=np-pack]")).toHaveCount(0);
  });

  test("board card shows type", async ({ page }) => {
    await page.goto(handle.url);
    const card = page.locator('.card[data-task-name="HTW"]');
    await expect(card.locator(".pill", { hasText: "QA" })).toBeVisible();
    const rows = await card.evaluate((el) =>
      Array.from(el.children).map((child) => child.className)
    );
    expect(rows.slice(0, 2)).toEqual(["card-meta", "title"]);
    const positions = await card.evaluate((el) => {
      const cardBox = el.getBoundingClientRect();
      const typeBox = el.querySelector(".pill").getBoundingClientRect();
      const titleBox = el.querySelector(".title").getBoundingClientRect();
      return {
        typeLeft: Math.round(typeBox.left),
        contentLeft: Math.round(cardBox.left + 9),
        typeTop: Math.round(typeBox.top),
        titleTop: Math.round(titleBox.top)
      };
    });
    expect(positions.typeLeft).toBe(positions.contentLeft);
    expect(positions.titleTop).toBeGreaterThan(positions.typeTop);
  });

  test("Attention lists approvals and clarifications", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator("#attention-approvals .att-row")).toContainText("HTW");
    await expect(page.locator("#attention-approvals .att-row")).toContainText("Approve");
    await expect(page.locator("#attention-approvals .att-row")).toContainText("Add comment");
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
    await expect(doc.locator("#doc-body .kw")).toHaveText("Feature");
    await expect(doc.locator("#doc-body")).toContainText("Feature: console");
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
    await expect.poll(() => fs.existsSync(reviewsPath)).toBeTruthy();
    expect(JSON.parse(fs.readFileSync(reviewsPath, "utf8"))["features/console.feature"]).toBe("");
    await page.reload();
    await expect(page.locator("#attention-approvals .doc-mark-ok")).toHaveCount(1);

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

  test("Add comment opens the retry dialog", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator("#attention-approvals button", { hasText: "Add comment" }).click();
    await expect(page.locator("#reject-layer")).toHaveClass(/open/);
    await expect(page.locator("#rt-title")).toHaveText("HTW");
    await expect(page.locator("#rt-retry")).toHaveText("Try Again");
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
      await page.locator("#attention-approvals button", { hasText: "Add comment" }).click();
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

  test("Attention truncates long text instead of scrolling", async ({ page }) => {
    const request = path.join(
      handle.root,
      "projects/htw/.swarmforge/dashboard/clarifications/pending/clar-long.request"
    );
    writeFile(
      request,
      "id: clar-long\nstatus: pending\nrole: specifier\ncreated_at: 2026-01-01T00:00:00Z\n\n" +
        "This deliberately long clarification keeps going so the compact Attention bar " +
        "must shorten it with an ellipsis while the full-window control remains visible.\n"
    );
    try {
      await page.setViewportSize({ width: 760, height: 700 });
      await page.goto(handle.url);
      const row = page.locator("#attention-clarifications .att-row", { hasText: "deliberately long" });
      const summary = row.locator(".att-summary");
      await expect(page.locator("#attention")).toHaveCSS("overflow", "hidden");
      await expect(row).toHaveCSS("flex-wrap", "nowrap");
      await expect(summary).toHaveCSS("text-overflow", "ellipsis");
      await expect(row.locator(".att-expand")).toBeVisible();
      expect(await summary.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
    } finally {
      fs.unlinkSync(request);
    }
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

  test("Attention Allow writes lieutenant approval on disk", async ({ page }) => {
    const local = await startDashboard();
    try {
      writeFile(
        path.join(local.root, "projects/htw/.swarmforge/board/lt-allow-pending/HTW-move"),
        "name: HTW\nact: move\n"
      );
      await page.goto(local.url);
      const row = page.locator("#attention-allows .att-row");
      await expect(row).toContainText("HTW");
      await expect(row).toContainText("Allow move");
      await row.locator("button", { hasText: "Allow" }).click();
      await expect(page.locator("#attention-allows .att-row")).toHaveCount(0);
      const allow = path.join(local.root, "projects/htw/.swarmforge/board/lt-allow/HTW-move");
      await expect.poll(() => fs.existsSync(allow)).toBe(true);
      expect(fs.existsSync(path.join(
        local.root,
        "projects/htw/.swarmforge/board/lt-allow-pending/HTW-move"
      ))).toBe(false);
    } finally {
      await stopDashboard(local);
    }
  });

  test("Clarification expand icon OK posts the answer and closes attention", async ({ page, context }) => {
    const local = await startDashboard();
    try {
      await page.goto(local.url);
      const popupPromise = context.waitForEvent("page");
      await page.locator("#attention-clarifications .att-expand").click();
      const clar = await popupPromise;
      await clar.waitForLoadState("domcontentloaded");
      await expect(clar.locator("#clar-request")).toContainText("Does the bat drop to any of 20 rooms?");
      await clar.locator("#clar-response").fill("Yes, any of the 20 rooms.");
      await clar.locator("#clar-ok").click();
      await expect(page.locator("#attention-clarifications .att-row")).toHaveCount(0);
    } finally {
      await stopDashboard(local);
    }
  });

  test("Clarification Dismiss closes the window and leaves attention", async ({ page, context }) => {
    const local = await startDashboard();
    try {
      await page.goto(local.url);
      const popupPromise = context.waitForEvent("page");
      await page.locator("#attention-clarifications .att-expand").click();
      const clar = await popupPromise;
      await clar.waitForLoadState("domcontentloaded");
      await clar.locator("#clar-response").fill("draft only");
      await clar.locator("#clar-dismiss").click();
      await expect(clar.isClosed()).toBeTruthy();
      await expect(page.locator("#attention-clarifications .att-row")).toHaveCount(1);
      await expect(page.locator("#attention-clarifications [data-clar-id]")).toHaveValue("draft only");
    } finally {
      await stopDashboard(local);
    }
  });

  test("lieutenant status stays live outside the response history", async ({ page }) => {
    const local = await startDashboard();
    try {
      writeFile(
        path.join(local.root, ".swarmforge/sessions/lieutenant/pane.txt"),
        "I'm listing the open projects.\nI'll summarize HTW next.\n"
      );
      await page.goto(local.url);
      const status = page.locator("#lieutenant-status");
      const lines = status.locator(".lt-status-line");
      await expect(lines).toHaveCount(2);
      await expect(lines).toHaveText([
        "I'm listing the open projects.",
        "I'll summarize HTW next."
      ]);
      await expect(status).toHaveCSS("color", "rgb(47, 107, 58)");
      await expect(status).toHaveCSS("border-top-width", "0px");
      expect(await status.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(
        await page.locator(".rail section.ts").evaluate((el) => getComputedStyle(el).backgroundColor)
      );
      writeFile(
        path.join(local.root, ".swarmforge/dashboard/requests/pending/req-1.request"),
        "id: req-1\nstatus: pending\ncreated_at: 2026-01-01T00:00:00Z\n\nhi\n"
      );
      await page.reload();
      await expect(page.locator("#lieutenant-status .lt-status-line")).toHaveCount(2);
      await expect(page.locator("#chat-history .bubble-status")).toHaveCount(0);
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

function mockForgeState(tasks, extras) {
  extras = extras || {};
  return {
    forge: true,
    master_role: "lieutenant",
    master_display: "Lieutenant",
    packs: [{ name: "lieutenant", conf: "" }],
    all_projects: extras.all_projects || ["htw"],
    open_projects: extras.open_projects || ["htw"],
    projects: extras.projects || [{
      name: "htw",
      open: true,
      card_types: extras.card_types || ["utility", "component", "QA", "review"],
      lanes: ["waiting", "specifier", "coder", "done"],
      tasks: tasks || [],
      role_heats: extras.role_heats || { specifier: 0, coder: 0 },
      work_in_flight: []
    }],
    approvals: extras.approvals || [],
    board_allows: [],
    clarifications: [],
    chat: [],
    lieutenant_status: [],
    lieutenant_activity: extras.lieutenant_activity || 0,
    lanes: [],
    tasks: [],
    work_in_flight: []
  };
}

test.describe("mocked dashboard buttons", () => {
  let handle;

  test.beforeAll(async () => {
    handle = await startDashboard();
  });

  test.afterAll(async () => {
    await stopDashboard(handle);
  });

  test("swimlane shows the active card above queued cards", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([
          { name: "Newer", lane: "specifier", type: "QA", status: "waiting in queue",
            updated_at: "2026-01-03T00:00:00Z", project: "htw" },
          { name: "Older", lane: "specifier", type: "QA", status: "waiting in queue",
            updated_at: "2026-01-01T00:00:00Z", project: "htw" },
          { name: "Active", lane: "specifier", type: "QA", status: "I'm writing the spec.",
            updated_at: "2026-01-02T00:00:00Z", project: "htw" }
        ])
      });
    });
    await page.goto(handle.url);
    const names = page.locator('.col[data-lane="specifier"] .card .name');
    await expect(names).toHaveText(["Active", "Older", "Newer"]);
  });

  test("active cards reserve five lines while batch children and edge cards stay two lines", async ({ page }) => {
    const longStatus = "one two three four five six seven eight nine ten eleven twelve " +
      "thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty " +
      "twenty-one twenty-two twenty-three twenty-four twenty-five";
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([
          {
            name: "Primary",
            lane: "specifier",
            type: "QA",
            status: longStatus,
            batch: "batch-1",
            updated_at: "2026-01-01T00:00:00Z",
            project: "htw"
          },
          {
            name: "Child",
            lane: "specifier",
            type: "QA",
            status: longStatus,
            batch: "batch-1",
            updated_at: "2026-01-02T00:00:00Z",
            project: "htw"
          },
          {
            name: "No status",
            lane: "coder",
            type: "component",
            status: "",
            project: "htw"
          },
          {
            name: "Waiting card",
            lane: "waiting",
            type: "component",
            status: "Waiting to start",
            project: "htw"
          },
          {
            name: "Done card",
            lane: "done",
            type: "component",
            status: longStatus,
            project: "htw"
          }
        ])
      });
    });
    await page.goto(handle.url);
    const primary = page.locator('.card[data-task-name="Primary"]');
    const child = page.locator('.card[data-task-name="Child"]');
    const empty = page.locator('.card[data-task-name="No status"]');
    const waiting = page.locator('.card[data-task-name="Waiting card"]');
    const done = page.locator('.card[data-task-name="Done card"]');
    await expect(primary).toHaveCSS("height", "96px");
    await expect(empty).toHaveCSS("height", "96px");
    await expect(child).toHaveCSS("height", "45px");
    await expect(waiting).toHaveCSS("height", "45px");
    await expect(done).toHaveCSS("height", "45px");
    await expect(primary.locator(".status")).toHaveCount(1);
    await expect(empty.locator(".status")).toHaveCount(1);
    await expect(child.locator(".status")).toHaveCount(0);
    await expect(waiting.locator(".status")).toHaveCount(0);
    await expect(done.locator(".status")).toHaveCount(0);
    const statusGeometry = await primary.locator(".status").evaluate((el) => ({
      height: el.clientHeight,
      lineHeight: parseFloat(getComputedStyle(el).lineHeight),
      scrollHeight: el.scrollHeight
    }));
    expect(statusGeometry.height).toBe(statusGeometry.lineHeight * 3);
    expect(statusGeometry.scrollHeight).toBeGreaterThan(statusGeometry.height);
  });

  test("New Task OK parks the card in waiting", async ({ page }) => {
    let created = null;
    await page.route("**/api/state", (route) => {
      const tasks = [
        { name: "HTW", lane: "specifier", type: "QA", status: "working", audit_count: 0, project: "htw" }
      ];
      if (created) {
        tasks.push({
          name: created.name,
          lane: "waiting",
          type: created.type || "component",
          status: "Waiting to start",
          audit_count: 0,
          project: "htw"
        });
      }
      route.fulfill({ json: mockForgeState(tasks) });
    });
    await page.route("**/api/tasks", async (route) => {
      if (route.request().method() === "POST") {
        created = JSON.parse(route.request().postData() || "{}");
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ ok: true })
        });
      }
      return route.continue();
    });
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await page.locator("input[name=nt-type][value=QA]").check();
    await page.locator("#nt-name").fill("UiFromMock");
    await page.locator("#nt-text").fill("Build the shim.");
    await page.locator("#nt-ok").click();
    await expect(page.locator("#new-task-layer")).not.toHaveClass(/open/);
    const card = page.locator('.col[data-lane="waiting"] .card', { hasText: "UiFromMock" });
    await expect(card).toBeVisible();
    await expect(card.locator(".status")).toHaveCount(0);
    await expect(card.locator(".pill")).toHaveText("QA");
    expect(created).toMatchObject({ name: "UiFromMock", type: "QA", project: "htw" });
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await expect(page.locator("input[name=nt-type][value=LT]")).toBeChecked();
    await expect(page.locator("#nt-note")).toContainText("Does not create a card");
  });

  test("New Task LT does not park a card", async ({ page }) => {
    let created = null;
    await page.route("**/api/state", (route) => {
      const tasks = [
        { name: "HTW", lane: "specifier", type: "QA", status: "working", audit_count: 0, project: "htw" }
      ];
      if (created && created.type !== "LT") {
        tasks.push({
          name: created.name,
          lane: "waiting",
          type: created.type || "component",
          status: "Waiting to start",
          audit_count: 0,
          project: "htw"
        });
      }
      route.fulfill({ json: mockForgeState(tasks) });
    });
    await page.route("**/api/tasks", async (route) => {
      if (route.request().method() === "POST") {
        created = JSON.parse(route.request().postData() || "{}");
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ ok: true })
        });
      }
      return route.continue();
    });
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await page.locator("input[name=nt-type][value=LT]").check();
    await page.locator("#nt-name").fill("Shim");
    await page.locator("#nt-text").fill("fit this");
    await page.locator("#nt-ok").click();
    await expect(page.locator("#new-task-layer")).not.toHaveClass(/open/);
    expect(created).toMatchObject({ name: "Shim", type: "LT", project: "htw" });
    await expect(page.locator('.col[data-lane="waiting"] .card', { hasText: "Shim" })).toHaveCount(0);
  });

  test("Directory leaves show clj, gherkin, and binary", async ({ page, context }) => {
    await context.route("**/api/tree**", async (route) => {
      const url = new URL(route.request().url());
      const rel = url.searchParams.get("path") || "";
      const entries =
        rel === ""
          ? [
              { name: "src", dir: true, path: "src" },
              { name: "features", dir: true, path: "features" },
              { name: "blob.bin", dir: false, path: "blob.bin" }
            ]
          : rel === "src"
            ? [{ name: "x.clj", dir: false, path: "src/x.clj" }]
            : rel === "features"
              ? [{ name: "console.feature", dir: false, path: "features/console.feature" }]
              : [];
      await route.fulfill({ json: { path: rel, entries } });
    });
    await context.route("**/api/file**", async (route) => {
      const url = new URL(route.request().url());
      const rel = url.searchParams.get("path") || "";
      const body = rel.endsWith(".clj")
        ? {
            path: rel,
            kind: "code",
            html: "<table class='src'><tr><td class='ln'>1</td><td class='code'><pre><span class='kw'>:k</span></pre></td></tr></table>"
          }
        : rel.endsWith(".feature")
          ? {
              path: rel,
              kind: "code",
              html: "<table class='src'><tr><td class='ln'>1</td><td class='code'><pre><span class='kw'>Feature</span>: console</pre></td></tr></table>"
            }
          : rel.endsWith(".bin")
            ? { path: rel, kind: "binary", text: "00000000  00 01 48 69                                      |.Hi|" }
            : { path: rel, kind: "text", text: "not found" };
      await route.fulfill({ json: body });
    });
    await page.goto(handle.url);
    const popupPromise = context.waitForEvent("page");
    await page.locator('.col[data-lane="specifier"] .card .name', { hasText: "HTW" }).click();
    const win = await popupPromise;
    await win.waitForLoadState("domcontentloaded");
    await win.locator(".dir-row", { hasText: "src" }).locator("button.toggle").click();
    const cljPromise = context.waitForEvent("page");
    await win.locator("button.leaf", { hasText: "x.clj" }).click();
    const cljWin = await cljPromise;
    await cljWin.waitForLoadState("domcontentloaded");
    await expect(cljWin.locator("#file-body")).toContainText(":k");
    await expect(cljWin.locator("#file-body .kw")).toHaveCount(1);
    await cljWin.close();
    await win.locator(".dir-row", { hasText: "features" }).locator("button.toggle").click();
    const featPromise = context.waitForEvent("page");
    await win.locator("button.leaf", { hasText: "console.feature" }).click();
    const featWin = await featPromise;
    await featWin.waitForLoadState("domcontentloaded");
    await expect(featWin.locator("#file-body .kw")).toHaveText("Feature");
    await featWin.close();
    const binPromise = context.waitForEvent("page");
    await win.locator("button.leaf", { hasText: "blob.bin" }).click();
    const binWin = await binPromise;
    await binWin.waitForLoadState("domcontentloaded");
    await expect(binWin.locator("#file-body")).toContainText("00000000");
    await expect(binWin.locator("#file-body")).toContainText("|");
  });

  test("Teardown confirm posts teardown", async ({ page }) => {
    let posted = false;
    await page.route("**/api/teardown", async (route) => {
      posted = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ ok: true })
      });
    });
    await page.goto(handle.url);
    page.once("dialog", (dialog) => dialog.accept());
    await page.locator("#teardown-btn").click();
    await expect.poll(() => posted).toBe(true);
    await expect(page.locator("#pack-meta")).toContainText("teardown started");
  });

  test("Lieutenant title has a thermometer", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({ json: mockForgeState([], { lieutenant_activity: 4 }) });
    });
    await page.goto(handle.url);
    await expect(page.locator("#master-title")).toHaveText("Lieutenant");
    const therm = page.locator(".ts-head .wif-therm");
    await expect(therm).toHaveCount(1);
    await expect(therm).toHaveAttribute("data-heat", "4");
    expect(await therm.evaluate((el) => getComputedStyle(el, "::after").animationName)).toBe("wif-cylon");
    expect(await therm.evaluate((el) => getComputedStyle(el, "::after").animationDirection)).toBe("alternate");
  });

  test("thermometer heat controls scanner speed", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([], {
          lieutenant_activity: 0,
          all_projects: ["htw", "empire"],
          open_projects: ["htw", "empire"],
          projects: [
            {
              name: "htw", open: true, lanes: ["specifier"], tasks: [],
              role_heats: { specifier: 1 }, work_in_flight: []
            },
            {
              name: "empire", open: true, lanes: ["specifier"], tasks: [],
              role_heats: { specifier: 6 }, work_in_flight: []
            }
          ]
        })
      });
    });
    await page.goto(handle.url);
    const idle = page.locator(".ts-head .wif-therm");
    const cool = page.locator('.project-band[data-project="htw"] .wif-therm');
    const hot = page.locator('.project-band[data-project="empire"] .wif-therm');
    const duration = (locator) => locator.evaluate((el) =>
      parseFloat(getComputedStyle(el, "::after").animationDuration)
    );
    await expect(idle).toHaveAttribute("data-heat", "0");
    expect(await idle.evaluate((el) => getComputedStyle(el, "::after").animationName)).toBe("none");
    expect(await duration(hot)).toBeLessThan(await duration(cool));
  });

  test("Lieutenant title opens the session and there is no Open button", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({ json: mockForgeState([]) });
    });
    await page.route("**/agent/lieutenant**", (route) => {
      route.fulfill({ status: 200, contentType: "text/html", body: "<html><body>lt</body></html>" });
    });
    await page.goto(handle.url);
    await expect(page.locator("#btn-open-master-rail")).toHaveCount(0);
    const title = page.locator("#master-title");
    await expect(title).toHaveAttribute("data-open-agent", "lieutenant");
    const popupPromise = page.waitForEvent("popup");
    await title.click();
    const popup = await popupPromise;
    await expect(popup).toHaveURL(/\/agent\/lieutenant/);
    await popup.close();
  });

  test("lane heat is per project and not on cards", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([], {
          all_projects: ["htw", "empire"],
          open_projects: ["htw", "empire"],
          projects: [
            {
              name: "htw",
              open: true,
              lanes: ["waiting", "specifier", "coder", "done"],
              tasks: [{ name: "HTW", lane: "specifier", type: "QA", project: "htw" }],
              role_heats: { specifier: 5, coder: 0 },
              work_in_flight: []
            },
            {
              name: "empire",
              open: true,
              lanes: ["waiting", "specifier", "coder", "done"],
              tasks: [{ name: "Review", lane: "coder", type: "review", project: "empire" }],
              role_heats: { specifier: 0, coder: 2 },
              work_in_flight: []
            }
          ]
        })
      });
    });
    await page.goto(handle.url);
    const htw = page.locator('.project-band[data-project="htw"]');
    const empire = page.locator('.project-band[data-project="empire"]');
    await expect(htw.locator('.col[data-lane="specifier"] .lane-title .wif-therm')).toHaveAttribute("data-heat", "5");
    await expect(empire.locator('.col[data-lane="specifier"] .lane-title .wif-therm')).toHaveAttribute("data-heat", "0");
    await expect(empire.locator('.col[data-lane="coder"] .lane-title .wif-therm')).toHaveAttribute("data-heat", "2");
    await expect(page.locator(".card .wif-therm")).toHaveCount(0);
    await expect(htw.locator('.col[data-lane="waiting"] .wif-therm')).toHaveCount(0);
  });

  test("role swimlanes stay fixed while Waiting and Done are narrower", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([
          {
            name: "A deliberately long waiting card that must not widen its lane",
            lane: "waiting",
            type: "component",
            project: "htw"
          },
          {
            name: "A deliberately long card name that would otherwise widen its lane",
            lane: "specifier",
            type: "component",
            project: "htw"
          }
        ])
      });
    });
    await page.goto(handle.url);
    const widths = await page.locator(".col").evaluateAll((lanes) =>
      Object.fromEntries(lanes.map((lane) => [
        lane.dataset.lane,
        Math.round(lane.getBoundingClientRect().width)
      ]))
    );
    expect(widths.waiting).toBe(148);
    expect(widths.done).toBe(148);
    expect(widths.specifier).toBe(185);
    expect(widths.coder).toBe(185);
  });

  test("QA documents get a qa suffix", async ({ page }) => {
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([], {
          approvals: [{
            id: "50_hello",
            task: "HTW",
            project: "htw",
            artifacts: ["features/yob-console.feature", "qa/yob-console.feature"],
            reviews: {}
          }]
        })
      });
    });
    await page.goto(handle.url);
    await page.locator("#attention-approvals .menu > button").click();
    const labels = page.locator("#attention-approvals .menu-list button");
    await expect(labels).toHaveCount(2);
    await expect(labels.nth(0)).toContainText("yob-console.feature");
    await expect(labels.nth(0)).not.toContainText(".qa");
    await expect(labels.nth(1)).toContainText("yob-console.qa.feature");
  });

  test("board scrolls as one pane across projects", async ({ page }) => {
    const waiting = [];
    for (let i = 0; i < 18; i++) {
      waiting.push({ name: "Wait" + i, lane: "waiting", type: "component", project: "htw" });
    }
    const lanes = ["waiting", "specifier", "coder", "cleaner", "architect", "hardender", "QA", "done"];
    await page.route("**/api/state", (route) => {
      route.fulfill({
        json: mockForgeState([], {
          all_projects: ["htw", "empire"],
          open_projects: ["htw", "empire"],
          projects: [
            {
              name: "htw",
              open: true,
              lanes,
              tasks: waiting,
              role_heats: {},
              work_in_flight: []
            },
            {
              name: "empire",
              open: true,
              lanes,
              tasks: [{ name: "Review", lane: "specifier", type: "review", project: "empire" }],
              role_heats: {},
              work_in_flight: []
            }
          ]
        })
      });
    });
    await page.goto(handle.url);
    const board = page.locator(".board");
    await board.evaluate((el) => {
      el.style.height = "220px";
      el.style.width = "420px";
      el.style.maxHeight = "220px";
      el.style.maxWidth = "420px";
    });
    const size = await board.evaluate((el) => ({
      sw: el.scrollWidth,
      sh: el.scrollHeight,
      cw: el.clientWidth,
      ch: el.clientHeight
    }));
    expect(size.sw).toBeGreaterThan(size.cw);
    expect(size.sh).toBeGreaterThan(size.ch);
    const before = await page.locator(".project-band").evaluateAll((els) =>
      els.map((el) => ({ left: el.getBoundingClientRect().left, top: el.getBoundingClientRect().top }))
    );
    expect(before[0].left).toBe(before[1].left);
    await board.evaluate((el) => {
      el.scrollLeft = 80;
      el.scrollTop = 40;
    });
    const after = await page.locator(".project-band").evaluateAll((els) =>
      els.map((el) => ({ left: el.getBoundingClientRect().left, top: el.getBoundingClientRect().top }))
    );
    expect(after[0].left).toBe(after[1].left);
    expect(after[0].left).toBeLessThan(before[0].left);
    expect(after[1].top - after[0].top).toBeCloseTo(before[1].top - before[0].top, 0);
  });
});
