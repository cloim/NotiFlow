import { useState, useCallback } from "react";
import { Link, useNavigate, useParams, useLocation } from "react-router-dom";
import {
  Workflow,
  History,
  Share2,
  Bell,
  ArrowUpRight,
  ChevronRight,
  Pause,
  Play,
  Pencil,
  Trash2,
  RefreshCw,
  CircleAlert,
  CircleCheck,
  Activity,
  X,
} from "lucide-react";
import { useFlow } from "./store.jsx";
import { Modal, Empty, Skeleton, SectionHead } from "./components.jsx";
import { webhookOf, conditionTypes } from "./rules.js";
const sourceLabel = (source) =>
  source === "share" ? "앱에서 공유" : "알림 수신";
const time = (ts) =>
  new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(ts);
export function ErrorState({ error, retry }) {
  return (
    <Empty
      icon={CircleAlert}
      title="다시 연결해 볼까요?"
      action={
        <button className="btn-secondary" onClick={retry}>
          <RefreshCw size={16} />
          다시 시도
        </button>
      }
    >
      {error}
    </Empty>
  );
}
export function RuleList() {
  const { rules, pendingRule, loading, error, refresh, toggle } = useFlow();
  return (
    <div
      data-ui-audit-scroll-root
      className="flex min-h-0 flex-1 flex-col overflow-y-auto px-5 py-5 sm:px-8 xl:px-12"
    >
      {error ? (
        <ErrorState error={error} retry={refresh} />
      ) : loading ? (
        <Skeleton />
      ) : !rules.length ? (
        <div
          role="status"
          className="flex flex-1 items-center justify-center text-sm text-muted"
        >
          등록된 규칙이 없어요
        </div>
      ) : (
        <div className="space-y-3 pb-6">
          {rules.map((rule) => (
            <article
              key={rule.id}
              className="group rounded-2xl border border-line bg-surface p-5 transition hover:border-primary/35 sm:p-6"
            >
              <div className="flex items-start gap-3 sm:gap-4">
                <div
                  className={`flex size-11 shrink-0 items-center justify-center rounded-xl ${rule.enabled ? "bg-accent text-primary" : "bg-canvas text-muted"}`}
                >
                  {rule.source === "share" ? (
                    <Share2 size={21} />
                  ) : (
                    <Bell size={21} />
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="mb-1 flex flex-wrap items-center gap-2">
                    <span className="text-[11px] font-medium text-muted">
                      {sourceLabel(rule.source)}
                    </span>
                    <span
                      className={`badge ${rule.enabled ? "bg-accent text-primary" : "bg-canvas text-muted"}`}
                    >
                      {rule.enabled ? "활성" : "일시정지"}
                    </span>
                  </div>
                  <Link
                    to={`/rules/${rule.id}`}
                    className="block w-fit max-w-full text-base font-bold leading-6 underline-offset-4 hover:underline"
                  >
                    {rule.name}
                  </Link>
                  <p className="mt-2 text-xs leading-6 text-muted">
                    {rule.conditionExpression?.rows?.length
                      ? `${rule.conditionExpression.rows.length}개 조건에 따라 실행`
                      : "선택한 입력의 모든 콘텐츠 실행"}
                  </p>
                </div>
                <button
                  role="switch"
                  aria-checked={rule.enabled}
                  aria-label={`${rule.name} 활성화`}
                  disabled={pendingRule !== null}
                  onClick={() => toggle(rule)}
                  className={`relative mt-1 h-7 w-12 shrink-0 rounded-full border-4 border-transparent transition ${rule.enabled ? "bg-primary" : "bg-line"}`}
                >
                  <span
                    className={`absolute left-0 top-0 size-5 rounded-full shadow-sm transition-transform ${rule.enabled ? "translate-x-5 bg-on-primary" : "bg-muted"}`}
                  />
                </button>
              </div>
              <div className="mt-4 flex min-w-0 items-center gap-2 border-t border-line pt-4">
                <ArrowUpRight size={16} className="shrink-0 text-primary" />
                <span className="shrink-0 whitespace-nowrap font-mono text-[11px] font-bold text-primary">
                  {webhookOf(rule).method || "POST"}
                </span>
                <span className="truncate font-mono text-xs text-muted">
                  {webhookOf(rule).url}
                </span>
                <Link
                  className="ml-auto shrink-0 text-muted hover:text-primary"
                  to={`/rules/${rule.id}`}
                  aria-label={`${rule.name} 상세 보기`}
                >
                  <ChevronRight size={18} />
                </Link>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
export function RuleDetail() {
  const { id } = useParams(),
    navigate = useNavigate(),
    { rules, pendingRule, loading, toggle, remove, notify } = useFlow(),
    [deleting, setDeleting] = useState(false);
  const rule = rules.find((r) => String(r.id) === id),
    close = useCallback(() => setDeleting(false), []);
  if (loading)
    return (
      <div className="p-6">
        <Skeleton />
      </div>
    );
  if (!rule)
    return (
      <Empty
        icon={Workflow}
        title="규칙을 찾지 못했어요"
        action={
          <Link to="/rules" className="btn-primary">
            규칙 목록
          </Link>
        }
      >
        목록에서 다시 선택해 주세요
      </Empty>
    );
  const webhook = webhookOf(rule);
  return (
    <div
      data-ui-audit-scroll-root
      className="flex-1 overflow-y-auto px-5 py-6 sm:px-8 xl:px-12"
    >
      <div className="mx-auto max-w-3xl space-y-5">
        <section className="panel">
          <div className="mb-4 flex items-center justify-between gap-3">
            <span className="badge bg-accent text-primary">
              {sourceLabel(rule.source)}
            </span>
            <span className="text-xs text-muted">
              {rule.enabled ? "활성 상태" : "일시정지 상태"}
            </span>
          </div>
          <h2 className="text-2xl font-bold leading-9">{rule.name}</h2>
          <div className="mt-6 flex flex-wrap gap-2">
            <Link to={`/rules/${id}/edit`} className="btn-primary">
              <Pencil size={17} />
              규칙 수정
            </Link>
            <button
              className="btn-secondary"
              disabled={pendingRule !== null}
              onClick={() => toggle(rule)}
            >
              {rule.enabled ? <Pause size={17} /> : <Play size={17} />}
              {rule.enabled ? "일시정지" : "규칙 켜기"}
            </button>
            <button
              className="icon-btn ml-auto text-danger"
              aria-label="규칙 삭제"
              disabled={pendingRule !== null}
              onClick={() => setDeleting(true)}
            >
              <Trash2 size={19} />
            </button>
          </div>
        </section>
        <section className="panel">
          <SectionHead
            number="01"
            title="입력과 조건"
            text={
              rule.targetPackages?.[0] || "선택한 유형의 모든 입력을 확인합니다"
            }
          />
          <div className="space-y-3">
            {rule.conditionExpression?.rows?.length ? (
              rule.conditionExpression.rows.map((row, i, rows) => (
                <div key={i}>
                  <div className="rounded-xl bg-canvas p-4 text-sm leading-6">
                    <span className="font-semibold text-primary">
                      {conditionTypes.find(([t]) => t === row.type)?.[1] ||
                        row.type}
                    </span>
                    <span className="ml-2 break-all font-mono text-xs">
                      {"(".repeat(row.openParen || 0)} {row.value}{" "}
                      {")".repeat(row.closeParen || 0)}
                    </span>
                  </div>
                  {i < rows.length - 1 && (
                    <p className="mt-3 text-center font-mono text-xs font-semibold text-muted">
                      {row.operator || "AND"}
                    </p>
                  )}
                </div>
              ))
            ) : (
              <p className="text-sm text-muted">
                추가 조건 없이 모든 콘텐츠를 실행해요
              </p>
            )}
          </div>
        </section>
        <section className="panel">
          <SectionHead
            number="02"
            title="웹훅 전송"
            text={`실행 우선순위 ${rule.priority}`}
          />
          <span className="badge bg-accent text-primary">
            {webhook.method || "POST"}
          </span>
          <p className="mt-3 break-all font-mono text-sm leading-7">
            {webhook.url}
          </p>
          <p className="mt-4 text-xs leading-6 text-muted">
            {webhook.payloadTemplate
              ? "사용자 지정 JSON 본문"
              : "기본 JSON 본문으로 전체 콘텐츠 전송"}
          </p>
          {webhook.payloadTemplate && (
            <pre className="mt-3 overflow-x-auto rounded-xl bg-canvas p-4 text-xs leading-6">
              {webhook.payloadTemplate}
            </pre>
          )}
          <Link
            className="mt-5 inline-flex items-center gap-2 text-sm font-semibold text-primary"
            to={`/logs?rule=${id}`}
          >
            이 규칙의 실행 기록
            <ArrowUpRight size={16} />
          </Link>
        </section>
      </div>
      {deleting && (
        <Modal
          title="이 규칙을 삭제할까요?"
          onClose={close}
          footer={
            <>
              <button className="btn-secondary" onClick={close}>
                유지하기
              </button>
              <button
                className="btn-danger"
                disabled={pendingRule !== null}
                onClick={async () => {
                  try {
                    await remove(rule.id);
                    navigate("/rules");
                  } catch (err) {
                    notify(err.message, "error");
                  }
                }}
              >
                삭제하기
              </button>
            </>
          }
        >
          {rule.name}
          <br />
          규칙 설정이 기기에서 삭제됩니다
        </Modal>
      )}
    </div>
  );
}
export function Logs() {
  const { logs, rules, error, loading, refresh } = useFlow(),
    location = useLocation(),
    [filter, setFilter] = useState("all");
  const ruleId = new URLSearchParams(location.search).get("rule");
  const items = logs.filter(
    (l) =>
      (!ruleId || String(l.ruleId) === ruleId) &&
      (filter === "all" ||
        (filter === "success" && l.result.includes("SUCCESS")) ||
        (filter === "failed" && l.result.includes("FAILED")) ||
        (filter === "skip" && !l.matched)),
  );
  return (
    <>
      <div className="shrink-0 px-5 pt-6 sm:px-8 xl:px-12">
        {ruleId && (
          <Link
            to="/logs"
            className="mb-4 inline-flex items-center gap-2 text-xs font-semibold text-primary"
          >
            전체 기록 보기
            <X size={14} />
          </Link>
        )}
        <div
          className="flex flex-wrap gap-1 border-b border-line pb-4"
          role="group"
          aria-label="실행 결과 필터"
        >
          {[
            ["all", "전체"],
            ["success", "성공"],
            ["failed", "실패"],
            ["skip", "조건 불일치"],
          ].map(([value, label]) => (
            <button
              key={value}
              className={`min-h-10 rounded-xl px-3 text-sm font-semibold ${filter === value ? "bg-primary text-on-primary" : "text-muted"}`}
              aria-pressed={filter === value}
              onClick={() => setFilter(value)}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      <div
        data-ui-audit-scroll-root
        className="flex min-h-0 flex-1 flex-col overflow-y-auto px-5 py-5 sm:px-8 xl:px-12"
      >
        {error ? (
          <ErrorState error={error} retry={refresh} />
        ) : loading ? (
          <Skeleton />
        ) : !items.length ? (
          <Empty icon={History} title="아직 실행 기록이 없어요">
            다른 앱에서 콘텐츠를 공유하거나
            <br />
            규칙에 맞는 알림을 기다려 보세요
          </Empty>
        ) : (
          <div className="space-y-3">
            {items.map((log) => {
              const success = log.result.includes("SUCCESS"),
                failed = log.result.includes("FAILED");
              return (
                <article
                  key={log.id}
                  className="rounded-2xl border border-line bg-surface p-5"
                >
                  <div className="flex items-start gap-3">
                    <span
                      className={`mt-0.5 ${success ? "text-primary" : failed ? "text-danger" : "text-muted"}`}
                    >
                      {success ? (
                        <CircleCheck size={21} />
                      ) : failed ? (
                        <CircleAlert size={21} />
                      ) : (
                        <Activity size={21} />
                      )}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <strong className="text-sm">
                          {success
                            ? "웹훅 실행 성공"
                            : failed
                              ? "전송 실패"
                              : "조건 불일치"}
                          {log.result.startsWith("RETRY") && " | 재시도"}
                        </strong>
                        <time className="text-xs text-muted">
                          {time(log.executedAt)}
                        </time>
                      </div>
                      <p className="mt-2 text-sm">
                        {rules.find((r) => r.id === log.ruleId)?.name ||
                          (log.ruleId ? "삭제된 규칙" : "공유 콘텐츠")}
                      </p>
                      <p className="mt-1 truncate text-xs leading-6 text-muted">
                        {log.eventTitle || log.eventPackage}
                      </p>
                      <details className="mt-3 text-xs text-muted">
                        <summary className="cursor-pointer">실행 상세</summary>
                        <p className="mt-2 break-all leading-6">
                          {log.message}
                        </p>
                        <p className="mt-1 font-mono">{log.result}</p>
                      </details>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </div>
    </>
  );
}
