import { useState, useEffect } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  Share2,
  Bell,
  Plus,
  Check,
  CircleCheck,
  Trash2,
  SlidersHorizontal,
  ChevronRight,
  Workflow,
} from "lucide-react";
import { useFlow } from "./store.jsx";
import {
  Field,
  SelectControl,
  SectionHead,
  Skeleton,
  Empty,
} from "./components.jsx";
import {
  ruleForm,
  validateForm,
  toPayload,
  conditionTypes,
  mimeTypes,
} from "./rules.js";

function ConditionRow({ row, index, last, source, error, set, remove }) {
  return (
    <div className="rounded-2xl border border-line bg-canvas/60 p-4">
      <div className="mb-3 flex items-center justify-between gap-3">
        <span className="text-xs font-semibold text-muted">
          조건 {String(index + 1).padStart(2, "0")}
        </span>
        <button
          type="button"
          className="icon-btn text-danger"
          aria-label={`조건 ${index + 1} 삭제`}
          onClick={remove}
        >
          <Trash2 size={16} />
        </button>
      </div>
      <div className="grid gap-3 sm:grid-cols-[170px_1fr]">
        <SelectControl
          aria-label={`조건 ${index + 1} 종류`}
          value={row.type}
          onChange={(e) => set("type", e.target.value)}
        >
          {conditionTypes
            .filter(([type]) => source === "share" || type !== "mime.matches")
            .map(([value, label]) => (
              <option value={value} key={value}>
                {label}
              </option>
            ))}
        </SelectControl>
        <div>
          {row.type === "mime.matches" && (
            <SelectControl
              className="mb-2"
              aria-label={`조건 ${index + 1} 콘텐츠 타입 선택`}
              value={
                mimeTypes.some(([v]) => v === row.value) ? row.value : "custom"
              }
              onChange={(e) =>
                set("value", e.target.value === "custom" ? "" : e.target.value)
              }
            >
              {mimeTypes.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </SelectControl>
          )}
          <input
            className="field"
            aria-label={`조건 ${index + 1} 값`}
            aria-invalid={Boolean(error)}
            value={row.value}
            onChange={(e) => set("value", e.target.value)}
            placeholder={
              row.type === "mime.matches"
                ? "예: image/* 또는 application/pdf"
                : row.type === "text.regex"
                  ? "예: 주문\\s*완료"
                  : "찾을 내용을 입력하세요"
            }
          />
          {error && (
            <p role="alert" className="mt-2 text-xs text-danger">
              {error}
            </p>
          )}
        </div>
      </div>
      <details className="mt-3">
        <summary className="cursor-pointer text-xs text-muted">
          괄호 설정
        </summary>
        <div className="mt-3 grid grid-cols-2 gap-3">
          <Field label="여는 괄호">
            <SelectControl
              aria-label={`조건 ${index + 1} 여는 괄호`}
              value={row.openParen || 0}
              onChange={(e) => set("openParen", e.target.value)}
            >
              {[0, 1, 2, 3].map((n) => (
                <option key={n} value={n}>
                  {n ? "(".repeat(n) : "없음"}
                </option>
              ))}
            </SelectControl>
          </Field>
          <Field label="닫는 괄호">
            <SelectControl
              aria-label={`조건 ${index + 1} 닫는 괄호`}
              value={row.closeParen || 0}
              onChange={(e) => set("closeParen", e.target.value)}
            >
              {[0, 1, 2, 3].map((n) => (
                <option key={n} value={n}>
                  {n ? ")".repeat(n) : "없음"}
                </option>
              ))}
            </SelectControl>
          </Field>
        </div>
      </details>
      {!last && (
        <div className="mt-3 w-36">
          <SelectControl
            aria-label={`조건 ${index + 1} 다음 연결`}
            value={row.operator || "AND"}
            onChange={(e) => set("operator", e.target.value)}
          >
            <option value="AND">그리고 AND</option>
            <option value="OR">또는 OR</option>
          </SelectControl>
        </div>
      )}
    </div>
  );
}
export default function RuleEditor() {
  const { id } = useParams(),
    navigate = useNavigate(),
    { rules, save, loading } = useFlow();
  const existing = rules.find((r) => String(r.id) === id);
  const [form, setForm] = useState(() => ruleForm(existing)),
    [errors, setErrors] = useState({}),
    [busy, setBusy] = useState(false),
    [dirty, setDirty] = useState(false);
  const set = (key, value) => {
    setDirty(true);
    setForm((f) => ({ ...f, [key]: value }));
    setErrors((e) => ({ ...e, [key]: "" }));
  };
  useEffect(() => {
    if (existing && !dirty) setForm(ruleForm(existing));
  }, [existing, dirty]);
  const rowSet = (index, key, value) =>
    set(
      "rows",
      form.rows.map((r, i) => (i === index ? { ...r, [key]: value } : r)),
    );
  function submit(e) {
    e.preventDefault();
    const next = validateForm(form);
    setErrors(next);
    if (Object.keys(next).length) {
      requestAnimationFrame(() =>
        document.querySelector('[aria-invalid="true"]')?.focus(),
      );
      return;
    }
    setBusy(true);
    requestAnimationFrame(() => {
      try {
        save(toPayload(form));
        setDirty(false);
        navigate("/rules");
      } catch (err) {
        setErrors({ submit: err.message });
      } finally {
        setBusy(false);
      }
    });
  }
  if (loading)
    return (
      <div className="p-6">
        <Skeleton />
      </div>
    );
  if (id && !existing)
    return (
      <Empty
        icon={Workflow}
        title="규칙을 찾지 못했어요"
        action={
          <Link to="/rules" className="btn-primary">
            목록으로
          </Link>
        }
      >
        규칙 목록에서 다시 선택해 주세요
      </Empty>
    );
  return (
    <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
      <div
        data-ui-audit-scroll-root
        className="min-h-0 flex-1 overflow-y-auto px-5 py-6 sm:px-8 xl:px-12"
      >
        <div className="mx-auto max-w-3xl space-y-5">
          <section className="panel">
            <SectionHead
              number="01"
              title="무엇을 연결할까요?"
              text="알아보기 쉬운 이름과 입력 유형을 정하세요"
            />
            <Field label="규칙 이름" error={errors.name}>
              {(props) => (
                <input
                  {...props}
                  className="field"
                  placeholder="예: 공유한 PDF를 문서함으로"
                  value={form.name}
                  onChange={(e) => set("name", e.target.value)}
                  maxLength={120}
                />
              )}
            </Field>
            <div className="mt-5 grid grid-cols-2 gap-3">
              {[
                ["share", "앱에서 공유", Share2, "공유 메뉴에서 선택하면"],
                ["notification", "알림 수신", Bell, "새 알림이 도착하면"],
              ].map(([value, title, Icon, desc]) => (
                <button
                  key={value}
                  type="button"
                  onClick={() => {
                    set("source", value);
                    if (value === "notification")
                      set(
                        "rows",
                        form.rows.filter((r) => r.type !== "mime.matches"),
                      );
                  }}
                  aria-pressed={form.source === value}
                  className={`flex flex-col items-start rounded-2xl border p-4 text-left transition ${form.source === value ? "border-primary bg-accent" : "border-line hover:border-primary/40"}`}
                >
                  <span className="mb-3 flex w-full items-center justify-between">
                    <Icon size={22} className="text-primary" />
                    {form.source === value && (
                      <CircleCheck size={17} className="text-primary" />
                    )}
                  </span>
                  <strong className="text-sm">{title}</strong>
                  <span className="mt-1 text-xs leading-5 text-muted">
                    {desc}
                  </span>
                </button>
              ))}
            </div>
            {form.source === "notification" && (
              <Field
                className="mt-5"
                label="대상 앱 패키지"
                hint="비워 두면 모든 앱의 알림을 확인합니다. 예: com.example.app"
              >
                {(props) => (
                  <input
                    {...props}
                    className="field font-mono"
                    value={form.packageName}
                    onChange={(e) => set("packageName", e.target.value)}
                    placeholder="모든 앱"
                  />
                )}
              </Field>
            )}
          </section>
          <section className="panel">
            <SectionHead
              number="02"
              title="이 조건에 맞으면"
              text="조건이 없으면 선택한 입력을 모두 실행합니다"
            />
            <div className="space-y-3">
              {form.rows.map((row, index) => (
                <ConditionRow
                  key={index}
                  row={row}
                  index={index}
                  last={index === form.rows.length - 1}
                  source={form.source}
                  error={errors[`row${index}`]}
                  set={(key, value) => rowSet(index, key, value)}
                  remove={() =>
                    set(
                      "rows",
                      form.rows.filter((_, i) => i !== index),
                    )
                  }
                />
              ))}
            </div>
            {errors.expression && (
              <p className="mt-3 text-sm text-danger" role="alert">
                {errors.expression}
              </p>
            )}
            <button
              type="button"
              className="btn-secondary mt-4 w-full border-dashed"
              onClick={() =>
                set("rows", [
                  ...form.rows,
                  {
                    type:
                      form.source === "share"
                        ? "mime.matches"
                        : "text.contains",
                    value: form.source === "share" ? "*/*" : "",
                    operator: "AND",
                    openParen: 0,
                    closeParen: 0,
                  },
                ])
              }
            >
              <Plus size={17} />
              조건 추가
            </button>
            <p className="mt-3 text-xs leading-6 text-muted">
              AND를 먼저 계산합니다. 계산 순서를 바꾸려면 괄호를 설정하세요
            </p>
          </section>
          <section className="panel">
            <SectionHead
              number="03"
              title="이 웹훅을 실행해요"
              text="조건과 일치한 콘텐츠를 JSON으로 전송합니다"
            />
            <div className="grid gap-4 sm:grid-cols-[120px_1fr]">
              <Field label="메서드">
                <SelectControl
                  aria-label="웹훅 메서드"
                  value={form.method}
                  onChange={(e) => set("method", e.target.value)}
                >
                  {["POST", "PUT", "PATCH"].map((m) => (
                    <option key={m}>{m}</option>
                  ))}
                </SelectControl>
              </Field>
              <Field label="웹훅 URL" error={errors.url}>
                {(props) => (
                  <input
                    {...props}
                    className="field font-mono text-xs"
                    value={form.url}
                    onChange={(e) => set("url", e.target.value)}
                    placeholder="https://example.com/webhook"
                    inputMode="url"
                  />
                )}
              </Field>
            </div>
            <details className="mt-5 border-t border-line pt-4">
              <summary className="flex cursor-pointer items-center gap-2 text-sm font-semibold text-primary">
                <SlidersHorizontal size={16} />
                전송 상세 설정 <ChevronRight size={15} />
              </summary>
              <div className="mt-5 space-y-5">
                <Field
                  label="인증 토큰"
                  hint={
                    existing
                      ? "기존 토큰은 안전하게 보관되어 있어요. 비우고 저장하면 제거합니다"
                      : "필요한 경우 Bearer 토큰을 입력하세요"
                  }
                >
                  {(props) => (
                    <input
                      {...props}
                      type="password"
                      autoComplete="new-password"
                      className="field"
                      value={form.token ?? ""}
                      placeholder={existing ? "기존 토큰 유지" : "선택 사항"}
                      onChange={(e) => set("token", e.target.value)}
                    />
                  )}
                </Field>
                <Field label="요청 헤더 JSON" error={errors.headers}>
                  {(props) => (
                    <textarea
                      {...props}
                      spellCheck={false}
                      rows={4}
                      className="field font-mono text-xs leading-6"
                      value={form.headers}
                      onChange={(e) => set("headers", e.target.value)}
                    />
                  )}
                </Field>
                <Field
                  label="요청 본문 템플릿"
                  error={errors.payloadTemplate}
                  hint="비워 두면 콘텐츠 전체를 전송합니다. 변수에는 따옴표를 붙이지 마세요"
                >
                  {(props) => (
                    <textarea
                      {...props}
                      spellCheck={false}
                      rows={5}
                      className="field font-mono text-xs leading-6"
                      value={form.payloadTemplate}
                      onChange={(e) => set("payloadTemplate", e.target.value)}
                      placeholder={
                        '{\n  "text": {{text}},\n  "files": {{attachments}}\n}'
                      }
                    />
                  )}
                </Field>
                <div className="flex flex-wrap gap-2">
                  {[
                    "source",
                    "mimeType",
                    "title",
                    "text",
                    "attachments",
                    "packageName",
                    "postedAt",
                    "extras",
                  ].map((v) => (
                    <button
                      type="button"
                      key={v}
                      className="rounded-lg border border-line bg-canvas px-2 py-1.5 font-mono text-[11px] text-primary"
                      onClick={() =>
                        set(
                          "payloadTemplate",
                          `${form.payloadTemplate}{{${v}}}`,
                        )
                      }
                    >{`{{${v}}}`}</button>
                  ))}
                </div>
                <p className="text-xs leading-6 text-muted">
                  파일은 이름, MIME 타입, 크기와 Base64 본문으로 전송합니다
                </p>
                <Field
                  label="실행 우선순위"
                  hint="숫자가 큰 규칙부터 실행합니다"
                >
                  {(props) => (
                    <input
                      {...props}
                      type="number"
                      min={0}
                      max={9999}
                      className="field max-w-40"
                      value={form.priority}
                      onChange={(e) => set("priority", e.target.value)}
                    />
                  )}
                </Field>
              </div>
            </details>
          </section>
          {errors.submit && (
            <div
              role="alert"
              className="rounded-xl bg-danger-surface p-4 text-sm text-danger"
            >
              {errors.submit}
            </div>
          )}
        </div>
      </div>
      <footer className="z-10 shrink-0 border-t border-line bg-canvas px-5 py-4 sm:px-8">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-3">
          <Link to="/rules" className="btn-secondary">
            취소
          </Link>
          <button
            disabled={busy}
            type="submit"
            className="btn-primary min-w-36"
          >
            <Check size={18} />
            {busy ? "저장 중…" : "규칙 저장"}
          </button>
        </div>
      </footer>
    </form>
  );
}
