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
  meta.appendChild(audit);
  const title = document.createElement("div");
  title.className = "title";
  const name = document.createElement("span");
  name.className = "name";
  name.textContent = task.name;
  title.appendChild(name);
  card.append(meta, title);
  if (!thin) {
    const status = document.createElement("div");
    status.className = "status";
    status.textContent = task.status || "";
    card.appendChild(status);
  }
  card.onclick = () => {
    const qs = "name=" + encodeURIComponent(task.name || "")
      + (task.project ? "&project=" + encodeURIComponent(task.project) : "");
    openGrowable("/task?" + qs, "task-" + (task.name || ""));
  };
  return card;
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
  header.append(title, state, nt, cl);
  const cols = document.createElement("div");
  cols.className = "columns";
  const lanes = proj.lanes || [];
  lanes.forEach((lane) => cols.appendChild(columnEl(lane, proj.tasks || [], proj.name, proj.role_heats)));
  band.append(header, cols);
  return band;
}

function renderBoard(data) {
  const board = document.querySelector(".board");
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
  $("pack-meta").append(dot, " live · master = " + master);
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
