async function loadState() {
  try {
    const res = await fetch("/api/state", {cache: "no-store"});
    if (!res.ok) throw new Error("offline");
    const data = await res.json();
    $("error").textContent = "";
    renderChrome(data);
    renderBoard(data);
    renderAttention(data);
    renderChat(data);
  } catch (_) {
    $("error").textContent = "Swarm disconnected";
  }
}

function closeNewTask() {
  $("new-task-layer").classList.remove("open");
  taskProject = "";
}

function defaultTaskType() {
  const lieutenant = document.querySelector("input[name=nt-type][value=LT]");
  if (taskProject && lieutenant) return "LT";
  const first = document.querySelector("input[name=nt-type]");
  return first ? first.value : "";
}

function selectedType() {
  const box = document.querySelector("input[name=nt-type]:checked");
  return box ? box.value : defaultTaskType();
}

function updateNewTaskNote() {
  const note = $("nt-note");
  if (!note) return;
  note.textContent = selectedType() === "LT"
    ? "Sends the name and text to the lieutenant. Does not create a card."
    : "Creates a waiting card. The lieutenant starts it when the plan is ready.";
}

function resetTypeRadios() {
  const def = defaultTaskType();
  document.querySelectorAll("input[name=nt-type]").forEach((el) => {
    el.checked = el.value === def;
  });
}

function fillTaskTypes(project) {
  const host = $("nt-types");
  const forge = !!project;
  const types = forge ? (cardTypesByProject[project] || []) : standaloneCardTypes;
  host.replaceChildren();
  types.forEach((type) => {
    const label = document.createElement("label");
    const input = document.createElement("input");
    input.type = "radio";
    input.name = "nt-type";
    input.value = type;
    label.append(input, " " + displayName(type));
    host.appendChild(label);
  });
  if (forge) {
    const label = document.createElement("label");
    label.id = "nt-type-lt";
    const input = document.createElement("input");
    input.type = "radio";
    input.name = "nt-type";
    input.value = "LT";
    input.checked = true;
    label.append(input, " LT");
    host.appendChild(label);
  } else if (host.querySelector("input")) {
    host.querySelector("input").checked = true;
  }
}

function openNewTask(project) {
  taskProject = project || "";
  fillTaskTypes(taskProject);
  resetTypeRadios();
  updateNewTaskNote();
  $("new-task-layer").classList.add("open");
  $("nt-name").focus();
}

function selectedPack() {
  return "lieutenant";
}

function updateInferred() {
  const github = $("np-github").checked;
  const raw = $("np-name").value.trim();
  let name = raw;
  if (github) {
    name = raw.replace(/\.git$/, "").replace(/^https?:\/\/github.com\//, "");
    name = name.split("/").filter(Boolean).pop() || name;
  }
  $("np-inferred").textContent = github && name ? "Directory: " + name : "";
}

function fillPackRadios() {
  const conf = $("np-conf");
  if (!conf) return;
  const pack = forgePacks.find((p) => p.name === "lieutenant") || forgePacks[0];
  if (pack) conf.value = pack.conf || "";
}

async function refreshForgePacks() {
  try {
    const res = await fetch("/api/state", {cache: "no-store"});
    if (!res.ok) return;
    const data = await res.json();
    if (data.packs) forgePacks = data.packs;
    if (data.all_projects) allProjects = data.all_projects;
    if (data.open_projects) openProjects = data.open_projects;
    if (data.project_states) projectStates = data.project_states;
  } catch (_) {}
}

function openNewProject() {
  refreshForgePacks().then(() => fillPackRadios());
  fillPackRadios();
  $("np-name").value = "";
  $("np-github").checked = false;
  $("np-mission").value = "";
  updateInferred();
  $("np-ok").disabled = false;
  $("np-ok").textContent = "OK";
  $("new-project-layer").classList.add("open");
  $("np-name").focus();
}

function closeNewProject() {
  $("new-project-layer").classList.remove("open");
}

async function submitNewProject() {
  const name = $("np-name").value.trim();
  const pack = selectedPack() || (forgePacks[0] && forgePacks[0].name) || "";
  if (!name) {
    alert("Name is required.");
    $("np-name").focus();
    return;
  }
  if (!pack) {
    alert("Lieutenant pack template is missing (.swarmforge/project-pack).");
    return;
  }
  const btn = $("np-ok");
  btn.disabled = true;
  btn.textContent = "Creating…";
  try {
    const payload = {
      name,
      github: $("np-github").checked,
      pack,
      conf: $("np-conf").value,
      mission: $("np-mission").value
    };
    let res = await fetch("/api/projects", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify(payload)
    });
    if (res.status === 409) {
      const replace = confirm(
        "This project directory already exists. Clear and replace it? " +
        "This permanently deletes every file in that directory. SwarmForge keeps no backup."
      );
      if (!replace) return;
      payload.replace = true;
      btn.textContent = "Replacing…";
      res = await fetch("/api/projects", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify(payload)
      });
    }
    if (!res.ok) {
      let msg = "Could not create project";
      try { msg = (await res.json()).error || msg; } catch (_) {}
      alert(msg);
      return;
    }
    closeNewProject();
    loadState();
  } catch (err) {
    alert("Could not create project: " + (err && err.message ? err.message : err));
  } finally {
    btn.disabled = false;
    btn.textContent = "OK";
  }
}

async function openProject(name) {
  const res = await fetch("/api/projects/open", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name})
  });
  if (!res.ok) {
    let msg = "Could not open project";
    try { msg = (await res.json()).error || msg; } catch (_) {}
    alert(msg);
    return;
  }
  loadState();
}

async function closeProject(name) {
  const res = await fetch("/api/projects/close", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name})
  });
  if (!res.ok) {
    let msg = "Could not close project";
    try { msg = (await res.json()).error || msg; } catch (_) {}
    alert(msg);
    return;
  }
  loadState();
}

async function submitNewTask() {
  const name = $("nt-name").value.trim();
  const text = $("nt-text").value;
  if (!name) return;
  const payload = {name, text, type: selectedType()};
  if (taskProject) payload.project = taskProject;
  const res = await fetch("/api/tasks", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(payload)
  });
  if (!res.ok) {
    let msg = "Could not create task";
    try {
      const body = await res.json();
      msg = body.error || msg;
    } catch (_) {}
    alert(msg);
    $("nt-name").focus();
    return;
  }
  $("nt-name").value = "";
  $("nt-text").value = "";
  resetTypeRadios();
  closeNewTask();
  loadState();
}

async function deleteTask(name) {
  if (!name) return;
  await fetch("/api/tasks/delete", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name})
  });
  loadState();
}

let rejectApprovalId = "";
let rejectApprovalProject = "";
let rejectHasComments = false;
function closeRejectDialog() {
  $("reject-layer").classList.remove("open");
  rejectApprovalId = "";
  rejectApprovalProject = "";
  rejectHasComments = false;
}

function openRejectDialog(item) {
  rejectApprovalId = item && item.id ? item.id : "";
  rejectApprovalProject = (item && item.project) || "";
  rejectHasComments = hasRemedialComments(item);
  $("rt-title").textContent = (item && item.task) || "Rejected task";
  $("rt-text").value = "";
  $("reject-layer").classList.add("open");
}

async function retryRejected() {
  if (!rejectApprovalId) return;
  const payload = {id: rejectApprovalId, comments: $("rt-text").value};
  if (rejectApprovalProject) payload.project = rejectApprovalProject;
  await fetch("/api/tasks/retry", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(payload)
  });
  closeRejectDialog();
  loadState();
}

async function deleteRejected() {
  if (!rejectApprovalId) return;
  const payload = {id: rejectApprovalId};
  if (rejectApprovalProject) payload.project = rejectApprovalProject;
  await fetch("/api/tasks/delete", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(payload)
  });
  closeRejectDialog();
  loadState();
}

async function acceptRejected() {
  if (!rejectApprovalId) return;
  if (rejectHasComments && !confirm("Remedial comments will be ignored.")) return;
  await fetch("/api/approvals/" + encodeURIComponent(rejectApprovalId) + "/approve", {method: "POST"});
  closeRejectDialog();
  loadState();
}

function bindSplitter() {
  const split = document.querySelector(".splitter");
  const body = document.querySelector(".body");
  if (!split || !body) return;
  split.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const move = (e) => {
      const rect = body.getBoundingClientRect();
      const width = Math.max(220, Math.min(rect.width - 180, rect.right - e.clientX));
      document.documentElement.style.setProperty("--rail", width + "px");
    };
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  });
}

async function openMission(project) {
  const qs = "/api/mission" + (project ? "?project=" + encodeURIComponent(project) : "");
  const res = await fetch(qs);
  const text = res.ok ? await res.text() : "";
  const win = window.open(
    "about:blank",
    "mission-" + encodeURIComponent(project || "") + "-" + Date.now(),
    "resizable=yes,scrollbars=yes,width=640,height=480"
  );
  if (!win) return;
  win.document.open();
  win.document.write(
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Mission</title>" +
    "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}" +
    "header{flex:0 0 auto;padding:8px 10px;background:linear-gradient(180deg,#eceee8,#e0e3dc);border-bottom:1px solid #d5d9d2;font-weight:600;font-size:13px}" +
    "pre{flex:1 1 auto;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;background:#fffef9;font:13px/1.45 ui-sans-serif,system-ui,sans-serif}</style></head><body>" +
    "<header>" + escapeHtml(project || "Mission") + "</header>" +
    "<pre id=\"mission-body\">" + escapeHtml(text) + "</pre></body></html>"
  );
  win.document.close();
}

function chatToEnd() {
  const history = $("chat-history");
  history.scrollTop = history.scrollHeight;
}

function chatAtBottom(history) {
  return (history.scrollHeight - history.scrollTop - history.clientHeight) <= 64;
}

function statusText(lines) {
  return (lines || []).slice(0, 2).map((line) => "| " + line).join("\n");
}

function statusBlock(lines) {
  const el = document.createElement("div");
  el.className = "bubble-status";
  el.textContent = statusText(lines);
  return el;
}

function chatTurn(item, statusLines) {
  const wrap = document.createElement("div");
  wrap.dataset.chatId = item.id || "";
  const you = document.createElement("div");
  you.className = "bubble-you";
  you.textContent = item.body || "";
  wrap.appendChild(you);
  if (item.response) {
    const reply = document.createElement("div");
    reply.className = "bubble-ts";
    reply.textContent = item.response;
    wrap.appendChild(reply);
  } else if (statusLines && statusLines.length) {
    wrap.appendChild(statusBlock(statusLines));
  }
  return wrap;
}

function pendingChatId(items) {
  const pending = (items || []).filter((item) => item.status === "pending" && !item.response);
  return pending.length ? pending[pending.length - 1].id : "";
}

function renderChat(data) {
  const history = $("chat-history");
  const items = data.chat || [];
  const pinBottom = chatAtBottom(history);
  const live = {};
  items.forEach((item) => {
    live[item.id] = true;
  });
  [...history.querySelectorAll("[data-chat-id]")].forEach((el) => {
    if (!live[el.dataset.chatId]) el.remove();
  });
  const latestPending = pendingChatId(items);
  const statusLines = data.lieutenant_status || [];
  items.forEach((item) => {
    const showStatus = item.id === latestPending && !item.response;
    let wrap = history.querySelector("[data-chat-id=\"" + item.id + "\"]");
    if (!wrap) {
      history.appendChild(chatTurn(item, showStatus ? statusLines : []));
      return;
    }
    const reply = wrap.querySelector(".bubble-ts");
    const statusEl = wrap.querySelector(".bubble-status");
    if (item.response && !reply) {
      const next = document.createElement("div");
      next.className = "bubble-ts";
      next.textContent = item.response;
      wrap.appendChild(next);
    } else if (item.response && reply && reply.textContent !== item.response) {
      reply.textContent = item.response;
    }
    if (item.response || !showStatus) {
      if (statusEl) statusEl.remove();
    } else {
      const text = statusText(statusLines);
      if (!text) {
        if (statusEl) statusEl.remove();
      } else if (statusEl) {
        if (statusEl.textContent !== text) statusEl.textContent = text;
      } else {
        wrap.appendChild(statusBlock(statusLines));
      }
    }
  });
  if (pinBottom) {
    chatToEnd();
    requestAnimationFrame(chatToEnd);
  }
}

async function sendChat() {
  const text = $("chat-input").value.trim();
  if (!text) return;
  await fetch("/api/chat", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({text})
  });
  $("chat-input").value = "";
  loadState();
}

async function teardownSwarm() {
  if (!confirm("Stop this swarm? Agent sessions and tmux windows will be terminated. Project files stay on disk.")) return;
  const btn = $("teardown-btn");
  if (btn) {
    btn.disabled = true;
    btn.textContent = "Tearing down…";
  }
  try {
    await fetch("/api/teardown", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({confirm: "TEARDOWN"})
    });
    $("pack-meta").textContent = "Swarm teardown started — this page will go offline.";
  } catch (_) {
    if (btn) {
      btn.disabled = false;
      btn.textContent = "Teardown";
    }
  }
}

$("btn-new-task").onclick = () => openNewTask("");
$("nt-types").addEventListener("change", updateNewTaskNote);
$("btn-new-project").onclick = openNewProject;
$("np-cancel").onclick = closeNewProject;
$("np-ok").onclick = submitNewProject;
$("np-name").addEventListener("input", updateInferred);
$("np-github").addEventListener("change", updateInferred);
$("btn-open-project").onclick = (event) => {
  event.stopPropagation();
  const menu = $("open-project-menu");
  menu.classList.toggle("open");
  if (menu.classList.contains("open")) {
    const list = $("open-project-list");
    const box = event.currentTarget.getBoundingClientRect();
    list.style.top = box.bottom + "px";
    list.style.left = box.left + "px";
  }
};
$("nt-cancel").onclick = closeNewTask;
$("nt-ok").onclick = submitNewTask;
$("rt-delete").onclick = deleteRejected;
$("rt-retry").onclick = retryRejected;
$("rt-accept").onclick = acceptRejected;
bindSplitter();
$("teardown-btn").onclick = teardownSwarm;
$("chat-input").addEventListener("keydown", (event) => {
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    sendChat();
  }
});
document.addEventListener("click", (event) => {
  if (!event.target.closest(".menu")) {
    document.querySelectorAll(".menu.open").forEach((el) => {
      el.classList.remove("open");
      if (el.dataset.docId) openDocMenus.delete(el.dataset.docId);
    });
  }
  const btn = event.target.closest("[data-open-agent]");
  if (!btn) return;
  const role = btn.getAttribute("data-open-agent");
  const project = btn.getAttribute("data-open-project");
  if (role) {
    const qs = project ? "?project=" + encodeURIComponent(project) : "";
    openAgentWindow("/agent/" + encodeURIComponent(role) + qs,
                    "agent-" + (project ? project + "-" : "") + role);
  }
});
loadState();
setInterval(loadState, 2000);
