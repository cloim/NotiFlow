import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/* ── Bridge ─────────────────────────────────────────────────────────── */
function getBridge() {
  return (typeof window !== "undefined" && window.NotiFlowNative) || null;
}
function parseBridge(raw) {
  if (typeof raw !== "string") return raw;
  try { return JSON.parse(raw); } catch { return { ok: false, error: raw }; }
}

/* ── Theme ──────────────────────────────────────────────────────────── */
const THEME_STORAGE_KEY = "notiflow.theme";
const THEME_OPTIONS = [
  { id: "system", label: "시스템" },
  { id: "light", label: "라이트" },
  { id: "dark", label: "다크" },
];
function normalizeThemePreference(value) {
  return THEME_OPTIONS.some((option) => option.id === value) ? value : "system";
}
function getInitialThemePreference() {
  if (typeof window === "undefined") return "system";
  return normalizeThemePreference(window.localStorage?.getItem(THEME_STORAGE_KEY));
}
function applyThemePreference(preference) {
  if (typeof document === "undefined") return;
  const nextTheme = normalizeThemePreference(preference);
  document.documentElement.setAttribute("data-theme", nextTheme);
  document.documentElement.style.colorScheme = nextTheme === "system" ? "light dark" : nextTheme;
}

/* ── Helpers ────────────────────────────────────────────────────────── */
function condId() { return `c_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`; }
function groupId() { return `g_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`; }
function normLogicOp(value, fallback = "AND") {
  const v = String(value ?? "").trim().toUpperCase();
  return v === "OR" ? "OR" : v === "AND" ? "AND" : fallback;
}
function normParenCount(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) return 0;
  return Math.floor(n);
}
function blankCond() {
  return {
    id: condId(),
    type: "text.contains",
    value: "",
  };
}
function blankGroup(intraOp = "AND", nextGroupOp = null) {
  return {
    id: groupId(),
    intraOp: normLogicOp(intraOp),
    nextGroupOp: nextGroupOp ? normLogicOp(nextGroupOp) : null,
    conditions: [blankCond()],
  };
}
function ensureAtLeastOneGroup(groups) {
  if (Array.isArray(groups) && groups.length) return groups;
  return [blankGroup()];
}
function emptyForm() {
  return {
    id: null, name: "", packageName: "",
    groups: [blankGroup()],
    webhookUrl: "", webhookMethod: "POST",
    headersRaw: "", payloadTemplate: "",
    token: "", removeToken: false, hasTokenRef: false,
    enabled: true, priority: 100,
  };
}

function normalizeInstalledApps(items) {
  if (!Array.isArray(items)) return [];
  return items
    .map((item) => ({
      appLabel: String(item?.appLabel ?? "").trim(),
      packageName: String(item?.packageName ?? "").trim(),
      isSystem: Boolean(item?.isSystem),
    }))
    .filter((item) => item.packageName)
    .sort((a, b) =>
      a.appLabel.localeCompare(b.appLabel) || a.packageName.localeCompare(b.packageName)
    );
}

function parseHeaders(raw) {
  const text = String(raw ?? "").trim();
  if (!text) return { ok: true, headers: {} };
  const headers = {};
  for (const line of text.split(/\r?\n/).map(l => l.trim()).filter(Boolean)) {
    const idx = line.indexOf(":");
    if (idx <= 0 || idx === line.length - 1)
      return { ok: false, error: `헤더 형식이 올바르지 않습니다: ${line}` };
    const k = line.slice(0, idx).trim(), v = line.slice(idx + 1).trim();
    if (!k || !v) return { ok: false, error: `헤더 형식이 올바르지 않습니다: ${line}` };
    headers[k] = v;
  }
  return { ok: true, headers };
}

function fallbackSingleGroupFromRows(rows, fallbackOp = "AND") {
  const conds = (rows ?? [])
    .map((row) => ({
      id: condId(),
      type: String(row?.type ?? "").trim() || "text.contains",
      value: String(row?.value ?? "").trim(),
    }))
    .filter((row) => row.value);

  const inferredIntra = normLogicOp(
    rows?.find((row, idx) => idx < (rows.length - 1) && row?.operator)?.operator,
    fallbackOp,
  );

  return [
    {
      id: groupId(),
      intraOp: inferredIntra,
      nextGroupOp: null,
      conditions: conds.length ? conds : [blankCond()],
    },
  ];
}

function groupsFromExpressionRows(rows, fallbackOp = "AND") {
  if (!Array.isArray(rows) || rows.length === 0) return { ok: false, reason: "empty" };

  const out = [];
  let i = 0;

  while (i < rows.length) {
    const startRow = rows[i];
    if (normParenCount(startRow?.openParen) !== 1) {
      return { ok: false, reason: "non-grouped-start" };
    }

    const conditions = [];
    const internalOps = [];
    let depth = 0;
    let j = i;

    while (j < rows.length) {
      const row = rows[j];
      depth += normParenCount(row?.openParen);
      depth -= normParenCount(row?.closeParen);
      if (depth < 0) return { ok: false, reason: "invalid-paren-depth" };

      const type = String(row?.type ?? "").trim() || "text.contains";
      const value = String(row?.value ?? "").trim();
      if (value) conditions.push({ id: condId(), type, value });

      if (depth > 0) {
        if (!["AND", "OR"].includes(String(row?.operator ?? ""))) {
          return { ok: false, reason: "invalid-inner-operator" };
        }
        internalOps.push(normLogicOp(row?.operator, fallbackOp));
      }

      if (depth === 0) break;
      j += 1;
    }

    if (depth !== 0) return { ok: false, reason: "unclosed-group" };
    if (!conditions.length) return { ok: false, reason: "empty-group" };

    const intraOp = internalOps.length
      ? internalOps.every((op) => op === internalOps[0])
        ? internalOps[0]
        : null
      : normLogicOp(fallbackOp);
    if (!intraOp) return { ok: false, reason: "mixed-inner-operator" };

    const isLastGroup = j >= rows.length - 1;
    const boundaryOp = !isLastGroup ? String(rows[j]?.operator ?? "") : null;
    if (!isLastGroup && !["AND", "OR"].includes(boundaryOp)) {
      return { ok: false, reason: "invalid-group-operator" };
    }

    out.push({
      id: groupId(),
      intraOp,
      nextGroupOp: isLastGroup ? null : normLogicOp(boundaryOp, fallbackOp),
      conditions,
    });

    i = j + 1;
  }

  if (!out.length) return { ok: false, reason: "no-groups" };
  out[out.length - 1].nextGroupOp = null;
  return { ok: true, groups: out };
}

function toEditableGroups(rule) {
  const fallbackOp = String(rule?.filterOperator ?? "AND");
  const expressionRows = Array.isArray(rule?.conditionExpression?.rows)
    ? rule.conditionExpression.rows
    : null;

  if (expressionRows?.length) {
    const mapped = groupsFromExpressionRows(expressionRows, fallbackOp);
    if (mapped.ok) return ensureAtLeastOneGroup(mapped.groups);
    return fallbackSingleGroupFromRows(expressionRows, fallbackOp);
  }

  const conds = (rule?.filters ?? [])
    .filter((f) => f?.type && f.type !== "package.equals")
    .map((f) => ({
      id: condId(),
      type: f.type,
      value: String(f?.config?.value ?? "").trim(),
    }))
    .filter((c) => c.value);

  return [{
    id: groupId(),
    intraOp: normLogicOp(fallbackOp),
    nextGroupOp: null,
    conditions: conds.length ? conds : [blankCond()],
  }];
}

function toWebhookData(rule) {
  const wh = (rule?.actions ?? []).find(a => a?.type === "webhook.post")?.config ?? {};
  const hEntries = Object.entries(wh.headers ?? {}).filter(([k, v]) => k && v);
  return {
    url: String(wh.url ?? ""),
    method: String(wh.method ?? "POST"),
    headersRaw: hEntries.map(([k, v]) => `${k}: ${v}`).join("\n"),
    payloadTemplate: String(wh.payloadTemplate ?? ""),
    hasTokenRef: String(wh.tokenRef ?? "").length > 0,
  };
}

function expressionRowsForSubmit(groups, fallbackOp = "AND") {
  const normGroups = ensureAtLeastOneGroup(groups).map((group, gIdx, arr) => ({
    ...group,
    intraOp: normLogicOp(group?.intraOp, fallbackOp),
    nextGroupOp: gIdx < arr.length - 1
      ? normLogicOp(group?.nextGroupOp, fallbackOp)
      : null,
    conditions: Array.isArray(group?.conditions) ? group.conditions : [],
  }));

  const rows = [];
  for (const group of normGroups) {
    const conds = group.conditions
      .map((cond) => ({
        type: String(cond?.type ?? "").trim(),
        value: String(cond?.value ?? "").trim(),
      }))
      .filter((cond) => cond.type || cond.value);

    if (!conds.length) return { ok: false, error: "각 그룹에는 조건이 하나 이상 필요합니다." };
    if (conds.some((cond) => !cond.type || !cond.value)) {
      return { ok: false, error: "각 조건에는 유형과 값이 모두 필요합니다." };
    }

    conds.forEach((cond, idx) => {
      rows.push({
        type: cond.type,
        value: cond.value,
        openParen: idx === 0 ? 1 : 0,
        closeParen: idx === conds.length - 1 ? 1 : 0,
        operator: idx < conds.length - 1 ? group.intraOp : null,
      });
    });

    if (rows.length && group.nextGroupOp) {
      rows[rows.length - 1].operator = normLogicOp(group.nextGroupOp, fallbackOp);
    }
  }

  if (!rows.length) return { ok: false, error: "조건이 하나 이상 필요합니다." };
  rows[rows.length - 1].operator = null;

  return { ok: true, rows, groups: normGroups };
}

function validateExpressionRows(rows) {
  let depth = 0;
  for (let i = 0; i < rows.length; i += 1) {
    const row = rows[i];
    depth += normParenCount(row.openParen);
    depth -= normParenCount(row.closeParen);
    if (depth < 0) return { ok: false, error: "괄호가 올바르지 않습니다. 닫는 괄호가 너무 많습니다." };
    if (i < rows.length - 1 && !["AND", "OR"].includes(String(row.operator ?? ""))) {
      return { ok: false, error: `${i + 1}번째 행에는 AND/OR 연산자가 필요합니다.` };
    }
  }
  if (depth !== 0) return { ok: false, error: "괄호가 올바르지 않습니다. 여는 괄호가 남아 있습니다." };
  return { ok: true };
}

function normConds(conds) {
  return (conds ?? []).map((c, idx, arr) => {
    const open = "(".repeat(normParenCount(c.openParen));
    const close = ")".repeat(normParenCount(c.closeParen));
    const op = idx < arr.length - 1 ? ` ${normLogicOp(c.operator)} ` : "";
    return `${open}${c.type}:${c.value}${close}${op}`;
  }).join("").trim();
}
function normHeaders(raw) {
  const p = parseHeaders(raw);
  if (!p.ok) return String(raw ?? "").trim();
  return Object.entries(p.headers).sort(([a], [b]) => a.localeCompare(b)).map(([k, v]) => `${k}: ${v}`).join("\n");
}
function ruleToNorm(rule) {
  const wh = toWebhookData(rule);
  const rowsResult = expressionRowsForSubmit(toEditableGroups(rule), String(rule.filterOperator ?? "AND"));
  const condsText = rowsResult.ok ? normConds(rowsResult.rows) : "";
  return {
    name: String(rule.name ?? "").trim(),
    pkg: String(rule.targetPackages?.[0] ?? "").trim(),
    op: String(rule.filterOperator ?? "AND"),
    conds: condsText,
    url: wh.url, method: wh.method,
    headers: normHeaders(wh.headersRaw),
    payload: wh.payloadTemplate,
    tokenMode: wh.hasTokenRef ? "stored" : "none",
    enabled: String(rule.enabled), priority: String(rule.priority ?? 100),
  };
}
function formToNorm(form) {
  const tokenMode = form.removeToken ? "remove" : form.token.trim() ? "replace" : form.hasTokenRef ? "stored" : "none";
  const rows = expressionRowsForSubmit(form.groups);
  const condsText = rows.ok ? normConds(rows.rows) : "";
  const firstOp = rows.ok && rows.rows.length > 1 ? rows.rows[0].operator : "AND";
  return {
    name: form.name.trim(), pkg: form.packageName.trim(),
    op: firstOp,
    conds: condsText,
    url: form.webhookUrl.trim(), method: form.webhookMethod,
    headers: normHeaders(form.headersRaw), payload: form.payloadTemplate?.trim() ?? "",
    tokenMode, enabled: String(form.enabled), priority: String(form.priority),
  };
}
function buildDiff(base, draft) {
  if (!base || !draft) return [];
  const out = [];
  const push = (label, a, b) => { if (a !== b) out.push({ label, from: a || "(empty)", to: b || "(empty)" }); };
  push("이름", base.name, draft.name);
  push("패키지", base.pkg, draft.pkg);
  push("연산자", base.op, draft.op);
  push("조건", base.conds, draft.conds);
  push("webhook.url", base.url, draft.url);
  push("webhook.method", base.method, draft.method);
  push("webhook.headers", base.headers, draft.headers);
  push("webhook.payload", base.payload, draft.payload);
  push("webhook.token", base.tokenMode, draft.tokenMode);
  push("활성화", base.enabled, draft.enabled);
  push("우선순위", base.priority, draft.priority);
  return out;
}

function logClass(result) {
  if (!result) return "n";
  const r = result.toUpperCase();
  if (r.includes("SUCCESS")) return "s";
  if (r.includes("FAILED")) return "f";
  if (r.includes("RETRY")) return "r";
  return "n";
}
function logResultLabel(result) {
  const r = String(result ?? "").toUpperCase();
  if (r === "ALL") return "전체";
  if (r.includes("SUCCESS")) return "성공";
  if (r.includes("FAILED")) return "실패";
  if (r.includes("RETRY")) return "재시도";
  if (r.includes("FILTER_NO_MATCH")) return "조건 불일치";
  return result || "-";
}
function fmtTime(ts) {
  const d = new Date(Number(ts));
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  return d.toLocaleDateString([], { month: "short", day: "numeric" }) + " " + d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}
function fmtBytes(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return "";
  if (n < 1024 * 1024) return `${Math.round(n / 1024)}KB`;
  return `${(n / (1024 * 1024)).toFixed(1)}MB`;
}

/* ── Icons ──────────────────────────────────────────────────────────── */
const Icon = {
  Bell: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/>
    </svg>
  ),
  Log: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/><polyline points="10 9 9 9 8 9"/>
    </svg>
  ),
  Settings: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
    </svg>
  ),
  Plus: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
      <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
    </svg>
  ),
  Edit: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
    </svg>
  ),
  Pause: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>
    </svg>
  ),
  Play: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="5 3 19 12 5 21 5 3"/>
    </svg>
  ),
  Trash: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
    </svg>
  ),
  X: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
      <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
    </svg>
  ),
  Refresh: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
      <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
    </svg>
  ),
  Filter: () => (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/>
    </svg>
  ),
};

/* ── Toast ──────────────────────────────────────────────────────────── */
function Toast({ toast }) {
  return (
    <div className={`toast${toast.show ? " show" : ""}${toast.type === "err" ? "" : ""}`}>
      <span className={`toast-dot ${toast.type}`} />
      {toast.msg}
    </div>
  );
}

/* ── Rule Card ──────────────────────────────────────────────────────── */
function RuleCard({ rule, onEdit, onToggle, onDelete, busy }) {
  const condCount = Array.isArray(rule?.conditionExpression?.rows)
    ? rule.conditionExpression.rows.length
    : (rule.filters ?? []).filter(f => f.type !== "package.equals").length;
  const hasExpression = Array.isArray(rule?.conditionExpression?.rows) && rule.conditionExpression.rows.length > 0;
  const isEnabled = Boolean(rule.enabled);
  return (
    <div className={`rule-card${!isEnabled ? " disabled" : ""}`}>
      <div className="rule-head">
        <div className="rule-icon"><Icon.Bell /></div>
        <div className="rule-info">
          <div className="rule-name">{rule.name || `규칙 #${rule.id}`}</div>
          <div className="rule-pkg">{rule.targetPackages?.[0] ?? "—"}</div>
        </div>
      </div>
      <div className="rule-chips">
        <span className={`chip ${isEnabled ? "chip-green" : "chip-neutral"}`}>
          {isEnabled ? "활성" : "일시 정지"}
        </span>
        <span className="chip chip-neutral">{hasExpression ? "조건식" : (rule.filterOperator ?? "AND")}</span>
        {condCount > 0 && (
          <span className="chip chip-accent">조건 {condCount}개</span>
        )}
        <span className="chip chip-neutral">우선 {rule.priority ?? 100}</span>
      </div>
      <div className="rule-btns">
        <button className="rule-btn edit" onClick={() => onEdit(rule)} disabled={busy}>
          <Icon.Edit /> 수정
        </button>
        <button
          className={`rule-btn ${isEnabled ? "on" : "off"}`}
          onClick={() => onToggle(rule.id, !isEnabled)}
          disabled={busy}
        >
          {isEnabled ? <><Icon.Pause /> 정지</> : <><Icon.Play /> 활성화</>}
        </button>
        <button className="rule-btn del" onClick={() => onDelete(rule.id)} disabled={busy}>
          <Icon.Trash /> 삭제
        </button>
      </div>
    </div>
  );
}

/* ── Rule Form Sheet ────────────────────────────────────────────────── */
function RuleFormSheet({
  open,
  form,
  onClose,
  onSetField,
  onSetGroupField,
  onSetGroupCond,
  onAddGroup,
  onRemoveGroup,
  onAddCondInGroup,
  onRemoveCondInGroup,
  onSubmit,
  busy,
  diff,
  selectedAppLabel,
  onOpenPackagePicker,
}) {
  const isEdit = form.id !== null;
  const expressionPreview = useMemo(() => {
    const rows = expressionRowsForSubmit(form.groups);
    return rows.ok ? normConds(rows.rows) : "";
  }, [form.groups]);
  return (
    <>
      <div className={`backdrop${open ? " open" : ""}`} onClick={onClose} />
      <div className={`sheet${open ? " open" : ""}`}>
        <div className="sheet-grab" />
        <div className="sheet-hdr">
          <span className="sheet-title">{isEdit ? `규칙 #${form.id} 수정` : "새 규칙"}</span>
          <button className="icon-btn" onClick={onClose}><Icon.X /></button>
        </div>

        <div className="sheet-body">
          {/* Package */}
          <div className="field">
            <label className="field-lbl">대상 앱</label>
            <button
              type="button"
              className="field-input pkg-picker-btn"
              onClick={onOpenPackagePicker}
            >
              <span className={`pkg-picker-label${selectedAppLabel ? "" : " placeholder"}`}>
                {selectedAppLabel || "설치된 앱 목록에서 선택"}
              </span>
              <span className={`pkg-picker-package${form.packageName ? "" : " placeholder"}`}>
                {form.packageName || "탭해서 찾아보기"}
              </span>
            </button>
          </div>

          {/* Name */}
          <div className="field">
            <label className="field-lbl">규칙 이름 <span style={{ color: "var(--t3)" }}>(선택)</span></label>
            <input
              className="field-input"
              placeholder="비워두면 자동 생성"
              value={form.name}
              onChange={e => onSetField("name", e.target.value)}
            />
          </div>

          {/* Conditions */}
          <div className="field">
            <label className="field-lbl">조건</label>
            <div className="conds-box">
              <div className="cond-help">그룹을 추가한 뒤 각 그룹 안에 조건을 넣으세요. 괄호는 자동으로 생성됩니다.</div>
              <button className="add-group-btn" onClick={onAddGroup}>+ 그룹 추가</button>

              {form.groups.map((group, gIdx) => {
                const canDeleteGroup = form.groups.length > 1;
                const isLastGroup = gIdx === form.groups.length - 1;
                return (
                  <div key={group.id} className="group-card">
                    <div className="group-head">
                      <div className="group-title">그룹 {gIdx + 1}</div>
                      <button
                        className="group-del"
                        onClick={() => onRemoveGroup(group.id)}
                        disabled={!canDeleteGroup}
                      >
                        삭제
                      </button>
                    </div>

                    <div className="group-conds">
                      {group.conditions.map((c, cIdx) => (
                        <div key={c.id} className="cond-item">
                          <div className="cond-row">
                            <select
                              className="field-input"
                              value={c.type}
                              onChange={(e) => onSetGroupCond(group.id, c.id, "type", e.target.value)}
                            >
                              <option value="text.contains">본문 포함</option>
                              <option value="title.contains">제목 포함</option>
                              <option value="text.regex">본문 정규식</option>
                            </select>
                            <input
                              className="field-input cond-val"
                              placeholder="값"
                              value={c.value}
                              onChange={(e) => onSetGroupCond(group.id, c.id, "value", e.target.value)}
                            />
                            <button
                              className="cond-add"
                              onClick={() => onAddCondInGroup(group.id, cIdx)}
                              title="이 그룹에 조건 추가"
                            >
                              +
                            </button>
                            <button
                              className="cond-del"
                              onClick={() => onRemoveCondInGroup(group.id, c.id)}
                              title="조건 삭제"
                            >
                              ×
                            </button>
                          </div>
                        </div>
                      ))}
                    </div>

                    {group.conditions.length > 1 && (
                      <div className="group-op-row">
                        <span className="cond-link-lbl">그룹 내부</span>
                        <div className="seg cond-seg">
                          {["AND", "OR"].map((op) => (
                            <button
                              key={op}
                              className={`seg-opt${normLogicOp(group.intraOp) === op ? " active" : ""}`}
                              onClick={() => onSetGroupField(group.id, "intraOp", op)}
                            >
                              {op}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}

                    {!isLastGroup && (
                      <div className="group-op-row between">
                        <span className="cond-link-lbl">다음 그룹</span>
                        <div className="seg cond-seg">
                          {["AND", "OR"].map((op) => (
                            <button
                              key={op}
                              className={`seg-opt${normLogicOp(group.nextGroupOp, "AND") === op ? " active" : ""}`}
                              onClick={() => onSetGroupField(group.id, "nextGroupOp", op)}
                            >
                              {op}
                            </button>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}

              <div className="expr-preview">
                <span className="expr-preview-lbl">조건식</span>
                <code className="expr-preview-code">{expressionPreview || "(비어 있음)"}</code>
              </div>
            </div>
          </div>

          {/* Webhook */}
          <div className="field">
            <label className="field-lbl">Webhook URL</label>
            <input
              className="field-input"
              placeholder="https://hooks.example.com/..."
              value={form.webhookUrl}
              onChange={e => onSetField("webhookUrl", e.target.value)}
            />
          </div>

          <div className="field-row">
            <div className="field">
              <label className="field-lbl">메서드</label>
              <select
                className="field-input"
                value={form.webhookMethod}
                onChange={e => onSetField("webhookMethod", e.target.value)}
              >
                <option>POST</option><option>PUT</option><option>PATCH</option>
              </select>
            </div>
            <div className="field">
              <label className="field-lbl">우선순위</label>
              <input
                className="field-input"
                type="number"
                value={form.priority}
                onChange={e => onSetField("priority", Number(e.target.value))}
              />
            </div>
          </div>

          <div className="field">
            <label className="field-lbl">헤더 <span style={{ color: "var(--t3)" }}>(한 줄에 Key: Value)</span></label>
            <textarea
              className="field-input"
              placeholder={"X-App: NotiFlow\nAuthorization: Bearer ..."}
              rows={3}
              value={form.headersRaw}
              onChange={e => onSetField("headersRaw", e.target.value)}
            />
          </div>

          <div className="field">
            <label className="field-lbl">페이로드 템플릿 <span style={{ color: "var(--t3)" }}>(선택)</span></label>
            <textarea
              className="field-input"
              placeholder={'{"title":{{title}},"text":{{text}}}'}
              rows={3}
              value={form.payloadTemplate}
              onChange={e => onSetField("payloadTemplate", e.target.value)}
            />
          </div>

          <div className="field">
            <label className="field-lbl">
              토큰 {isEdit ? "(비워두면 기존 값 유지)" : "(선택)"}
            </label>
            <input
              className="field-input"
              placeholder="Bearer 비밀 토큰"
              type="password"
              value={form.token}
              onChange={e => onSetField("token", e.target.value)}
            />
            {isEdit && form.hasTokenRef && (
              <label className="token-check">
                <input
                  type="checkbox"
                  checked={form.removeToken}
                  onChange={e => onSetField("removeToken", e.target.checked)}
                />
                저장된 토큰 삭제
              </label>
            )}
          </div>

          {isEdit && diff.length > 0 && (
            <div className="diff-box">
              <div className="diff-lbl">변경사항</div>
              {diff.map(d => (
                <div key={d.label} className="diff-line">
                  <strong style={{ color: "var(--t1)" }}>{d.label}</strong>: {d.from} → {d.to}
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="sheet-foot">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>취소</button>
          <button className="btn btn-primary" onClick={onSubmit} disabled={busy}>
            {isEdit ? "변경사항 저장" : "규칙 만들기"}
          </button>
        </div>
      </div>
    </>
  );
}

function PackageLoadingDialog({ open }) {
  return (
    <>
      <div className={`package-loading-backdrop${open ? " open" : ""}`} />
      <div className={`package-loading-dialog${open ? " open" : ""}`} role="dialog" aria-modal="true" aria-live="polite">
        <span className="package-loading-spinner" aria-hidden="true" />
        <span className="package-loading-text">패키지 목록 불러오는 중...</span>
      </div>
    </>
  );
}

function UpdateDialog({ open, updateInfo, busy, onInstall, onClose }) {
  if (!open || !updateInfo?.available) return null;
  const sizeText = fmtBytes(updateInfo.assetSize);
  return (
    <>
      <div className="update-backdrop open" onClick={onClose} />
      <div className="update-dialog open" role="dialog" aria-modal="true">
        <div className="update-dialog-head">
          <div>
            <div className="update-title">새 버전 사용 가능</div>
            <div className="update-desc">
              현재 {updateInfo.currentVersionName}에서 {updateInfo.latestVersionName || updateInfo.latestTag}로 업데이트할 수 있습니다.
            </div>
          </div>
          <button className="icon-btn" onClick={onClose}><Icon.X /></button>
        </div>
        <div className="update-dialog-body">
          <div className="update-meta">
            <span>{updateInfo.assetName}</span>
            {sizeText && <span>{sizeText}</span>}
          </div>
        </div>
        <div className="update-dialog-foot">
          <button className="btn btn-ghost" onClick={onClose} disabled={busy}>나중에</button>
          <button className="btn btn-primary" onClick={onInstall} disabled={busy}>
            {busy ? "다운로드 중..." : "업데이트 설치"}
          </button>
        </div>
      </div>
    </>
  );
}

function AppPickerDialog({
  open,
  apps,
  query,
  includeSystem,
  isLoading,
  errorMessage,
  onQueryChange,
  onIncludeSystemChange,
  onSelect,
  onClose,
}) {
  const filteredApps = useMemo(() => {
    const q = String(query ?? "").trim().toLowerCase();
    if (!q) return apps;
    return apps.filter((app) =>
      app.appLabel.toLowerCase().includes(q) || app.packageName.toLowerCase().includes(q)
    );
  }, [apps, query]);

  return (
    <>
      <div className={`picker-backdrop${open ? " open" : ""}`} onClick={onClose} />
      <div className={`picker-dialog${open ? " open" : ""}`}>
        <div className="picker-head">
          <span className="sheet-title">대상 앱 선택</span>
          <button className="icon-btn" onClick={onClose}><Icon.X /></button>
        </div>

        <div className="picker-body">
          <div className="picker-privacy-note">
            설치된 앱 목록은 이 규칙의 알림 출처를 고르기 위해서만 표시됩니다.
            NotiFlow는 선택한 패키지만 저장합니다.
          </div>

          <label className="picker-toggle">
            <input
              type="checkbox"
              checked={includeSystem}
              onChange={(e) => onIncludeSystemChange(e.target.checked)}
            />
            시스템 앱 포함
          </label>

          <input
            className="field-input"
            placeholder="앱 이름 또는 패키지 검색"
            value={query}
            onChange={(e) => onQueryChange(e.target.value)}
          />

          <div className="picker-count">
            {isLoading ? "패키지 목록 불러오는 중..." : `${filteredApps.length}개 / 전체 ${apps.length}개`}
          </div>

          <div className={`picker-list${isLoading ? " picker-list-loading" : ""}`}>
            {isLoading ? (
              <div className="picker-loading" role="status" aria-live="polite">
                <span className="picker-loading-spinner" aria-hidden="true" />
                <span>설치된 앱 목록을 불러오는 중...</span>
              </div>
            ) : errorMessage ? (
              <div className="picker-error" role="alert">{errorMessage}</div>
            ) : filteredApps.length === 0 ? (
              <div className="picker-empty">설치된 앱을 찾을 수 없습니다.</div>
            ) : (
              filteredApps.map((app) => (
                <button
                  key={app.packageName}
                  className="picker-item"
                  onClick={() => onSelect(app.packageName)}
                >
                  <span className="picker-item-label">{app.appLabel || app.packageName}</span>
                  <span className="picker-item-package">{app.packageName}</span>
                </button>
              ))
            )}
          </div>
        </div>

        <div className="picker-foot">
          <button className="btn btn-ghost" onClick={onClose}>취소</button>
        </div>
      </div>
    </>
  );
}

/* ── Log Item ───────────────────────────────────────────────────────── */
function LogItem({ log }) {
  const cls = logClass(log.result);
  return (
    <div className="log-item">
      <div className={`log-dot ${cls}`} />
      <div className="log-body">
        <div className={`log-result ${cls}`}>{logResultLabel(log.result)}</div>
        <div className="log-pkg">#{log.ruleId} · {log.eventPackage}</div>
        {log.message && <div className="log-msg">{log.message}</div>}
      </div>
      <div className="log-time">{fmtTime(log.executedAt)}</div>
    </div>
  );
}

/* ── App ────────────────────────────────────────────────────────────── */
export default function App() {
  const native = useMemo(() => getBridge(), []);
  const isNative = Boolean(native);

  const [tab, setTab]       = useState("rules");
  const [sheetOpen, setSheet] = useState(false);
  const [rules, setRules]   = useState([]);
  const [logs, setLogs]     = useState([]);
  const [appInfo, setAppInfo] = useState(null);
  const [permEnabled, setPermEnabled] = useState(null);
  const [busy, setBusy]     = useState(false);
  const [form, setFormState] = useState(emptyForm);
  const [baseline, setBaseline] = useState(null);
  const [toast, setToast]   = useState({ show: false, msg: "", type: "ok" });
  const [logFilters, setLogFilters] = useState({ result: "ALL", pkg: "", from: "", to: "" });
  const [installedApps, setInstalledApps] = useState([]);
  const [isLoadingInstalledApps, setIsLoadingInstalledApps] = useState(false);
  const [installedAppsError, setInstalledAppsError] = useState("");
  const [isPackageLoadingDialogOpen, setIsPackageLoadingDialogOpen] = useState(false);
  const [isPackagePickerOpen, setIsPackagePickerOpen] = useState(false);
  const [pickerQuery, setPickerQuery] = useState("");
  const [includeSystemApps, setIncludeSystemApps] = useState(false);
  const [updateInfo, setUpdateInfo] = useState(null);
  const [updateBusy, setUpdateBusy] = useState(false);
  const [updateChecked, setUpdateChecked] = useState(false);
  const [isUpdateDialogOpen, setIsUpdateDialogOpen] = useState(false);
  const [themePreference, setThemePreference] = useState(getInitialThemePreference);
  const toastTimer = useRef(null);
  const installedAppsRequestIdRef = useRef(0);
  const includeSystemAppsRef = useRef(includeSystemApps);

  const showToast = useCallback((msg, type = "ok") => {
    clearTimeout(toastTimer.current);
    setToast({ show: true, msg, type });
    toastTimer.current = setTimeout(() => setToast(t => ({ ...t, show: false })), 3000);
  }, []);

  useEffect(() => {
    includeSystemAppsRef.current = includeSystemApps;
  }, [includeSystemApps]);

  useEffect(() => {
    applyThemePreference(themePreference);
    window.localStorage?.setItem(THEME_STORAGE_KEY, themePreference);
  }, [themePreference]);

  // Data loading
  const loadRules = useCallback(() => {
    if (!native?.listRules) return;
    const r = parseBridge(native.listRules());
    if (r?.ok) setRules(r.data?.rules ?? []);
    else showToast(r?.error ?? "규칙을 불러오지 못했습니다.", "err");
  }, [native, showToast]);

  const loadLogs = useCallback(() => {
    if (!native?.listLogs) return;
    const r = parseBridge(native.listLogs(200));
    if (r?.ok) setLogs(r.data?.logs ?? []);
    else showToast(r?.error ?? "실행 로그를 불러오지 못했습니다.", "err");
  }, [native, showToast]);

  const loadPerm = useCallback(() => {
    if (!native?.isNotificationListenerEnabled) return;
    setPermEnabled(native.isNotificationListenerEnabled());
  }, [native]);

  const loadAppInfo = useCallback(() => {
    if (!native?.getAppInfo) return;
    try { setAppInfo(JSON.parse(native.getAppInfo())); } catch {}
  }, [native]);

  const checkForUpdate = useCallback(async (manual = false) => {
    if (!native?.checkForUpdate) {
      if (manual) showToast("업데이트 확인 기능을 사용할 수 없습니다.", "err");
      return;
    }

    setUpdateBusy(true);
    try {
      await new Promise((resolve) => {
        requestAnimationFrame(() => setTimeout(resolve, 0));
      });

      const r = parseBridge(native.checkForUpdate());
      if (!r?.ok) {
        if (manual) showToast(r?.error ?? "업데이트를 확인하지 못했습니다.", "err");
        return;
      }

      const nextInfo = r.data ?? null;
      setUpdateInfo(nextInfo);
      setUpdateChecked(true);
      if (nextInfo?.available) {
        setIsUpdateDialogOpen(true);
        showToast("새 버전이 있습니다.");
      } else if (manual) {
        showToast("최신 버전입니다.");
      }
    } finally {
      setUpdateBusy(false);
    }
  }, [native, showToast]);

  const loadInstalledApps = useCallback(async (includeSystem = true) => {
    if (!native?.listInstalledApps) return { ok: false, error: "앱 연결 기능을 사용할 수 없습니다.", requestId: -1 };

    const requestId = installedAppsRequestIdRef.current + 1;
    installedAppsRequestIdRef.current = requestId;
    const requestedIncludeSystem = Boolean(includeSystem);

    setIsLoadingInstalledApps(true);
    setInstalledAppsError("");

    try {
      await new Promise((resolve) => {
        requestAnimationFrame(() => {
          setTimeout(resolve, 0);
        });
      });

      const r = parseBridge(native.listInstalledApps(requestedIncludeSystem));
      const isStale = requestId !== installedAppsRequestIdRef.current;
      if (isStale) return { ok: false, stale: true, requestId };

      if (r?.ok) {
        setInstalledApps(normalizeInstalledApps(r.data?.apps));
        return { ok: true, requestId };
      }

      const msg = r?.error ?? "설치된 앱 목록을 불러오지 못했습니다.";
      setInstalledAppsError(msg);
      showToast(msg, "err");
      return { ok: false, error: msg, requestId };
    } finally {
      if (requestId === installedAppsRequestIdRef.current) {
        setIsLoadingInstalledApps(false);
      }
    }
  }, [native, showToast]);

  const refresh = useCallback(() => {
    loadRules(); loadLogs(); loadPerm();
  }, [loadRules, loadLogs, loadPerm]);

  useEffect(() => {
    loadAppInfo(); refresh();
  }, [loadAppInfo, refresh]);

  useEffect(() => {
    if (!isNative) return undefined;
    const timer = setTimeout(() => {
      void checkForUpdate(false);
    }, 700);
    return () => clearTimeout(timer);
  }, [isNative, checkForUpdate]);

  // Form helpers
  const setField = useCallback((k, v) => setFormState(f => ({ ...f, [k]: v })), []);

  const setGroupField = useCallback((groupIdValue, key, value) => {
    setFormState((f) => ({
      ...f,
      groups: f.groups.map((group) => {
        if (group.id !== groupIdValue) return group;
        if (key === "intraOp") return { ...group, intraOp: normLogicOp(value, group.intraOp) };
        if (key === "nextGroupOp") return { ...group, nextGroupOp: normLogicOp(value, "AND") };
        return { ...group, [key]: value };
      }),
    }));
  }, []);

  const setGroupCond = useCallback((groupIdValue, condIdValue, key, value) => {
    setFormState((f) => ({
      ...f,
      groups: f.groups.map((group) => {
        if (group.id !== groupIdValue) return group;
        return {
          ...group,
          conditions: group.conditions.map((cond) => (
            cond.id === condIdValue ? { ...cond, [key]: value } : cond
          )),
        };
      }),
    }));
  }, []);

  const addGroup = useCallback(() => {
    setFormState((f) => {
      const next = ensureAtLeastOneGroup(f.groups).map((group) => ({ ...group }));
      if (next.length) {
        next[next.length - 1] = {
          ...next[next.length - 1],
          nextGroupOp: normLogicOp(next[next.length - 1].nextGroupOp, "OR"),
        };
      }
      next.push(blankGroup("AND", null));
      return { ...f, groups: next };
    });
  }, []);

  const removeGroup = useCallback((groupIdValue) => {
    setFormState((f) => {
      const next = f.groups.filter((group) => group.id !== groupIdValue);
      if (!next.length) return { ...f, groups: [blankGroup()] };
      next[next.length - 1] = { ...next[next.length - 1], nextGroupOp: null };
      return { ...f, groups: next };
    });
  }, []);

  const addCondInGroup = useCallback((groupIdValue, afterIdx) => {
    setFormState((f) => ({
      ...f,
      groups: f.groups.map((group) => {
        if (group.id !== groupIdValue) return group;
        const nextConds = [...group.conditions];
        nextConds.splice(afterIdx + 1, 0, blankCond());
        return { ...group, conditions: nextConds };
      }),
    }));
  }, []);

  const removeCondInGroup = useCallback((groupIdValue, condIdValue) => {
    setFormState((f) => ({
      ...f,
      groups: f.groups.map((group) => {
        if (group.id !== groupIdValue) return group;
        const nextConds = group.conditions.filter((cond) => cond.id !== condIdValue);
        return { ...group, conditions: nextConds.length ? nextConds : [blankCond()] };
      }),
    }));
  }, []);

  const openCreate = useCallback(() => { setFormState(emptyForm()); setBaseline(null); setSheet(true); }, []);
  const openEdit   = useCallback((rule) => {
    const wh = toWebhookData(rule);
    const editableGroups = ensureAtLeastOneGroup(toEditableGroups(rule));
    const normalizedGroups = editableGroups.map((group, idx, arr) => ({
      ...group,
      intraOp: normLogicOp(group.intraOp, "AND"),
      nextGroupOp: idx < arr.length - 1
        ? normLogicOp(group.nextGroupOp, "AND")
        : null,
      conditions: (group.conditions?.length ? group.conditions : [blankCond()]).map((cond) => ({
        id: cond.id ?? condId(),
        type: String(cond.type ?? "text.contains") || "text.contains",
        value: String(cond.value ?? ""),
      })),
    }));

    setFormState({
      id: Number(rule.id), name: String(rule.name ?? ""),
      packageName: String(rule.targetPackages?.[0] ?? ""),
      groups: normalizedGroups,
      webhookUrl: wh.url, webhookMethod: wh.method,
      headersRaw: wh.headersRaw, payloadTemplate: wh.payloadTemplate,
      token: "", removeToken: false, hasTokenRef: wh.hasTokenRef,
      enabled: Boolean(rule.enabled), priority: Number(rule.priority ?? 100),
    });
    setBaseline(ruleToNorm(rule));
    setSheet(true);
  }, []);
  const closeSheet = useCallback(() => setSheet(false), []);

  const openPackagePicker = useCallback(async () => {
    if (!isNative) {
      showToast("브라우저 모드에서는 앱 연결 기능을 사용할 수 없습니다.", "err");
      return;
    }

    setPickerQuery("");
    setInstalledAppsError("");
    setIsPackagePickerOpen(false);
    setIsPackageLoadingDialogOpen(true);

    try {
      const result = await loadInstalledApps(includeSystemAppsRef.current);
      if (result?.ok) {
        setIsPackagePickerOpen(true);
      }
    } finally {
      setIsPackageLoadingDialogOpen(false);
    }
  }, [isNative, loadInstalledApps, showToast]);

  const closePackagePicker = useCallback(() => {
    installedAppsRequestIdRef.current += 1;
    setIsLoadingInstalledApps(false);
    setIsPackageLoadingDialogOpen(false);
    setIsPackagePickerOpen(false);
  }, []);

  const selectPackage = useCallback((packageName) => {
    setFormState((f) => ({ ...f, packageName }));
    setIsPackagePickerOpen(false);
  }, []);

  const handleIncludeSystemAppsChange = useCallback((checked) => {
    setIncludeSystemApps(checked);
    if (!isPackagePickerOpen) return;

    setIsPackageLoadingDialogOpen(true);
    void loadInstalledApps(checked).then((result) => {
      if (result?.requestId === installedAppsRequestIdRef.current) {
        setIsPackageLoadingDialogOpen(false);
      }
    });
  }, [isPackagePickerOpen, loadInstalledApps]);

  const selectedAppLabel = useMemo(() => {
    if (!form.packageName) return "";
    return installedApps.find((app) => app.packageName === form.packageName)?.appLabel ?? "";
  }, [form.packageName, installedApps]);

  const installUpdate = useCallback(async () => {
    if (!native?.installUpdate || !updateInfo?.downloadUrl || !updateInfo?.assetName) {
      showToast("설치할 업데이트 파일을 찾지 못했습니다.", "err");
      return;
    }

    setUpdateBusy(true);
    try {
      await new Promise((resolve) => {
        requestAnimationFrame(() => setTimeout(resolve, 0));
      });

      const r = parseBridge(native.installUpdate(updateInfo.downloadUrl, updateInfo.assetName));
      if (r?.ok) {
        setIsUpdateDialogOpen(false);
        showToast("설치 화면을 열었습니다.");
      } else {
        showToast(r?.error ?? "업데이트 설치를 시작하지 못했습니다.", "err");
      }
    } finally {
      setUpdateBusy(false);
    }
  }, [native, updateInfo, showToast]);

  // Submit
  const submitRule = useCallback(() => {
    if (!isNative) return;
    const expressionRowsResult = expressionRowsForSubmit(form.groups, "AND");
    if (!expressionRowsResult.ok) { showToast(expressionRowsResult.error, "err"); return; }
    const exprValidation = validateExpressionRows(expressionRowsResult.rows);
    if (!exprValidation.ok) { showToast(exprValidation.error, "err"); return; }

    const validConds = expressionRowsResult.rows.map((row) => ({ type: row.type, value: row.value }));
    const expressionPayloadRows = expressionRowsResult.rows.map((row, idx, arr) => ({
      type: row.type,
      value: row.value,
      openParen: row.openParen,
      closeParen: row.closeParen,
      ...(idx < arr.length - 1 ? { operator: row.operator } : {}),
    }));
    const legacyOperator = expressionRowsResult.rows.length > 1
      ? normLogicOp(expressionRowsResult.rows[0].operator, "AND")
      : "AND";

    const ph = parseHeaders(form.headersRaw);
    if (!ph.ok) { showToast(ph.error, "err"); return; }

    const wh = { url: form.webhookUrl, method: form.webhookMethod };
    if (Object.keys(ph.headers).length) wh.headers = ph.headers;
    if (form.payloadTemplate?.trim()) wh.payloadTemplate = form.payloadTemplate.trim();
    const token = form.token.trim();
    if (form.id === null && token) wh.token = token;
    if (form.id !== null) {
      if (form.removeToken) wh.token = "";
      else if (token) wh.token = token;
    }

    const payload = {
      id: form.id, name: form.name, packageName: form.packageName,
      conditionOperator: legacyOperator, conditions: validConds,
      conditionExpression: { rows: expressionPayloadRows },
      enabled: form.enabled, priority: form.priority, webhook: wh,
    };

    setBusy(true);
    try {
      const isEdit = form.id !== null;
      const raw = isEdit
        ? native?.updateRule?.(JSON.stringify(payload))
        : native?.createRule?.(JSON.stringify(payload));
      if (!raw) { showToast("앱 연결 기능을 사용할 수 없습니다.", "err"); return; }
      const r = parseBridge(raw);
      if (!r?.ok) { showToast(r?.error ?? "처리하지 못했습니다.", "err"); return; }
      showToast(isEdit ? "규칙을 수정했습니다." : "규칙을 만들었습니다.");
      closeSheet();
      loadRules();
    } finally {
      setBusy(false);
    }
  }, [form, isNative, native, showToast, closeSheet, loadRules]);

  const toggleRule = useCallback((ruleId, enabled) => {
    if (!native?.setRuleEnabled) return;
    setBusy(true);
    try {
      const r = parseBridge(native.setRuleEnabled(ruleId, enabled));
      if (r?.ok) { showToast(enabled ? "규칙을 활성화했습니다." : "규칙을 일시 정지했습니다."); loadRules(); }
      else showToast(r?.error ?? "상태를 변경하지 못했습니다.", "err");
    } finally { setBusy(false); }
  }, [native, showToast, loadRules]);

  const deleteRule = useCallback((ruleId) => {
    if (!window.confirm(`규칙 #${ruleId}을 삭제할까요?`)) return;
    setBusy(true);
    try {
      const r = parseBridge(native?.deleteRule?.(ruleId));
      if (r?.ok) { showToast("규칙을 삭제했습니다."); loadRules(); }
      else showToast(r?.error ?? "규칙을 삭제하지 못했습니다.", "err");
    } finally { setBusy(false); }
  }, [native, showToast, loadRules]);

  // Computed
  const diff = useMemo(() => {
    if (form.id === null || !baseline) return [];
    return buildDiff(baseline, formToNorm(form));
  }, [form, baseline]);

  const resultOptions = useMemo(() => (
    ["ALL", ...Array.from(new Set(logs.map(l => l.result).filter(Boolean))).sort()]
  ), [logs]);

  const filteredLogs = useMemo(() => {
    const rFilter = logFilters.result;
    const pkgQ = logFilters.pkg.trim().toLowerCase();
    const fromTs = logFilters.from ? new Date(logFilters.from).getTime() : null;
    const toTs   = logFilters.to   ? new Date(logFilters.to).getTime()   : null;
    return logs.filter(l => {
      if (rFilter !== "ALL" && l.result !== rFilter) return false;
      if (pkgQ && !String(l.eventPackage ?? "").toLowerCase().includes(pkgQ)) return false;
      if (fromTs && Number(l.executedAt) < fromTs) return false;
      if (toTs   && Number(l.executedAt) > toTs)   return false;
      return true;
    });
  }, [logs, logFilters]);

  const topbarTitle = tab === "rules" ? "규칙" : tab === "logs" ? "실행 로그" : "설정";
  const topbarSubtitle = tab === "rules"
    ? `자동화 규칙 ${rules.length}개`
    : tab === "logs"
      ? `${filteredLogs.length} / ${logs.length}건`
      : "앱 정보 및 권한";
  const topbarAction = tab === "rules"
    ? refresh
    : tab === "logs"
      ? loadLogs
      : () => { loadAppInfo(); loadPerm(); void checkForUpdate(true); };
  const topbarBusy = tab === "rules" || tab === "logs" ? busy : updateBusy;

  /* ── Render ── */
  return (
    <div className="app">
      <Toast toast={toast} />

      <header className="app-topbar">
        <div className="topbar-copy">
          <div className="topbar-title">{topbarTitle}</div>
          <div className="topbar-subtitle">{topbarSubtitle}</div>
        </div>
        <button
          className="icon-btn topbar-action"
          onClick={topbarAction}
          disabled={topbarBusy}
          aria-label="새로고침"
        >
          <Icon.Refresh />
        </button>
      </header>

      {/* ── Rules Tab ─────────────────────────────────────────────── */}
      {tab === "rules" && (
        <div className="tab-content">
          {rules.length === 0 ? (
            <div className="card">
              <div className="empty">
                <div className="empty-icon">🔔</div>
                <div className="empty-title">아직 규칙이 없습니다</div>
                <div className="empty-desc">+ 버튼을 눌러 첫 알림 규칙을 만드세요.</div>
              </div>
            </div>
          ) : (
            rules.map(rule => (
              <RuleCard
                key={rule.id}
                rule={rule}
                onEdit={openEdit}
                onToggle={toggleRule}
                onDelete={deleteRule}
                busy={busy}
              />
            ))
          )}
        </div>
      )}

      {/* ── Logs Tab ──────────────────────────────────────────────── */}
      {tab === "logs" && (
        <div className="tab-content">
          <div className="card card-body" style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <div className="log-filters">
              <select
                className="field-input"
                value={logFilters.result}
                onChange={e => setLogFilters(f => ({ ...f, result: e.target.value }))}
              >
                {resultOptions.map(r => <option key={r} value={r}>{logResultLabel(r)}</option>)}
              </select>
              <input
                className="field-input"
                placeholder="패키지 필터"
                value={logFilters.pkg}
                onChange={e => setLogFilters(f => ({ ...f, pkg: e.target.value }))}
              />
            </div>
            <div className="log-filters">
              <input
                className="field-input"
                type="datetime-local"
                value={logFilters.from}
                onChange={e => setLogFilters(f => ({ ...f, from: e.target.value }))}
              />
              <input
                className="field-input"
                type="datetime-local"
                value={logFilters.to}
                onChange={e => setLogFilters(f => ({ ...f, to: e.target.value }))}
              />
            </div>
            {(logFilters.result !== "ALL" || logFilters.pkg || logFilters.from || logFilters.to) && (
              <button
                className="btn btn-ghost"
                style={{ fontSize: 13, padding: "7px 12px" }}
                onClick={() => setLogFilters({ result: "ALL", pkg: "", from: "", to: "" })}
              >
                필터 초기화
              </button>
            )}
          </div>

          {filteredLogs.length === 0 ? (
            <div className="card">
              <div className="empty">
                <div className="empty-icon">📋</div>
                <div className="empty-title">실행 로그가 없습니다</div>
                <div className="empty-desc">알림이 발생하면 실행 결과가 여기에 표시됩니다.</div>
              </div>
            </div>
          ) : (
            <div className="card">
              <div className="log-list">
                {filteredLogs.map(log => <LogItem key={log.id} log={log} />)}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Settings Tab ──────────────────────────────────────────── */}
      {tab === "settings" && (
        <div className="tab-content">
          <div className="settings-section">
            <div className="section-lbl">알림 접근 권한</div>
            <div className={`perm-card ${permEnabled === null ? "check" : permEnabled ? "ok" : "warn"}`}>
              <div className="perm-icon">{permEnabled === null ? "⏳" : permEnabled ? "🔔" : "🔕"}</div>
              <div>
                <div className="perm-title">
                  {permEnabled === null ? "확인 중..." : permEnabled ? "리스너 활성화됨" : "권한 필요"}
                </div>
                <div className="perm-desc">
                  {permEnabled
                    ? "NotiFlow가 알림을 수신하고 있습니다."
                    : "알림 수집을 시작하려면 알림 접근 권한을 허용하세요."}
                </div>
              </div>
            </div>
            {permEnabled === false && (
              <button
                className="btn btn-primary"
                onClick={() => native?.openNotificationListenerSettings?.()}
                disabled={!isNative}
              >
                리스너 설정 열기
              </button>
            )}
          </div>

          <div className="settings-section">
            <div className="section-lbl">테마</div>
            <div className="card card-body">
              <div className="theme-row">
                <div>
                  <div className="theme-title">화면 테마</div>
                  <div className="theme-desc">기본값은 기기의 시스템 테마를 따릅니다.</div>
                </div>
                <div className="seg theme-seg" role="radiogroup" aria-label="테마 선택">
                  {THEME_OPTIONS.map((option) => (
                    <button
                      key={option.id}
                      className={`seg-opt${themePreference === option.id ? " active" : ""}`}
                      role="radio"
                      aria-checked={themePreference === option.id}
                      onClick={() => setThemePreference(option.id)}
                    >
                      {option.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </div>

          <div className="settings-section">
            <div className="section-lbl">업데이트</div>
            <div className={`update-card${updateInfo?.available ? " available" : ""}`}>
              <div className="update-card-main">
                <div>
                  <div className="update-title">
                    {updateBusy
                      ? "업데이트 확인 중..."
                      : updateInfo?.available
                        ? "새 버전 사용 가능"
                        : updateChecked
                          ? "최신 버전"
                          : "업데이트 확인"}
                  </div>
                  <div className="update-desc">
                    {updateInfo?.available
                      ? `${updateInfo.currentVersionName} → ${updateInfo.latestVersionName || updateInfo.latestTag}`
                      : updateChecked
                        ? "설치된 버전이 최신 GitHub 릴리즈와 같습니다."
                        : "앱 실행 시 GitHub 최신 릴리즈 APK를 확인합니다."}
                  </div>
                </div>
                {updateInfo?.available && (
                  <span className="chip chip-amber">{updateInfo.latestTag}</span>
                )}
              </div>
              {updateInfo?.available && (
                <div className="update-meta">
                  <span>{updateInfo.assetName}</span>
                  {fmtBytes(updateInfo.assetSize) && <span>{fmtBytes(updateInfo.assetSize)}</span>}
                </div>
              )}
              <div className="update-actions">
                <button
                  className="btn btn-ghost"
                  onClick={() => void checkForUpdate(true)}
                  disabled={!isNative || updateBusy}
                >
                  업데이트 확인
                </button>
                {updateInfo?.available && (
                  <button
                    className="btn btn-primary"
                    onClick={installUpdate}
                    disabled={!isNative || updateBusy}
                  >
                    {updateBusy ? "다운로드 중..." : "업데이트 설치"}
                  </button>
                )}
              </div>
            </div>
          </div>

          {appInfo && (
            <div className="settings-section">
              <div className="section-lbl">앱 정보</div>
              <div className="card">
                {[
                  ["앱", appInfo.appName],
                  ["버전", appInfo.versionName],
                  ["빌드", appInfo.versionCode],
                  ["패키지", appInfo.packageName],
                  ["플랫폼", appInfo.platform],
                ].map(([k, v]) => (
                  <div key={k} className="card-row">
                    <span className="card-key">{k}</span>
                    <span className="card-val">{v}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="settings-section">
            <div className="section-lbl">통계</div>
            <div className="card">
              {[
                ["전체 규칙", rules.length],
                ["활성 규칙", rules.filter(r => r.enabled).length],
                ["전체 로그", logs.length],
                ["성공", logs.filter(l => l.result?.includes("SUCCESS")).length],
                ["실패", logs.filter(l => l.result?.includes("FAILED")).length],
              ].map(([k, v]) => (
                <div key={k} className="card-row">
                  <span className="card-key">{k}</span>
                  <span className="card-val">{v}</span>
                </div>
              ))}
            </div>
          </div>

          {!isNative && (
            <div className="browser-mode-card">
              <div className="browser-mode-text">
                <strong>브라우저 모드</strong> - 앱 연결 기능을 사용할 수 없습니다.
                모든 기능을 사용하려면 NotiFlow Android 앱에서 이 화면을 여세요.
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── FAB ───────────────────────────────────────────────────── */}
      {tab === "rules" && (
        <button className="fab" onClick={openCreate} aria-label="규칙 만들기">
          <Icon.Plus />
        </button>
      )}

      {/* ── Rule Form Sheet ───────────────────────────────────────── */}
      <RuleFormSheet
        open={sheetOpen}
        form={form}
        onClose={closeSheet}
        onSetField={setField}
        onSetGroupField={setGroupField}
        onSetGroupCond={setGroupCond}
        onAddGroup={addGroup}
        onRemoveGroup={removeGroup}
        onAddCondInGroup={addCondInGroup}
        onRemoveCondInGroup={removeCondInGroup}
        onSubmit={submitRule}
        busy={busy}
        diff={diff}
        selectedAppLabel={selectedAppLabel}
        onOpenPackagePicker={openPackagePicker}
      />

      <PackageLoadingDialog open={isPackageLoadingDialogOpen} />

      <UpdateDialog
        open={isUpdateDialogOpen}
        updateInfo={updateInfo}
        busy={updateBusy}
        onInstall={installUpdate}
        onClose={() => setIsUpdateDialogOpen(false)}
      />

      <AppPickerDialog
        open={isPackagePickerOpen}
        apps={installedApps}
        query={pickerQuery}
        includeSystem={includeSystemApps}
        isLoading={isLoadingInstalledApps}
        errorMessage={installedAppsError}
        onQueryChange={setPickerQuery}
        onIncludeSystemChange={handleIncludeSystemAppsChange}
        onSelect={selectPackage}
        onClose={closePackagePicker}
      />

      {/* ── Bottom Nav ────────────────────────────────────────────── */}
      <nav className="bottom-nav">
        {[
          { id: "rules",    label: "규칙",      Icon: Icon.Bell },
          { id: "logs",     label: "로그",      Icon: Icon.Log },
          { id: "settings", label: "설정",      Icon: Icon.Settings },
        ].map(({ id, label, Icon: I }) => (
          <button
            key={id}
            className={`nav-tab${tab === id ? " active" : ""}`}
            onClick={() => setTab(id)}
          >
            <I />
            {label}
          </button>
        ))}
      </nav>
    </div>
  );
}
