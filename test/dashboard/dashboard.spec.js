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
    await expect(win.locator("#dir-btn")).toHaveText("Directory");
    await win.locator("#dir-btn").click();
    await expect(win.locator("#tree")).toContainText("tasks");
    await win.locator(".dir-row", { hasText: "tasks" }).locator("button.toggle").click();
    await win.locator("button.leaf", { hasText: "HTW.md" }).click();
    await expect(win.locator("#file-body")).toContainText("Integrate the cave.");
    await expect(win.locator("#file-body")).toHaveAttribute("contenteditable", "false");
  });

  test("New Task focuses the name field", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator(".project-header button", { hasText: "New Task" }).click();
    await expect(page.locator("#nt-name")).toBeFocused();
    await expect(page.locator("input[name=nt-type][value=component]")).toBeChecked();
    await expect(page.locator("input[name=nt-type]")).toHaveCount(4);
  });

  test("New Project has no pack radios", async ({ page }) => {
    await page.goto(handle.url);
    await page.locator("#btn-new-project").click();
    await expect(page.locator("#np-packs")).toHaveCount(0);
    await expect(page.locator("input[name=np-pack]")).toHaveCount(0);
  });

  test("board card shows type", async ({ page }) => {
    await page.goto(handle.url);
    await expect(page.locator(".card .pill", { hasText: "QA" })).toBeVisible();
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

  test("Clarification Open posts the answer", async ({ page, context }) => {
    const local = await startDashboard();
    try {
      await page.goto(local.url);
      const popupPromise = context.waitForEvent("page");
      await page.locator("#attention-clarifications button", { hasText: "Open" }).click();
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

function mockForgeState(tasks) {
  return {
    forge: true,
    master_role: "lieutenant",
    master_display: "Lieutenant",
    packs: [{ name: "lieutenant", conf: "" }],
    all_projects: ["htw"],
    open_projects: ["htw"],
    projects: [{
      name: "htw",
      open: true,
      lanes: ["waiting", "specifier", "coder", "done"],
      tasks: tasks || [],
      work_in_flight: []
    }],
    approvals: [],
    board_allows: [],
    clarifications: [],
    chat: [],
    lieutenant_status: [],
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
    await expect(card.locator(".status")).toHaveText("Waiting to start");
    await expect(card.locator(".pill")).toHaveText("QA");
    expect(created).toMatchObject({ name: "UiFromMock", type: "QA", project: "htw" });
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
    await win.locator("#dir-btn").click();
    await win.locator(".dir-row", { hasText: "src" }).locator("button.toggle").click();
    await win.locator("button.leaf", { hasText: "x.clj" }).click();
    await expect(win.locator("#file-body")).toContainText(":k");
    await expect(win.locator("#file-body .kw")).toHaveCount(1);
    await win.locator(".dir-row", { hasText: "features" }).locator("button.toggle").click();
    await win.locator("button.leaf", { hasText: "console.feature" }).click();
    await expect(win.locator("#file-body .kw")).toHaveText("Feature");
    await win.locator("button.leaf", { hasText: "blob.bin" }).click();
    await expect(win.locator("#file-body")).toContainText("00000000");
    await expect(win.locator("#file-body")).toContainText("|");
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
});
