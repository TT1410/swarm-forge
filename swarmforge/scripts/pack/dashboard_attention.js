function fileName(path) {
  const parts = (path || "").split("/");
  return parts[parts.length - 1] || path;
}

function artifactLabel(path) {
  const base = fileName(path);
  if (!/(^|\/)qa\//.test(path || "")) return base;
  const i = base.lastIndexOf(".");
  if (i < 0) return base + ".qa";
  return base.slice(0, i) + ".qa" + base.slice(i);
}

async function postApproval(id, action) {
  await fetch("/api/approvals/" + encodeURIComponent(id) + "/" + action, {method: "POST"});
  loadState();
}

function escapeHtml(value) {
  return String(value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function renderDiffLines(lines) {
  return (lines || []).map((line) => {
    const kind = line.type || "same";
    const cls = kind === "add" ? "diff-add" : kind === "del" ? "diff-del" : "diff-same";
    return "<span class=\"" + cls + "\">" + escapeHtml(line.text || "") + "\n</span>";
  }).join("");
}

function renderHistory(history) {
  if (!history || !history.length) {
    return "<div class=\"hist-empty\">No previous comments</div>";
  }
  return history.map((item) => {
    const at = escapeHtml(item.at || "");
    const text = escapeHtml(item.text || "");
    return "<div class=\"hist-sep\">" + at + "</div><div class=\"hist-block\">" + text + "</div>";
  }).join("");
}

function bindVSplit(win, handle, top, bottom) {
  if (!handle || !top || !bottom) return;
  handle.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const startY = event.clientY;
    const topH = top.getBoundingClientRect().height;
    const botH = bottom.getBoundingClientRect().height;
    const move = (e) => {
      const dy = e.clientY - startY;
      top.style.flex = "0 0 " + Math.max(40, topH + dy) + "px";
      bottom.style.flex = "0 0 " + Math.max(40, botH - dy) + "px";
      top.classList.remove("empty");
      bottom.classList.remove("empty");
      top.style.minHeight = "40px";
      bottom.style.minHeight = "40px";
      top.style.maxHeight = "none";
      bottom.style.maxHeight = "none";
    };
    const up = () => {
      win.removeEventListener("pointermove", move);
      win.removeEventListener("pointerup", up);
    };
    win.addEventListener("pointermove", move);
    win.addEventListener("pointerup", up);
  });
}

function fillDocWindow(win, name, data) {
  const hasDiff = !!(data && data.has_diff);
  const text = (data && data.text) || "";
  const history = (data && data.history) || [];
  const emptyHist = !history.length;
  win.document.open();
  win.document.write(
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>" +
    escapeHtml(name) + "</title>" +
    "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}" +
    "header{display:flex;align-items:center;gap:8px;padding:8px 10px;background:linear-gradient(180deg,#eceee8,#e0e3dc);border-bottom:1px solid #d5d9d2;flex:0 0 auto}" +
    "h1{margin:0;font-size:14px;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}" +
    "label.toggle{display:flex;align-items:center;gap:6px;font-size:11px;font-weight:600;color:#68726c;white-space:nowrap}" +
    "pre{flex:1 1 auto;min-height:4rem;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;font:12px/1.4 ui-monospace,Menlo,monospace}" +
    ".diff-add{color:#1a7f37;background:#e6ffed}.diff-del{color:#9b1c1c;background:#ffebe9}.diff-same{color:#1e221f}" +
    ".doc-split{flex:0 0 6px;cursor:row-resize;background:#d5d9d2}" +
    ".doc-split:hover{background:#b8bfb6}" +
    "#doc-history{flex:0 0 28%;min-height:5rem;overflow:auto;padding:8px 12px;background:#f3f4f0;font:12px/1.4 ui-sans-serif,system-ui,sans-serif}" +
    "#doc-history.empty{flex:0 0 2.5rem;min-height:2.5rem;max-height:2.5rem}" +
    ".hist-sep{margin:8px 0 4px;padding-top:6px;border-top:1px solid #c6cbc5;color:#68726c;font-size:10px;text-transform:uppercase;letter-spacing:.04em;font-weight:700}" +
    ".hist-sep:first-child{margin-top:0;padding-top:0;border-top:0}" +
    ".hist-block{white-space:pre-wrap;overflow-wrap:anywhere}" +
    ".hist-empty{color:#68726c;font-style:italic}" +
    ".comments{flex:0 0 auto;display:flex;flex-direction:column;gap:6px;padding:10px 12px;border-top:1px solid #d5d9d2;background:#fffef9}" +
    "label{font-size:10px;text-transform:uppercase;font-weight:700;color:#68726c}" +
    "textarea{width:100%;min-height:7rem;max-height:40vh;overflow:auto;resize:vertical;white-space:pre-wrap;overflow-wrap:anywhere;word-wrap:break-word;border:1px solid #c6cbc5;border-radius:6px;padding:8px;font:12px/1.4 ui-sans-serif,system-ui,sans-serif}" +
    ".actions{display:flex;gap:8px;justify-content:flex-end}" +
    "button{border:1px solid #9aa59e;background:#fff;padding:5px 10px;border-radius:7px;font-size:12px;cursor:pointer}" +
    "#doc-save{background:#3d5a45;border-color:#3d5a45;color:#fff}</style></head><body>" +
    "<header><h1>" + escapeHtml(name) + "</h1>" +
    "<label class=\"toggle\"><input type=\"checkbox\" id=\"doc-diff\"" +
    (hasDiff ? "" : " disabled") + "> Diff</label></header>" +
    "<pre id=\"doc-body\">" + escapeHtml(text) + "</pre>" +
    "<div class=\"doc-split\" id=\"doc-split-body\" title=\"Drag to resize\"></div>" +
    "<div id=\"doc-history\"" + (emptyHist ? " class=\"empty\"" : "") + ">" +
    renderHistory(history) + "</div>" +
    "<div class=\"doc-split\" id=\"doc-split-hist\" title=\"Drag to resize\"></div>" +
    "<div class=\"comments\" id=\"doc-comments-pane\"><label for=\"doc-comments\">New comment</label>" +
    "<textarea id=\"doc-comments\"></textarea>" +
    "<div class=\"actions\">" +
    "<button type=\"button\" id=\"doc-cancel\">Cancel</button>" +
    "<button type=\"button\" id=\"doc-save\">Save</button></div></div>" +
    "</body></html>"
  );
  win.document.close();
  const box = win.document.getElementById("doc-diff");
  const body = win.document.getElementById("doc-body");
  const hist = win.document.getElementById("doc-history");
  const comments = win.document.getElementById("doc-comments-pane");
  bindVSplit(win, win.document.getElementById("doc-split-body"), body, hist);
  bindVSplit(win, win.document.getElementById("doc-split-hist"), hist, comments);
  if (box && body && hasDiff) {
    box.onchange = () => {
      body.innerHTML = box.checked ? renderDiffLines(data.lines) : escapeHtml(text);
    };
  }
}

function hasRemedialComments(item) {
  const reviews = (item && item.reviews) || {};
  return Object.keys(reviews).some((path) => String(reviews[path] || "").trim());
}

function docMark(path, reviews) {
  const mark = document.createElement("span");
  mark.className = "doc-mark";
  if (!reviews || !Object.prototype.hasOwnProperty.call(reviews, path)) {
    mark.textContent = "\u2610";
    mark.title = "Not read";
  } else if (!String(reviews[path] || "").trim()) {
    mark.className += " doc-mark-ok";
    mark.textContent = "\u2713";
    mark.title = "No comments";
  } else {
    mark.className += " doc-mark-bad";
    mark.textContent = "\u2717";
    mark.title = "Has comments";
  }
  return mark;
}

async function viewDoc(path, approvalId, project, alreadyRead) {
  const qs = "/api/doc?path=" + encodeURIComponent(path) +
    (approvalId ? "&id=" + encodeURIComponent(approvalId) : "") +
    (project ? "&project=" + encodeURIComponent(project) : "");
  const res = await fetch(qs);
  const data = res.ok ? await res.json() : {text: "Not found", history: [], has_diff: false, lines: []};
  const name = fileName(path);
  const win = window.open("about:blank", "doc-" + encodeURIComponent(path) + "-" + Date.now(), "resizable=yes,scrollbars=yes,width=780,height=640");
  if (!win) return;
  if (approvalId && !alreadyRead) {
    await fetch("/api/approvals/" + encodeURIComponent(approvalId) + "/comments", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({path, comments: "", project: project || undefined})
    });
    loadState();
  }
  fillDocWindow(win, name, data);
  win.document.getElementById("doc-cancel").onclick = () => win.close();
  win.document.getElementById("doc-save").onclick = async () => {
    if (approvalId) {
      await fetch("/api/approvals/" + encodeURIComponent(approvalId) + "/comments", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({path, comments: win.document.getElementById("doc-comments").value, project: project || undefined})
      });
    }
    win.close();
    loadState();
  };
}

const openDocMenus = new Set();
const clarDrafts = {};

function artifactMenu(paths, item) {
  const id = item && item.id ? item.id : "";
  const reviews = (item && item.reviews) || {};
  const wrap = document.createElement("div");
  wrap.className = "menu";
  wrap.dataset.docId = id || "";
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "btn btn-sm";
  btn.textContent = "Documents";
  const list = document.createElement("div");
  list.className = "menu-list";
  (paths || []).forEach((path) => {
    const choice = document.createElement("button");
    choice.type = "button";
    const name = document.createElement("span");
    name.textContent = artifactLabel(path);
    choice.append(docMark(path, reviews), name);
    choice.onclick = (event) => {
      event.stopPropagation();
      wrap.classList.remove("open");
      if (id) openDocMenus.delete(id);
      viewDoc(path, id, item.project, reviews && Object.prototype.hasOwnProperty.call(reviews, path));
    };
    list.appendChild(choice);
  });
  const place = () => {
    const box = btn.getBoundingClientRect();
    list.style.top = box.bottom + "px";
    list.style.left = box.left + "px";
  };
  btn.onclick = (event) => {
    event.stopPropagation();
    wrap.classList.toggle("open");
    if (wrap.classList.contains("open")) {
      if (id) openDocMenus.add(id);
      place();
    } else if (id) {
      openDocMenus.delete(id);
    }
  };
  wrap.append(btn, list);
  if (id && openDocMenus.has(id)) {
    wrap.classList.add("open");
    requestAnimationFrame(place);
  }
  return wrap;
}

function approvalButton(item, action, label, className) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = className;
  btn.textContent = label;
  btn.onclick = () => postApproval(item.id, action);
  return btn;
}

function attentionWorkPair(project, task) {
  const wrap = document.createElement("span");
  wrap.className = "att-work";
  if (project) {
    const name = document.createElement("span");
    name.className = "att-project";
    name.textContent = project;
    wrap.appendChild(name);
  }
  if (project && task) wrap.appendChild(document.createTextNode("/"));
  if (task) wrap.appendChild(document.createTextNode(task));
  return wrap;
}

function playChime() {
  window.__swarmChime = (window.__swarmChime || 0) + 1;
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = "sine";
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.08, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.18);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.2);
  } catch (_) {}
}

let attentionSeen = new Set();
let attentionPrimed = false;
function chimeNewAttention(data) {
  const keys = [];
  (data.approvals || []).forEach((item) => {
    if (item && item.id) keys.push("a:" + item.id);
  });
  (data.clarifications || []).forEach((item) => {
    if (item && item.id && item.status === "pending") keys.push("c:" + item.id);
  });
  (data.board_allows || []).forEach((item) => {
    if (item && item.id) keys.push("b:" + (item.project || "") + ":" + item.id);
  });
  let fresh = false;
  if (attentionPrimed) {
    keys.forEach((key) => {
      if (!attentionSeen.has(key)) fresh = true;
    });
  }
  attentionSeen = new Set(keys);
  attentionPrimed = true;
  if (fresh) playChime();
}

function attentionRow(item) {
  const row = document.createElement("div");
  row.className = "att-row";
  const pill = document.createElement("span");
  pill.className = "pill pill-warn";
  pill.textContent = "Approval";
  const summary = attentionWorkPair(item.project, item.task);
  row.append(pill, summary);
  if ((item.artifacts || []).length) row.appendChild(artifactMenu(item.artifacts, item));
  const reject = document.createElement("button");
  reject.type = "button";
  reject.className = "btn btn-sm";
  reject.textContent = "Reject";
  reject.onclick = () => openRejectDialog(item);
  const approve = approvalButton(item, "approve", "Approve", "btn btn-sm btn-approve");
  approve.disabled = hasRemedialComments(item);
  row.append(approve, reject);
  return row;
}

function fillClarWindow(win, item) {
  const request = item.body || "";
  const draft = clarDrafts[item.id] || "";
  win.document.open();
  win.document.write(
    "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Clarification</title>" +
    "<style>html,body{height:100%;margin:0;display:flex;flex-direction:column;background:#f8f8f5;color:#1e221f;font-family:ui-sans-serif,system-ui,sans-serif}" +
    "header{flex:0 0 auto;padding:8px 10px;background:linear-gradient(180deg,#eceee8,#e0e3dc);border-bottom:1px solid #d5d9d2;font-weight:600;font-size:13px}" +
    "#clar-request{flex:1 1 50%;min-height:4rem;margin:0;padding:12px;overflow:auto;white-space:pre-wrap;background:#fffef9;font:13px/1.45 ui-sans-serif,system-ui,sans-serif}" +
    ".doc-split{flex:0 0 6px;cursor:row-resize;background:#d5d9d2}" +
    ".doc-split:hover{background:#b8bfb6}" +
    "#clar-response-pane{flex:1 1 50%;min-height:4rem;display:flex;flex-direction:column;padding:10px 12px;background:#fff;gap:6px}" +
    "label{font-size:10px;text-transform:uppercase;font-weight:700;color:#68726c}" +
    "textarea{flex:1;min-height:4rem;width:100%;border:1px solid #c6cbc5;border-radius:6px;padding:8px;font:13px/1.45 ui-sans-serif,system-ui,sans-serif;resize:none}" +
    ".actions{display:flex;justify-content:flex-end}" +
    "button{border:1px solid #9aa59e;background:#3d5a45;border-color:#3d5a45;color:#fff;padding:5px 12px;border-radius:7px;font-size:12px;cursor:pointer}" +
    "</style></head><body>" +
    "<header>Clarification requested from: " + escapeHtml(item.role || "agent") + "</header>" +
    "<pre id=\"clar-request\">" + escapeHtml(request) + "</pre>" +
    "<div class=\"doc-split\" id=\"clar-split\" title=\"Drag to resize\"></div>" +
    "<div id=\"clar-response-pane\"><label for=\"clar-response\">Response</label>" +
    "<textarea id=\"clar-response\">" + escapeHtml(draft) + "</textarea>" +
    "<div class=\"actions\"><button type=\"button\" id=\"clar-ok\">OK</button></div></div>" +
    "</body></html>"
  );
  win.document.close();
  bindVSplit(win, win.document.getElementById("clar-split"),
    win.document.getElementById("clar-request"),
    win.document.getElementById("clar-response-pane"));
}

function openClarification(item) {
  const win = window.open("about:blank", "clar-" + encodeURIComponent(item.id || "") + "-" + Date.now(),
    "resizable=yes,scrollbars=yes,width=720,height=520");
  if (!win) return;
  fillClarWindow(win, item);
  const box = win.document.getElementById("clar-response");
  win.document.getElementById("clar-ok").onclick = async () => {
    const text = box.value;
    clarDrafts[item.id] = text;
    await postClarification(item.id, text);
    win.close();
  };
}

function clarificationRow(item) {
  const row = document.createElement("form");
  row.className = "att-row";
  const pill = document.createElement("span");
  pill.className = "pill pill-warn";
  const who = item.source === "lieutenant" ? "lieutenant" : (item.role || "agent");
  pill.textContent = "Clarification requested from: " + who;
  const pair = attentionWorkPair(item.project, item.task);
  const summary = document.createElement("span");
  summary.textContent = item.body || "";
  const input = document.createElement("input");
  input.type = "text";
  input.placeholder = "Answer…";
  input.dataset.clarId = item.id || "";
  input.value = clarDrafts[item.id] || "";
  input.addEventListener("input", () => {
    clarDrafts[item.id] = input.value;
  });
  const open = document.createElement("button");
  open.type = "button";
  open.className = "btn btn-sm";
  open.textContent = "Open";
  open.onclick = (event) => {
    event.preventDefault();
    openClarification(item);
  };
  const send = document.createElement("button");
  send.type = "submit";
  send.className = "btn btn-sm btn-primary";
  send.textContent = "Submit";
  row.addEventListener("submit", (event) => {
    event.preventDefault();
    postClarification(item.id, input.value);
  });
  row.append(pill, pair, summary, input, open, send);
  return row;
}

async function postClarification(id, text) {
  if (!text || !text.trim()) return;
  await fetch("/api/clarifications/" + encodeURIComponent(id) + "/answer", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({text})
  });
  loadState();
}

function renderApprovals(items) {
  const box = $("attention-approvals");
  box.replaceChildren();
  (items || []).forEach((item) => box.appendChild(attentionRow(item)));
}

function boardAllowRow(item) {
  const row = document.createElement("div");
  row.className = "att-row";
  const pill = document.createElement("span");
  pill.className = "pill pill-warn";
  pill.textContent = "Allow " + (item.act || "move");
  const summary = attentionWorkPair(item.project, item.task);
  const allow = document.createElement("button");
  allow.type = "button";
  allow.className = "btn btn-sm btn-approve";
  allow.textContent = "Allow";
  allow.onclick = async () => {
    await fetch("/api/board/allow", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({name: item.task, act: item.act, project: item.project})
    });
    loadState();
  };
  row.append(pill, summary, allow);
  return row;
}

function renderBoardAllows(items) {
  const box = $("attention-allows");
  if (!box) return;
  box.replaceChildren();
  (items || []).forEach((item) => box.appendChild(boardAllowRow(item)));
}

function renderClarifications(items) {
  const box = $("attention-clarifications");
  const pending = (items || []).filter((item) => item.status === "pending");
  const live = {};
  pending.forEach((item) => {
    live[item.id] = true;
  });
  [...box.querySelectorAll("[data-clar-id]")].forEach((input) => {
    if (!live[input.dataset.clarId]) input.closest(".att-row").remove();
  });
  pending.forEach((item) => {
    if (!box.querySelector("[data-clar-id=\"" + item.id + "\"]")) {
      box.appendChild(clarificationRow(item));
    }
  });
}

function renderAttention(data) {
  chimeNewAttention(data);
  renderApprovals(data.approvals);
  renderBoardAllows(data.board_allows);
  renderClarifications(data.clarifications);
}

