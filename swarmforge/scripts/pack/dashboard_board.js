const $ = (id) => document.getElementById(id);

function displayName(role) {
  if (!role) return "";
  return role.charAt(0).toUpperCase() + role.slice(1);
}

function cardEl(task, opts) {
  const thin = opts && opts.thin;
  const card = document.createElement("article");
  card.className = thin ? "card card-thin" : "card";
  if (task.status === "REJECTED") card.classList.add("card-rejected");
  if (task.merging) card.classList.add("card-merging");
  card.setAttribute("data-task-name", task.name || "");
  if (task.merging) card.setAttribute("data-merging", "true");
  const meta = document.createElement("div");
  meta.className = "card-meta";
  if (task.type) {
    const badge = document.createElement("span");
    badge.className = "pill";
    badge.textContent = task.type;
    meta.appendChild(badge);
  }
  const audit = document.createElement("span");
  audit.className = "audit-count";
  audit.title = "Audit count: " + (task.audit_count || 0);
  const auditIcon = document.createElement("span");
  auditIcon.className = "audit-icon";
  auditIcon.setAttribute("role", "img");
  auditIcon.setAttribute("aria-label", "Audit count");
  auditIcon.title = "Audit count";
  auditIcon.textContent = "\u2713";
  const auditValue = document.createElement("span");
  auditValue.textContent = String(task.audit_count || 0);
  audit.append(auditIcon, auditValue);
  if (task.level && task.level !== "normal") {
    const level = document.createElement("span");
    level.className = "pill pill-level level-" + task.level;
    level.dataset.level = task.level;
    level.textContent = task.level.charAt(0).toUpperCase() + task.level.slice(1);
    level.title = "Level " + task.level + ": its handoffs take this priority";
    meta.appendChild(level);
  }
  const links = cardLinks(task);
  if (links) meta.appendChild(links);
  if (task.return_count) {
    const returns = document.createElement("span");
    returns.className = "return-count";
    returns.title = "Returned for rework " + task.return_count + " time(s)";
    returns.textContent = "\u21a9" + task.return_count;
    meta.appendChild(returns);
  }
  meta.appendChild(audit);
  if (!task.merging && task.lane !== "done") meta.appendChild(cardMenu(task));
  const title = document.createElement("div");
  title.className = "title";
  const name = document.createElement("span");
  name.className = "name";
  name.textContent = task.name;
  title.appendChild(name);
  card.append(meta, title);
  if (task.stuck) {
    card.classList.add("card-stuck");
    card.dataset.stuck = task.stuck;
    card.title = task.status || "";
  }
  if (task.returned_from) card.dataset.returnedFrom = task.returned_from;
  if (task.queued) {
    card.classList.add("card-queued");
    card.setAttribute("data-queued", "true");
    const pill = document.createElement("span");
    pill.className = "pill pill-queued";
    pill.textContent = "Queued" + (task.queue_priority ? " · P" + task.queue_priority : "");
    pill.title = "Waiting in " + (task.queue_role || task.lane || "the") + "'s queue; not started yet";
    card.appendChild(pill);
  }
  if (!thin && !task.queued) {
    const status = document.createElement("div");
    status.className = "status";
    if (task.status_phase === "working" || task.status_phase === "no session") {
      const phase = document.createElement("span");
      phase.className = "status-phase status-phase-" + task.status_phase.replace(/\s+/g, "-");
      phase.textContent = displayName(task.status_phase);
      status.appendChild(phase);
      if (task.status) status.appendChild(document.createTextNode(" · " + task.status));
    } else {
      status.textContent = task.status || "";
    }
    if (task.returned_from) {
      const back = document.createElement("span");
      back.className = "status-returned";
      back.textContent = "\u21a9 Returned by " + displayName(task.returned_from);
      back.title = displayName(task.returned_from) + " sent this card back for rework";
      status.prepend(back, document.createTextNode(task.status ? " \u00b7 " : ""));
    }
    card.appendChild(status);
  }
  card.onclick = () => {
    const qs = "name=" + encodeURIComponent(task.name || "")
      + (task.project ? "&project=" + encodeURIComponent(task.project) : "");
    openGrowable("/task?" + qs, "task-" + (task.name || ""));
  };
  return card;
}

function linkSummary(task) {
  const parts = [];
  if ((task.blockers || []).length) {
    parts.push("Blocked by: " + task.blockers.map((b) => b.name + (b.done ? " (done)" : "")).join(", "));
  }
  if ((task.blocks || []).length) parts.push("Blocks: " + task.blocks.join(", "));
  if ((task.related || []).length) parts.push("Related: " + task.related.join(", "));
  return parts.join("\n");
}

// The card's height is fixed, so links show as one mark in the meta row;
// its title lists them and Links... edits them.
function cardLinks(task) {
  const summary = linkSummary(task);
  if (!summary) return null;
  const mark = document.createElement("span");
  mark.className = "card-links" + (task.blocked ? " blocked" : "");
  mark.dataset.links = summary;
  if ((task.blockers || []).length) mark.dataset.blockers = task.blockers.map((b) => b.name).join(",");
  if ((task.blocks || []).length) mark.dataset.blocks = task.blocks.join(",");
  if ((task.related || []).length) mark.dataset.related = task.related.join(",");
  mark.textContent = task.blocked ? "\u26d4" : "\ud83d\udd17";
  mark.title = summary;
  mark.setAttribute("aria-label", summary);
  return mark;
}

const openCardMenus = new Set();

function cardMenuKey(task) {
  return (task.project || "") + "\n" + (task.name || "");
}

function cardMenuItem(label, action) {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = label;
  btn.onclick = (event) => {
    event.stopPropagation();
    document.querySelectorAll(".card-menu.open").forEach((el) => el.classList.remove("open"));
    openCardMenus.clear();
    action();
  };
  return btn;
}

function cardMenu(task) {
  const key = cardMenuKey(task);
  const wrap = document.createElement("span");
  wrap.className = "menu card-menu";
  wrap.dataset.cardMenu = key;
  const btn = document.createElement("button");
  btn.type = "button";
  btn.className = "card-menu-btn";
  btn.title = "Card actions";
  btn.setAttribute("aria-label", "Card actions");
  btn.textContent = "\u22ef";
  const list = document.createElement("div");
  list.className = "menu-list";
  list.appendChild(cardMenuItem("Rename\u2026", () => renameTask(task)));
  list.appendChild(cardMenuItem("Change level\u2026", () => changeLevel(task)));
  list.appendChild(cardMenuItem("Links\u2026", () => openLinks(task)));
  if (task.queued) {
    list.appendChild(cardMenuItem("Change priority\u2026", () => reprioritizeTask(task)));
    list.appendChild(cardMenuItem("Remove from queue", () => dequeueTask(task)));
  }
  const place = () => {
    const box = btn.getBoundingClientRect();
    list.style.top = box.bottom + "px";
    list.style.left = box.left + "px";
  };
  btn.onclick = (event) => {
    event.stopPropagation();
    wrap.classList.toggle("open");
    if (wrap.classList.contains("open")) {
      openCardMenus.add(key);
      place();
    } else {
      openCardMenus.delete(key);
    }
  };
  wrap.onclick = (event) => event.stopPropagation();
  wrap.append(btn, list);
  if (openCardMenus.has(key)) {
    wrap.classList.add("open");
    requestAnimationFrame(place);
  }
  return wrap;
}

async function postTaskAction(path, payload) {
  const res = await fetch(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(payload)
  });
  if (!res.ok) {
    let msg = "Request failed";
    try { msg = (await res.json()).error || msg; } catch (_) {}
    alert(msg);
  }
  loadState();
  return res.ok;
}

function taskPayload(task, extra) {
  const payload = Object.assign({name: task.name}, extra || {});
  if (task.project) payload.project = task.project;
  return payload;
}

async function renameTask(task) {
  const to = prompt("New name for " + task.name + " (max " + maxTaskNameLength + " characters)", task.name);
  if (to === null) return;
  const name = to.trim();
  if (!name || name === task.name) return;
  if (!taskNameFits(name)) return;
  await postTaskAction("/api/tasks/rename", taskPayload(task, {to: name}));
}

async function reprioritizeTask(task) {
  const value = prompt("Priority for " + task.name + " (00\u201399, lower runs first; 00 = front, 99 = back)",
    task.queue_priority || "50");
  if (value === null || !value.trim()) return;
  await postTaskAction("/api/tasks/priority", taskPayload(task, {priority: value.trim()}));
}

async function changeLevel(task) {
  const value = prompt("Level for " + task.name + ": critical, high, normal or low", task.level || "normal");
  if (value === null || !value.trim()) return;
  await postTaskAction("/api/tasks/priority", taskPayload(task, {level: value.trim().toLowerCase()}));
}

let linksTask = null;

function tasksFor(project) {
  const data = lastState || {};
  if (!project) return data.tasks || [];
  const proj = (data.projects || []).find((p) => p.name === project);
  return (proj && proj.tasks) || [];
}

function fillCardPicker(select, project, except, chosen) {
  select.replaceChildren();
  const seen = new Set();
  tasksFor(project).forEach((t) => {
    if (t.merging || t.name === except || seen.has(t.name)) return;
    seen.add(t.name);
    const opt = document.createElement("option");
    opt.value = t.name;
    opt.textContent = t.name + (t.lane === "done" ? " (done)" : " \u00b7 " + displayName(t.lane));
    opt.selected = (chosen || []).indexOf(t.name) >= 0;
    select.appendChild(opt);
  });
}

function pickedNames(select) {
  return [...select.selectedOptions].map((opt) => opt.value);
}

function openLinks(task) {
  linksTask = task;
  $("ln-title").textContent = "Links of " + task.name;
  fillCardPicker($("ln-blockers"), task.project, task.name, (task.blockers || []).map((b) => b.name));
  fillCardPicker($("ln-related"), task.project, task.name, task.related || []);
  $("links-layer").classList.add("open");
}

const linksInFlight = new Set();

async function submitLinks() {
  if (!linksTask) return;
  const task = linksTask;
  const key = cardMenuKey(task);
  if (linksInFlight.has(key)) return;
  linksInFlight.add(key);
  try {
    const ok = await postTaskAction("/api/tasks/links", taskPayload(task, {
      blocked_by: pickedNames($("ln-blockers")),
      related: pickedNames($("ln-related"))
    }));
    if (ok && linksTask === task) closeLinks();
  } finally {
    linksInFlight.delete(key);
  }
}

function closeLinks() {
  linksTask = null;
  $("links-layer").classList.remove("open");
}

async function dequeueTask(task) {
  if (!confirm("Remove " + task.name + " from the queue? Its queued mail is moved to .swarmforge/removed-tasks and the card leaves the board.")) return;
  await postTaskAction("/api/tasks/dequeue", taskPayload(task));
}

const maxTaskNameLength = 80;

function taskNameFits(name) {
  if (name.length <= maxTaskNameLength) return true;
  alert("Task name must be no longer than " + maxTaskNameLength + " characters (got " + name.length + ").");
  return false;
}

const heatPassMs = [0, 2400, 1900, 1500, 1100, 800, 550];

function setHeat(therm, heat) {
  const level = Math.max(0, Math.min(6, Math.round(Number(heat) || 0)));
  therm.dataset.heat = String(level);
  if (level) {
    const passMs = heatPassMs[level];
    const phaseMs = Date.now() % (passMs * 2);
    therm.style.setProperty("--scan-duration", passMs + "ms");
    therm.style.setProperty("--scan-delay", -phaseMs + "ms");
    therm.title = "pane heat " + level + "; faster scan is hotter";
  } else {
    therm.style.removeProperty("--scan-duration");
    therm.style.removeProperty("--scan-delay");
    therm.title = "pane heat idle";
  }
  therm.setAttribute("aria-label", therm.title);
}

function heatEl(heat) {
  const therm = document.createElement("span");
  therm.className = "wif-therm";
  setHeat(therm, heat);
  for (let i = 0; i < 6; i++) {
    const bar = document.createElement("span");
    bar.className = "bar";
    therm.appendChild(bar);
  }
  return therm;
}

function isActiveCard(task) {
  if (!task) return false;
  if (task.merging) return true;
  if (task.lane === "waiting" || task.lane === "done") return false;
  return task.status !== "waiting in queue";
}

function cardTime(task) {
  return task.updated_at || "";
}

function compareLaneItems(aActive, aTime, bActive, bTime, lane) {
  if (aActive !== bActive) return aActive ? -1 : 1;
  if (lane === "done") {
    if (bTime < aTime) return -1;
    if (bTime > aTime) return 1;
    return 0;
  }
  if (aTime < bTime) return -1;
  if (aTime > bTime) return 1;
  return 0;
}

function orderedLaneGroups(tasks, lane) {
  const mine = tasks.filter((task) => task.lane === lane);
  const groups = [];
  const batches = new Map();
  mine.forEach((task) => {
    if (task.batch) {
      let group = batches.get(task.batch);
      if (!group) {
        group = [];
        batches.set(task.batch, group);
        groups.push(group);
      }
      group.push(task);
    } else {
      groups.push([task]);
    }
  });
  groups.forEach((group) => {
    group.sort((a, b) => compareLaneItems(isActiveCard(a), cardTime(a),
                                          isActiveCard(b), cardTime(b), lane)
      || (a.name || "").localeCompare(b.name || ""));
  });
  groups.sort((a, b) => {
    const aTimes = a.map(cardTime).filter(Boolean).sort();
    const bTimes = b.map(cardTime).filter(Boolean).sort();
    const aStamp = lane === "done" ? (aTimes[aTimes.length - 1] || "") : (aTimes[0] || "");
    const bStamp = lane === "done" ? (bTimes[bTimes.length - 1] || "") : (bTimes[0] || "");
    return compareLaneItems(a.some(isActiveCard), aStamp, b.some(isActiveCard), bStamp, lane);
  });
  return groups;
}

function columnEl(lane, tasks, project, heats) {
  const col = document.createElement("div");
  col.className = "col";
  col.dataset.lane = lane;
  let heading;
  if (lane !== "waiting" && lane !== "done") {
    heading = document.createElement("button");
    heading.type = "button";
    heading.className = "lane-title";
    heading.setAttribute("data-open-agent", lane);
    if (project) heading.setAttribute("data-open-project", project);
    heading.title = "open " + lane + " session";
    heading.appendChild(document.createTextNode(displayName(lane)));
    if (heats && Object.prototype.hasOwnProperty.call(heats, lane)) {
      heading.appendChild(heatEl(heats[lane]));
    }
  } else {
    heading = document.createElement("h3");
    heading.textContent = displayName(lane);
  }
  const body = document.createElement("div");
  body.className = "col-body";
  body.id = "lane-" + (project ? project + "-" : "") + lane;
  const thinLane = lane === "waiting" || lane === "done";
  orderedLaneGroups(tasks, lane).forEach((group) => {
    if (group.length > 1) {
      const wrap = document.createElement("div");
      wrap.className = "batch";
      group.forEach((task, idx) => wrap.appendChild(cardEl(task, {thin: thinLane || idx > 0})));
      body.appendChild(wrap);
    } else {
      body.appendChild(cardEl(group[0], {thin: thinLane}));
    }
  });
  col.append(heading, body);
  return col;
}

function projectBand(proj) {
  const band = document.createElement("div");
  band.className = "project-band";
  band.dataset.project = proj.name || "";
  const header = document.createElement("div");
  header.className = "project-header";
  const title = document.createElement("button");
  title.type = "button";
  title.className = "project-name";
  title.textContent = proj.name || "";
  title.onclick = () => openMission(proj.name);
  const state = document.createElement("span");
  state.className = "project-state project-state-" + (proj.state || "open");
  state.textContent = displayName(proj.state || "open");
  if (proj.error) state.title = proj.error;
  const nt = document.createElement("button");
  nt.type = "button";
  nt.className = "btn btn-primary btn-sm";
  nt.textContent = "New Task";
  nt.onclick = () => openNewTask(proj.name);
  nt.disabled = (proj.state || "open") !== "open";
  const cl = document.createElement("button");
  cl.type = "button";
  cl.className = "btn btn-sm";
  if ((proj.state || "open") === "open") {
    cl.textContent = "Close";
    cl.onclick = () => closeProject(proj.name);
  } else if (proj.state === "starting" || proj.state === "stopping") {
    cl.textContent = displayName(proj.state);
    cl.disabled = true;
  } else {
    cl.textContent = "Open";
    cl.onclick = () => openProject(proj.name);
  }
  const open = (proj.state || "open") === "open";
  if (open) header.append(title, state, pauseButton(proj, proj.name), nt, cl);
  else header.append(title, state, nt, cl);
  const cols = document.createElement("div");
  cols.className = "columns";
  if (proj.drain && proj.drain.paused) cols.classList.add("paused-board");
  const lanes = proj.lanes || [];
  lanes.forEach((lane) => cols.appendChild(columnEl(lane, proj.tasks || [], proj.name, proj.role_heats)));
  band.append(header, ...(open ? swarmBanners(proj, proj.name) : []), cols);
  return band;
}

const swarmInFlight = new Set();

async function postSwarm(path, project, failure) {
  const key = path + "|" + (project || "");
  if (swarmInFlight.has(key)) return;
  swarmInFlight.add(key);
  try {
    const payload = project ? {project} : {};
    const res = await fetch(path, {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify(payload)
    });
    if (!res.ok) {
      let msg = failure;
      try { msg = (await res.json()).error || msg; } catch (_) {}
      alert(msg);
    }
  } catch (_) {
    alert(failure);
  } finally {
    swarmInFlight.delete(key);
  }
  loadState();
}

function pauseSwarm(project) {
  if (!confirm("Pause " + (project || "the swarm") + "? Roles finish their current turn, then no task moves until Resume.")) return;
  postSwarm("/api/pause", project, "Could not pause the swarm");
}

function resumeSwarm(project) {
  postSwarm("/api/resume", project, "Could not resume the swarm");
}

function pauseButton(state, project, btn) {
  const paused = !!(state.drain && state.drain.paused);
  btn = btn || document.createElement("button");
  btn.type = "button";
  btn.className = paused ? "btn btn-primary btn-sm" : "btn btn-sm";
  btn.textContent = paused ? "Resume" : "Pause";
  btn.dataset.pauseToggle = paused ? "resume" : "pause";
  btn.onclick = () => (paused ? resumeSwarm(project) : pauseSwarm(project));
  return btn;
}

function bannerEl(kind, title, detail, action) {
  const box = document.createElement("div");
  box.className = "swarm-banner banner-" + kind;
  box.dataset.banner = kind;
  const head = document.createElement("strong");
  head.textContent = title;
  const text = document.createElement("span");
  text.className = "detail";
  text.textContent = detail;
  box.append(head, text);
  if (action) box.appendChild(action);
  return box;
}

function swarmBanners(state, project) {
  const out = [];
  const live = (state.work_in_flight || []).some((row) => row.state !== "no_session");
  if (state.daemon && !state.daemon.running && live) {
    const restart = document.createElement("button");
    restart.type = "button";
    restart.className = "btn btn-sm btn-danger";
    restart.textContent = "Restart daemon";
    restart.onclick = () => postSwarm("/api/daemon/restart", project, "Could not restart the handoff daemon");
    out.push(bannerEl("daemon", "HANDOFFS STOPPED",
      "The handoff daemon is not running, so no handoff is delivered and no task moves.", restart));
  }
  const drain = state.drain || {};
  if (drain.paused) {
    const busy = (drain.busy || []).map((row) => displayName(row.role));
    const parts = [busy.length ? "Still finishing the current turn: " + busy.join(", ") + "."
                               : "Nothing is in progress."];
    if (drain.held) parts.push(drain.held + " handoff(s) held until Resume.");
    parts.push("No task moves until Resume.");
    const resume = document.createElement("button");
    resume.type = "button";
    resume.className = "btn btn-sm btn-primary";
    resume.textContent = "Resume";
    resume.onclick = () => resumeSwarm(project);
    out.push(bannerEl("paused", "\u23f8 PAUSED", parts.join(" "), resume));
  }
  return out;
}

function renderBanners(data) {
  const box = $("swarm-banners");
  const toggle = $("btn-pause");
  if (data.forge) {
    box.replaceChildren();
    box.dataset.sig = "";
    toggle.style.display = "none";
    return;
  }
  // Rebuild banners only when they change, and update the Pause button in
  // place, so a click or keyboard focus survives the poll.
  const banners = swarmBanners(data, "");
  const sig = banners.map((el) => el.textContent).join("|");
  if (box.dataset.sig !== sig) {
    box.replaceChildren(...banners);
    box.dataset.sig = sig;
  }
  pauseButton(data, "", toggle);
  toggle.style.display = "";
  toggle.className = toggle.className.replace(" btn-sm", "");
}

function renderBoard(data) {
  const board = document.querySelector(".board");
  board.classList.toggle("paused-board", !data.forge && !!(data.drain && data.drain.paused));
  if (data.forge) {
    board.replaceChildren();
    (data.projects || []).forEach((proj) => board.appendChild(projectBand(proj)));
    return;
  }
  let columns = $("columns");
  if (!columns) {
    columns = document.createElement("div");
    columns.id = "columns";
    columns.className = "columns";
    board.replaceChildren(columns);
  }
  columns.replaceChildren();
  const lanes = data.lanes || [];
  const tasks = data.tasks || [];
  lanes.forEach((lane) => columns.appendChild(columnEl(lane, tasks, null, data.role_heats)));
}

let forgePacks = [];
let allProjects = [];
let openProjects = [];
let projectStates = [];
let cardTypesByProject = {};
let standaloneCardTypes = [];
let taskProject = "";
let lastState = null;

function fillOpenMenu() {
  const list = $("open-project-list");
  if (!list) return;
  list.replaceChildren();
  allProjects.forEach((name) => {
    const entry = projectStates.find((item) => item.name === name) || {state: "closed"};
    const btn = document.createElement("button");
    btn.type = "button";
    btn.textContent = name + " (" + (entry.state || "closed") + ")";
    btn.disabled = entry.state === "open" || entry.state === "starting" || entry.state === "stopping";
    btn.onclick = (event) => {
      event.stopPropagation();
      $("open-project-menu").classList.remove("open");
      openProject(name);
    };
    list.appendChild(btn);
  });
  if (!allProjects.length) {
    const empty = document.createElement("div");
    empty.className = "batch-item";
    empty.textContent = "No projects";
    list.appendChild(empty);
  }
}

function renderChrome(data) {
  const master = data.master_display || displayName(data.master_role);
  const masterRole = data.master_role || "";
  const forge = !!data.forge;
  $("pack-title").textContent = forge ? "SwarmForge" : "SwarmForge Pack";
  $("btn-new-task").style.display = forge ? "none" : "";
  $("btn-new-project").style.display = forge ? "" : "none";
  $("open-project-menu").style.display = forge ? "" : "none";
  if (forge) {
    forgePacks = data.packs || [];
    allProjects = data.all_projects || [];
    openProjects = data.open_projects || [];
    projectStates = data.project_states || [];
    cardTypesByProject = {};
    (data.projects || []).forEach((project) => {
      cardTypesByProject[project.name] = project.card_types || [];
    });
    fillOpenMenu();
  } else {
    standaloneCardTypes = data.card_types || [];
  }
  $("pack-meta").replaceChildren();
  const dot = document.createElement("span");
  dot.className = "dot";
  dot.textContent = "●";
  const drain = data.drain && data.drain.paused ? (data.drain.drained ? " · drained (paused)" : " · draining") : "";
  $("pack-meta").append(dot, " live · master = " + master + drain);
  $("master-title").textContent = master;
  if (masterRole) {
    $("master-title").setAttribute("data-open-agent", masterRole);
    $("master-title").title = "open " + masterRole + " session";
  } else {
    $("master-title").removeAttribute("data-open-agent");
    $("master-title").removeAttribute("title");
  }
  const head = $("master-title").parentElement;
  let therm = head && head.querySelector(".wif-therm");
  if (forge) {
    if (!therm) {
      therm = heatEl(data.lieutenant_activity);
      $("master-title").after(therm);
    } else {
      const level = Math.max(0, Math.min(6, Math.round(Number(data.lieutenant_activity) || 0)));
      if (therm.dataset.heat !== String(level)) {
        const replacement = heatEl(level);
        therm.replaceWith(replacement);
      }
    }
  } else if (therm) {
    therm.remove();
  }
}

function openGrowable(url, name) {
  const sep = url.indexOf("?") >= 0 ? "&" : "?";
  window.open(url + sep + "_open=" + Date.now(), name, "resizable=yes,scrollbars=yes,width=780,height=560");
}

function openAgentWindow(url, name) {
  openGrowable(url, name);
}
