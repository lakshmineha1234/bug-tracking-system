"use strict";

/* ---------- Constants ---------- */
const STATUSES = ["OPEN", "IN_PROGRESS", "FIXED", "CLOSED"];
const PRIORITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"];
const SEVERITIES = ["CRITICAL", "MAJOR", "MINOR"];
const COLORS = {
  OPEN: "#2563eb", IN_PROGRESS: "#7c3aed", FIXED: "#16a34a", CLOSED: "#6b7280",
  CRITICAL: "#dc2626", HIGH: "#ea580c", MAJOR: "#ea580c", MEDIUM: "#ca8a04", LOW: "#16a34a", MINOR: "#16a34a"
};

/* ---------- State ---------- */
let csrf = null;          // {headerName, token}
let me = null;            // current user
let view = "dashboard";
let projects = [];        // projects visible to the current user
let allUsers = [];        // admin only
let boardBugs = [];
let listBugs = [];
let renderSeq = 0;
let filters = { q: "", status: "", priority: "", severity: "", projectId: "", assigneeId: "", sort: "id", dir: -1 };

/* ---------- Helpers ---------- */
const $ = id => document.getElementById(id);
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const label = v => v ? String(v).replace(/_/g, " ").toLowerCase().replace(/\b\w/g, c => c.toUpperCase()) : "";
const pill = v => `<span class="pill p-${esc(v)}">${esc(label(v))}</span>`;
const fmt = iso => iso ? new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }) : "";
const ago = iso => {
  const s = (Date.now() - new Date(iso)) / 1e3;
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  return Math.floor(s / 86400) + "d ago";
};
const initials = n => n ? n.split(/\s+/).map(x => x[0]).join("").slice(0, 2).toUpperCase() : "";
const opts = (arr, sel, blank, lab = label) =>
  (blank != null ? `<option value="">${esc(blank)}</option>` : "") +
  arr.map(v => `<option value="${esc(v)}" ${String(v) === String(sel) ? "selected" : ""}>${esc(lab(v))}</option>`).join("");
const isAdmin = () => me && me.role === "ADMIN";
const canReport = () => me && (me.role === "ADMIN" || me.role === "TESTER");

function toast(msg) {
  const t = $("toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(t._h);
  t._h = setTimeout(() => t.classList.remove("show"), 2500);
}

function transitionLabel(from, to) {
  if (to === "IN_PROGRESS") return from === "FIXED" ? "Fix failed, return to developer" : "Start work";
  if (to === "FIXED") return "Mark fixed (ready for testing)";
  if (to === "CLOSED") return "Verify & close";
  if (to === "OPEN") return "Reopen";
  return label(to);
}

/* ---------- API ---------- */
async function loadCsrf() {
  const r = await fetch("/api/auth/csrf");
  csrf = await r.json();
}

async function api(method, url, body) {
  const opt = { method, headers: { Accept: "application/json" } };
  if (body !== undefined) {
    opt.headers["Content-Type"] = "application/json";
    opt.body = JSON.stringify(body);
  }
  if (method !== "GET" && csrf) opt.headers[csrf.headerName] = csrf.token;
  const r = await fetch(url, opt);
  if (r.status === 401 && me) {
    me = null;
    await loadCsrf();
    showAuth("login");
    throw new Error("Your session has ended. Please log in again.");
  }
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* non-JSON error page */ }
  if (!r.ok) {
    let msg = (data && data.message) || r.statusText || "Request failed";
    if (data && data.fieldErrors) {
      msg += ": " + Object.entries(data.fieldErrors).map(([k, v]) => `${k} ${v}`).join(", ");
    }
    throw new Error(msg);
  }
  return data;
}

/* ---------- Auth screens ---------- */
function showAuth(mode) {
  document.body.classList.add("signed-out");
  document.body.classList.remove("is-admin");
  if ($("dlg").open) $("dlg").close();
  const register = mode === "register";
  $("app").innerHTML = `
  <div class="card auth">
    <h2>${register ? "Create an account" : "Sign in to BugTrack"}</h2>
    <form id="authForm" class="form">
      ${register ? `<div class="field"><label>Name</label><input name="name" required maxlength="100" autocomplete="name"></div>` : ""}
      <div class="field"><label>Email</label><input name="email" type="email" required maxlength="150" autocomplete="email"></div>
      <div class="field"><label>Password${register ? " (at least 8 characters)" : ""}</label>
        <input name="password" type="password" required ${register ? 'minlength="8" maxlength="72"' : ""} autocomplete="${register ? "new-password" : "current-password"}"></div>
      ${register ? `<div class="field"><label>Role</label><select name="role">${opts(["TESTER", "DEVELOPER"], "TESTER")}</select></div>` : ""}
      <div class="error" id="authError"></div>
      <button class="btn primary">${register ? "Create account" : "Sign in"}</button>
    </form>
    <div class="switch muted">${register
      ? `Already have an account? <button class="linklike" id="authSwitch">Sign in</button>`
      : `New here? <button class="linklike" id="authSwitch">Create an account</button>`}</div>
  </div>`;
  $("authSwitch").onclick = () => showAuth(register ? "login" : "register");
  $("authForm").onsubmit = async e => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    $("authError").textContent = "";
    try {
      if (register) {
        await api("POST", "/api/auth/register", f);
        toast("Account created. Please sign in.");
        showAuth("login");
      } else {
        me = await api("POST", "/api/auth/login", { email: f.email, password: f.password });
        await loadCsrf();
        await enterApp();
      }
    } catch (err) {
      $("authError").textContent = err.message;
    }
  };
}

async function enterApp() {
  document.body.classList.remove("signed-out");
  document.body.classList.toggle("is-admin", isAdmin());
  $("userBadge").innerHTML = `${esc(me.name)} ${pill(me.role)}`;
  $("newBugBtn").style.display = canReport() ? "" : "none";
  await refreshReferenceData();
  view = "dashboard";
  render();
  openBugFromLink();
}

/* Links such as /#bug-8 (used in notification emails) open that bug, after signing in if needed. */
function openBugFromLink() {
  const m = location.hash.match(/^#bug-(\d+)$/);
  if (m && me) openBug(Number(m[1]));
}
window.addEventListener("hashchange", openBugFromLink);

async function refreshReferenceData() {
  projects = await api("GET", "/api/projects");
  allUsers = isAdmin() ? await api("GET", "/api/users") : [];
}

/* ---------- Rendering ---------- */
const VIEWS = { dashboard: dashboardView, board: boardView, list: listView, projects: projectsView, users: usersView };
const BINDERS = { board: bindBoard, list: bindList, projects: bindProjects, users: bindUsers };

async function render() {
  if (!me) return;
  if (view === "users" && !isAdmin()) view = "dashboard";
  document.querySelectorAll("#nav button").forEach(b => b.classList.toggle("active", b.dataset.view === view));
  const seq = ++renderSeq;
  const app = $("app");
  let html;
  try {
    html = await VIEWS[view]();
  } catch (e) {
    html = `<div class="empty">${esc(e.message)}</div>`;
  }
  if (seq !== renderSeq || !me) return; // a newer render started meanwhile
  app.innerHTML = html;
  if (BINDERS[view]) BINDERS[view]();
  app.querySelectorAll("[data-open]").forEach(el => el.addEventListener("click", () => openBug(+el.dataset.open)));
}

function bars(map, useLabel) {
  const entries = Object.entries(map || {});
  if (!entries.length) return `<div class="muted">No data yet</div>`;
  const max = Math.max(1, ...entries.map(([, n]) => n));
  return entries.map(([k, n]) => `<div class="bar-row"><span title="${esc(k)}">${esc(useLabel ? label(k) : k)}</span>
    <div class="bar"><i style="width:${n / max * 100}%;background:${COLORS[k] || "var(--accent)"}"></i></div><b>${n}</b></div>`).join("");
}

/* Dashboard */
async function dashboardView() {
  const s = await api("GET", "/api/dashboard/summary");
  const stats = [
    ["Total bugs", s.total], ["Open", s.byStatus.OPEN], ["In progress", s.byStatus.IN_PROGRESS],
    ["Fixed, awaiting test", s.byStatus.FIXED], ["Closed", s.byStatus.CLOSED], ["Critical priority (not closed)", s.criticalActive]
  ];
  const events = timeline(s.recentActivity, s.recentAssignments, true).slice(0, 10);
  const activity = events.length ? events.map(e => `
    <div class="list-item" data-open="${e.bugId}" style="cursor:pointer">
      <span><b>${esc(e.by)}</b> ${e.html}</span>
      <span class="muted">${ago(e.at)}</span></div>`).join("") : `<div class="muted">No activity yet</div>`;
  return `<h2>Dashboard</h2>
  <div class="grid stats">${stats.map(([l, n]) => `<div class="card stat"><div class="n">${n}</div><div class="l">${l}</div></div>`).join("")}</div>
  <div class="grid two" style="margin-top:16px">
    <div class="card"><h3>By status</h3>${bars(s.byStatus, true)}</div>
    <div class="card"><h3>By priority</h3>${bars(s.byPriority, true)}</div>
    <div class="card"><h3>By severity</h3>${bars(s.bySeverity, true)}</div>
    <div class="card"><h3>By project</h3>${bars(s.byProject, false)}</div>
    <div class="card"><h3>By assignee</h3>${bars(s.byAssignee, false)}</div>
    <div class="card"><h3>Recent activity</h3>${activity}</div>
  </div>`;
}

/* Board */
async function boardView() {
  boardBugs = await api("GET", "/api/bugs");
  return `<h2>Board</h2><p class="muted" style="margin-top:-8px">Drag a card to another column to change its status. Only moves your role allows will be accepted.</p>
  <div class="board">${STATUSES.map(s => {
    const items = boardBugs.filter(b => b.status === s);
    return `<div class="col" data-status="${s}"><h3><span>${label(s)}</span><span>${items.length}</span></h3>
      ${items.map(b => `<div class="ticket" draggable="${b.allowedTransitions.length > 0}" data-id="${b.id}" data-open="${b.id}">
        <div class="id">#${b.id} · ${esc(b.project.projectName)}</div><div class="t">${esc(b.title)}</div>
        <div class="meta">${pill(b.priority)}<span class="muted">${esc(label(b.severity))}</span>
        ${b.assignedTo ? `<span class="avatar" title="${esc(b.assignedTo.name)}">${esc(initials(b.assignedTo.name))}</span>` : ""}</div></div>`).join("")}
    </div>`;
  }).join("")}</div>`;
}

function bindBoard() {
  document.querySelectorAll(".ticket[draggable=true]").forEach(t =>
    t.addEventListener("dragstart", e => e.dataTransfer.setData("text/plain", t.dataset.id)));
  document.querySelectorAll(".col").forEach(c => {
    c.addEventListener("dragover", e => { e.preventDefault(); c.classList.add("over"); });
    c.addEventListener("dragleave", () => c.classList.remove("over"));
    c.addEventListener("drop", async e => {
      e.preventDefault();
      c.classList.remove("over");
      const bug = boardBugs.find(b => b.id == e.dataTransfer.getData("text/plain"));
      const target = c.dataset.status;
      if (!bug || bug.status === target) return;
      if (!bug.allowedTransitions.includes(target)) {
        toast(`You can't move #${bug.id} from ${label(bug.status)} to ${label(target)}`);
        return;
      }
      try {
        await api("PATCH", `/api/bugs/${bug.id}/status`, { status: target });
        toast(`#${bug.id} moved to ${label(target)}`);
        render();
      } catch (err) { toast(err.message); }
    });
  });
}

/* All bugs */
const PRIORITY_RANK = Object.fromEntries(PRIORITIES.map((p, i) => [p, i]));
const SEVERITY_RANK = Object.fromEntries(SEVERITIES.map((p, i) => [p, i]));
const STATUS_RANK = Object.fromEntries(STATUSES.map((p, i) => [p, i]));
const SORT_KEYS = {
  id: b => b.id, title: b => b.title.toLowerCase(), project: b => b.project.projectName.toLowerCase(),
  status: b => STATUS_RANK[b.status], priority: b => PRIORITY_RANK[b.priority], severity: b => SEVERITY_RANK[b.severity],
  assignee: b => (b.assignedTo ? b.assignedTo.name : "~").toLowerCase(), updatedAt: b => b.updatedAt
};

async function listView() {
  const params = new URLSearchParams();
  for (const k of ["q", "status", "priority", "severity", "projectId"]) if (filters[k]) params.set(k, filters[k]);
  if (filters.assigneeId === "none") params.set("unassigned", "true");
  else if (filters.assigneeId) params.set("assigneeId", filters.assigneeId);
  listBugs = await api("GET", "/api/bugs?" + params);
  const key = SORT_KEYS[filters.sort];
  const rows = [...listBugs].sort((a, b) => (key(a) > key(b) ? 1 : key(a) < key(b) ? -1 : 0) * filters.dir);
  const cols = [["id", "ID"], ["title", "Title"], ["project", "Project"], ["status", "Status"], ["priority", "Priority"],
    ["severity", "Severity"], ["assignee", "Assignee"], ["updatedAt", "Updated"]];
  const developers = allUsers.filter(u => u.role === "DEVELOPER");
  return `<h2>All Bugs</h2>
  <div class="toolbar">
    <input type="search" id="fq" placeholder="Search by #ID, title or description…" value="${esc(filters.q)}">
    <select id="fstatus">${opts(STATUSES, filters.status, "All statuses")}</select>
    <select id="fpriority">${opts(PRIORITIES, filters.priority, "All priorities")}</select>
    <select id="fseverity">${opts(SEVERITIES, filters.severity, "All severities")}</select>
    <select id="fprojectId">${opts(projects.map(p => p.id), filters.projectId, "All projects", id => projects.find(p => p.id == id).projectName)}</select>
    ${isAdmin() ? `<select id="fassigneeId"><option value="">All assignees</option>
      <option value="none" ${filters.assigneeId === "none" ? "selected" : ""}>Unassigned</option>
      ${opts(developers.map(u => u.id), filters.assigneeId, null, id => developers.find(u => u.id == id).name)}</select>` : ""}
    <button class="btn" id="fclear">Clear</button>
    <button class="btn" id="fcsv">Export CSV</button>
  </div>
  <div class="card table-wrap" style="padding:0">${rows.length ? `<table>
    <thead><tr>${cols.map(([k, l]) => `<th data-sort="${k}">${l}${filters.sort === k ? (filters.dir > 0 ? " ▲" : " ▼") : ""}</th>`).join("")}</tr></thead>
    <tbody>${rows.map(b => `<tr data-open="${b.id}"><td>#${b.id}</td><td class="title">${esc(b.title)}</td>
      <td>${esc(b.project.projectName)}</td><td>${pill(b.status)}</td><td>${pill(b.priority)}</td><td>${pill(b.severity)}</td>
      <td>${b.assignedTo ? esc(b.assignedTo.name) : '<span class="muted">Unassigned</span>'}</td>
      <td class="muted">${ago(b.updatedAt)}</td></tr>`).join("")}</tbody></table>`
    : `<div class="empty">No bugs match these filters.</div>`}</div>
  <p class="muted">${rows.length} bug${rows.length === 1 ? "" : "s"}</p>`;
}

let searchTimer;
function bindList() {
  const q = $("fq");
  q.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(async () => {
      filters.q = q.value;
      const pos = q.selectionStart;
      await render();
      const n = $("fq");
      if (n) { n.focus(); n.setSelectionRange(pos, pos); }
    }, 300);
  });
  ["status", "priority", "severity", "projectId", "assigneeId"].forEach(k => {
    const el = $("f" + k);
    if (el) el.addEventListener("change", e => { filters[k] = e.target.value; render(); });
  });
  $("fclear").onclick = () => {
    Object.assign(filters, { q: "", status: "", priority: "", severity: "", projectId: "", assigneeId: "" });
    render();
  };
  $("fcsv").onclick = exportCSV;
  document.querySelectorAll("th[data-sort]").forEach(th => th.onclick = () => {
    const k = th.dataset.sort;
    filters.dir = filters.sort === k ? -filters.dir : 1;
    filters.sort = k;
    render();
  });
}

function exportCSV() {
  const cols = [["id", b => b.id], ["title", b => b.title], ["project", b => b.project.projectName], ["status", b => b.status],
    ["priority", b => b.priority], ["severity", b => b.severity], ["assignee", b => b.assignedTo ? b.assignedTo.name : ""],
    ["reporter", b => b.reportedBy.name], ["created", b => b.createdAt], ["updated", b => b.updatedAt]];
  const q = v => `"${String(v ?? "").replace(/"/g, '""')}"`;
  const csv = [cols.map(c => c[0]).join(","), ...listBugs.map(b => cols.map(c => q(c[1](b))).join(","))].join("\n");
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
  a.download = "bugs.csv";
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

/* Projects */
async function projectsView() {
  projects = await api("GET", "/api/projects");
  return `<h2>Projects</h2>
  ${isAdmin() ? `<div class="card" style="margin-bottom:16px"><h3>New project</h3>
    <form id="newProject" class="toolbar" style="margin:0">
      <input name="projectName" placeholder="Project name" required maxlength="100" style="flex:1;min-width:180px">
      <input name="description" placeholder="Description (optional)" maxlength="1000" style="flex:2;min-width:200px">
      <button class="btn primary">Create</button></form></div>` : ""}
  ${projects.length ? `<div class="grid two">${projects.map(p => `<div class="card">
    <h3>${esc(p.projectName)}</h3>
    <p class="muted" style="margin-top:-6px">${esc(p.description) || "No description"}</p>
    <p class="muted">${p.memberCount} member${p.memberCount === 1 ? "" : "s"} · created by ${esc(p.createdBy.name)}</p>
    <div class="toolbar" style="margin:0">
      <button class="btn sm" data-bugs="${p.id}">View bugs</button>
      <button class="btn sm" data-members="${p.id}">Members</button>
      ${isAdmin() ? `<button class="btn sm" data-edit="${p.id}">Edit</button>` : ""}
    </div></div>`).join("")}</div>`
    : `<div class="empty">${isAdmin() ? "No projects yet. Create one above." : "You are not a member of any project yet. Ask an admin to add you."}</div>`}`;
}

function bindProjects() {
  const form = $("newProject");
  if (form) form.onsubmit = async e => {
    e.preventDefault();
    try {
      await api("POST", "/api/projects", Object.fromEntries(new FormData(form)));
      toast("Project created");
      render();
    } catch (err) { toast(err.message); }
  };
  document.querySelectorAll("[data-bugs]").forEach(b => b.onclick = () => {
    Object.assign(filters, { q: "", status: "", priority: "", severity: "", assigneeId: "", projectId: b.dataset.bugs });
    view = "list";
    render();
  });
  document.querySelectorAll("[data-members]").forEach(b => b.onclick = () => openMembers(+b.dataset.members));
  document.querySelectorAll("[data-edit]").forEach(b => b.onclick = () => openProjectEdit(+b.dataset.edit));
}

function openDialog(html) {
  $("dlgContent").innerHTML = html;
  $("dlgContent").querySelectorAll("[data-close]").forEach(b => b.onclick = () => $("dlg").close());
  if (!$("dlg").open) $("dlg").showModal();
}

function openProjectEdit(id) {
  const p = projects.find(x => x.id === id);
  openDialog(`<form id="editProject">
    <div class="dlg-head"><h3 style="margin:0">Edit project</h3><button type="button" class="btn sm" data-close>✕</button></div>
    <div class="dlg-body"><div class="form">
      <div class="field full"><label>Project name</label><input name="projectName" required maxlength="100" value="${esc(p.projectName)}"></div>
      <div class="field full"><label>Description</label><textarea name="description" maxlength="1000">${esc(p.description)}</textarea></div>
    </div></div>
    <div class="dlg-foot"><button type="button" class="btn" data-close>Cancel</button><button class="btn primary">Save</button></div></form>`);
  $("editProject").onsubmit = async e => {
    e.preventDefault();
    try {
      await api("PUT", `/api/projects/${id}`, Object.fromEntries(new FormData(e.target)));
      $("dlg").close();
      toast("Project saved");
      render();
    } catch (err) { toast(err.message); }
  };
}

async function openMembers(id) {
  const p = projects.find(x => x.id === id);
  let members;
  try { members = await api("GET", `/api/projects/${id}/members`); } catch (err) { toast(err.message); return; }
  const memberIds = new Set(members.map(m => m.id));
  const candidates = allUsers.filter(u => !memberIds.has(u.id) && u.enabled);
  openDialog(`<div class="dlg-head"><h3 style="margin:0">Members of ${esc(p.projectName)}</h3><button class="btn sm" data-close>✕</button></div>
    <div class="dlg-body">
      ${members.map(m => `<div class="list-item"><span>${esc(m.name)} <span class="muted">${esc(m.email)}</span> ${pill(m.role)}</span>
        ${isAdmin() ? `<button class="btn sm danger" data-remove="${m.id}">Remove</button>` : ""}</div>`).join("") || `<p class="muted">No members yet.</p>`}
      ${isAdmin() ? `<div class="toolbar" style="margin-top:14px">
        <select id="addMember" style="flex:1">${candidates.length
          ? opts(candidates.map(u => u.id), "", null, uid => { const u = candidates.find(x => x.id == uid); return `${u.name} (${label(u.role)})`; })
          : `<option value="">Everyone is already a member</option>`}</select>
        <button class="btn primary" id="addMemberBtn" ${candidates.length ? "" : "disabled"}>Add member</button></div>` : ""}
    </div>`);
  const changeMembers = async (method, userId) => {
    try {
      await api(method, `/api/projects/${id}/members/${userId}`);
      await refreshReferenceData();
      openMembers(id);
      if (view === "projects") render();
    } catch (err) { toast(err.message); }
  };
  document.querySelectorAll("[data-remove]").forEach(b => b.onclick = () => changeMembers("DELETE", b.dataset.remove));
  const add = $("addMemberBtn");
  if (add) add.onclick = () => changeMembers("POST", $("addMember").value);
}

/* Users (admin) */
async function usersView() {
  allUsers = await api("GET", "/api/users");
  return `<h2>Users</h2>
  <p class="muted" style="margin-top:-8px">New users register as Tester or Developer. Change roles or disable accounts here.</p>
  <div class="card table-wrap" style="padding:0"><table>
    <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Active</th><th>Joined</th></tr></thead>
    <tbody>${allUsers.map(u => {
      const self = u.id === me.id;
      return `<tr><td>${esc(u.name)}${self ? ' <span class="muted">(you)</span>' : ""}</td><td>${esc(u.email)}</td>
      <td><select data-role="${u.id}" ${self ? "disabled" : ""}>${opts(["ADMIN", "TESTER", "DEVELOPER"], u.role)}</select></td>
      <td><input type="checkbox" data-enabled="${u.id}" ${u.enabled ? "checked" : ""} ${self ? "disabled" : ""}></td>
      <td class="muted">${fmt(u.createdAt)}</td></tr>`;
    }).join("")}</tbody></table></div>`;
}

function bindUsers() {
  const update = async (id, body) => {
    try {
      await api("PATCH", `/api/users/${id}`, body);
      toast("User updated");
    } catch (err) { toast(err.message); }
    render();
  };
  document.querySelectorAll("[data-role]").forEach(s => s.onchange = () => update(s.dataset.role, { role: s.value }));
  document.querySelectorAll("[data-enabled]").forEach(c => c.onchange = () => update(c.dataset.enabled, { enabled: c.checked }));
}

/* ---------- Activity timeline ---------- */
// Merges status changes and assignment changes, newest first. With withBug, entries name the bug (#N).
function timeline(statusChanges, assignmentChanges, withBug) {
  const ref = e => withBug ? ` <b>#${e.bugId}</b>` : "";
  const status = (statusChanges || []).map(h => ({
    at: h.changedAt, by: h.changedBy.name, bugId: h.bugId,
    html: h.oldStatus
      ? `${withBug ? "moved" + ref(h) : "changed status"} ${esc(label(h.oldStatus))} → ${esc(label(h.newStatus))}`
      : `reported${withBug ? ref(h) + " " + esc(h.bugTitle) : " the bug"}`
  }));
  const assignments = (assignmentChanges || []).map(a => {
    const target = withBug ? ref(a) : " the bug";
    let html;
    if (!a.newAssignee) html = `unassigned${target} from <b>${esc(a.oldAssignee.name)}</b>`;
    else if (!a.oldAssignee) html = `assigned${target} to <b>${esc(a.newAssignee.name)}</b>`;
    else html = `reassigned${target} from <b>${esc(a.oldAssignee.name)}</b> to <b>${esc(a.newAssignee.name)}</b>`;
    return { at: a.changedAt, by: a.changedBy.name, bugId: a.bugId, html };
  });
  return [...status, ...assignments].sort((x, y) => new Date(y.at) - new Date(x.at));
}

/* ---------- Bug dialog ---------- */
async function openBug(id) {
  let bug = null, comments = [], history = [], assignments = [], developers = [];
  if (id) {
    try {
      [bug, comments, history, assignments] = await Promise.all([
        api("GET", `/api/bugs/${id}`), api("GET", `/api/bugs/${id}/comments`), api("GET", `/api/bugs/${id}/history`),
        api("GET", `/api/bugs/${id}/assignments`)]);
      if (bug.canAssign) {
        developers = (await api("GET", `/api/projects/${bug.project.id}/members`)).filter(u => u.role === "DEVELOPER" && u.enabled);
      }
    } catch (err) { toast(err.message); return; }
  } else if (!projects.length) {
    toast("You need to be a member of a project before reporting bugs.");
    return;
  }

  const b = bug || { title: "", description: "", stepsToReproduce: "", expectedResult: "", actualResult: "", priority: "MEDIUM", severity: "MAJOR" };
  const editable = !bug || bug.canEdit;
  const dis = editable ? "" : "disabled";

  const workflow = bug ? `<div class="section">
    <h3>Status &amp; assignment</h3>
    <div class="workflow">${pill(bug.status)}
      <span class="muted">Reported by <b>${esc(bug.reportedBy.name)}</b> · ${bug.assignedTo ? `assigned to <b>${esc(bug.assignedTo.name)}</b>` : "unassigned"}</span></div>
    ${bug.allowedTransitions.length ? `<div class="workflow" style="margin-top:10px">${bug.allowedTransitions.map(t =>
      `<button type="button" class="btn sm" data-status="${t}">${esc(transitionLabel(bug.status, t))}</button>`).join("")}</div>` : ""}
    ${bug.canAssign ? `<div class="workflow" style="margin-top:10px">
      <select id="assignee">${opts(developers.map(u => u.id), bug.assignedTo ? bug.assignedTo.id : "", "Unassigned",
        uid => developers.find(u => u.id == uid).name)}</select>
      <button type="button" class="btn sm" id="assignBtn">Save assignment</button>
      ${developers.length ? "" : `<span class="muted">No developers in this project yet. Add one under Projects → Members.</span>`}</div>` : ""}
    <p class="muted" style="margin-bottom:0">Created ${fmt(bug.createdAt)} · updated ${fmt(bug.updatedAt)}</p></div>
  <div class="section"><h3>Comments (${comments.length})</h3>
    ${comments.map(c => `<div class="comment"><div class="who">${esc(c.author.name)} · ${fmt(c.createdAt)}</div>${esc(c.commentText)}</div>`).join("") || `<p class="muted">No comments yet.</p>`}
    <div class="field" style="margin-top:8px"><textarea id="newComment" maxlength="2000" placeholder="Add a comment or progress note…" style="min-height:60px"></textarea></div>
    <button type="button" class="btn sm" id="addComment" style="margin-top:6px">Post comment</button></div>
  <div class="section history"><h3>History</h3>${timeline(history, assignments, false).map(e =>
      `<div>${fmt(e.at)}: <b>${esc(e.by)}</b> ${e.html}</div>`).join("")}</div>` : "";

  openDialog(`<form id="bugForm">
    <div class="dlg-head"><h3 style="margin:0">${bug ? `Bug #${bug.id} · ${esc(bug.project.projectName)}` : "Report a bug"}</h3>
      <button type="button" class="btn sm" data-close>✕</button></div>
    <div class="dlg-body">
      <div class="form">
        <div class="field full"><label>Title *</label><input name="title" required maxlength="200" value="${esc(b.title)}" ${dis}></div>
        ${bug ? "" : `<div class="field"><label>Project *</label><select name="projectId" required>${opts(projects.map(p => p.id), filters.projectId || projects[0].id, null,
          pid => projects.find(p => p.id == pid).projectName)}</select></div>`}
        <div class="field"><label>Priority *</label><select name="priority" ${dis}>${opts(PRIORITIES, b.priority)}</select></div>
        <div class="field"><label>Severity *</label><select name="severity" ${dis}>${opts(SEVERITIES, b.severity)}</select></div>
        <div class="field full"><label>Description</label><textarea name="description" maxlength="4000" ${dis}>${esc(b.description)}</textarea></div>
        <div class="field full"><label>Steps to reproduce</label><textarea name="stepsToReproduce" maxlength="4000" placeholder="1. …&#10;2. …" ${dis}>${esc(b.stepsToReproduce)}</textarea></div>
        <div class="field"><label>Expected result</label><textarea name="expectedResult" maxlength="2000" ${dis}>${esc(b.expectedResult)}</textarea></div>
        <div class="field"><label>Actual result</label><textarea name="actualResult" maxlength="2000" ${dis}>${esc(b.actualResult)}</textarea></div>
      </div>
      ${workflow}
    </div>
    <div class="dlg-foot">
      <button type="button" class="btn" data-close>${editable ? "Cancel" : "Close"}</button>
      ${editable ? `<button class="btn primary">${bug ? "Save changes" : "Submit bug"}</button>` : ""}
    </div></form>`);

  $("bugForm").onsubmit = async e => {
    e.preventDefault();
    const f = Object.fromEntries(new FormData(e.target));
    try {
      if (bug) {
        await api("PUT", `/api/bugs/${bug.id}`, f);
        toast(`Bug #${bug.id} saved`);
      } else {
        const created = await api("POST", "/api/bugs", { ...f, projectId: Number(f.projectId) });
        toast(`Bug #${created.id} reported`);
      }
      $("dlg").close();
      render();
    } catch (err) { toast(err.message); }
  };

  if (!bug) return;
  const refresh = () => { openBug(bug.id); render(); };
  document.querySelectorAll("[data-status]").forEach(btn => btn.onclick = async () => {
    try {
      await api("PATCH", `/api/bugs/${bug.id}/status`, { status: btn.dataset.status });
      toast(`Status changed to ${label(btn.dataset.status)}`);
      refresh();
    } catch (err) { toast(err.message); }
  });
  const assignBtn = $("assignBtn");
  if (assignBtn) assignBtn.onclick = async () => {
    const v = $("assignee").value;
    try {
      await api("PATCH", `/api/bugs/${bug.id}/assignment`, { assigneeId: v ? Number(v) : null });
      toast(v ? "Bug assigned" : "Bug unassigned");
      refresh();
    } catch (err) { toast(err.message); }
  };
  $("addComment").onclick = async () => {
    const text = $("newComment").value.trim();
    if (!text) return;
    try {
      await api("POST", `/api/bugs/${bug.id}/comments`, { commentText: text });
      openBug(bug.id);
    } catch (err) { toast(err.message); }
  };
}

/* ---------- Theme ---------- */
// Follows the system setting until the user picks a theme; the choice is remembered in this browser.
const THEME_KEY = "bugtrack-theme";
const systemDark = window.matchMedia("(prefers-color-scheme: dark)");
const SUN = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>`;
const MOON = `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>`;

function isDarkTheme() {
  const chosen = document.documentElement.dataset.theme;
  return chosen ? chosen === "dark" : systemDark.matches;
}

function updateThemeButton() {
  const btn = $("themeToggle");
  const dark = isDarkTheme();
  btn.innerHTML = dark ? SUN : MOON;
  btn.title = dark ? "Switch to light mode" : "Switch to dark mode";
  btn.setAttribute("aria-label", btn.title);
}

$("themeToggle").onclick = () => {
  const next = isDarkTheme() ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  try { localStorage.setItem(THEME_KEY, next); } catch { /* storage unavailable: applies to this visit only */ }
  updateThemeButton();
};
systemDark.addEventListener("change", updateThemeButton);
updateThemeButton();

/* ---------- Startup ---------- */
$("nav").addEventListener("click", e => {
  if (e.target.dataset.view) { view = e.target.dataset.view; render(); }
});
$("newBugBtn").onclick = () => openBug(null);
// Drop a #bug-N link from the address once its dialog is closed, so reloading doesn't reopen it.
$("dlg").addEventListener("close", () => {
  if (/^#bug-\d+$/.test(location.hash)) history.replaceState(null, "", location.pathname + location.search);
});
$("logoutBtn").onclick = async () => {
  try { await api("POST", "/api/auth/logout"); } catch { /* session may already be gone */ }
  me = null;
  await loadCsrf();
  showAuth("login");
};

(async function start() {
  try {
    await loadCsrf();
  } catch {
    $("app").innerHTML = `<div class="empty">Cannot reach the BugTrack server.</div>`;
    return;
  }
  try {
    me = await api("GET", "/api/auth/me");
  } catch {
    me = null;
  }
  if (me) await enterApp();
  else showAuth("login");
})();
