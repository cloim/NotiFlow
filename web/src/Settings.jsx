import { useCallback, useEffect, useState } from "react";
import {
  Copy,
  ExternalLink,
  LoaderCircle,
  Play,
  Square,
  Server,
  ShieldCheck,
  Eye,
  EyeOff,
} from "lucide-react";
import { call, useFlow } from "./store.jsx";
import { HeaderTooltip } from "./HeaderActions.jsx";
import { Field } from "./components.jsx";

function ConnectionField({ label, value, notify }) {
  async function copy(event) {
    const button = event.currentTarget;
    const input = button.closest("div").querySelector("input");
    try {
      if (navigator.clipboard && window.isSecureContext)
        await navigator.clipboard.writeText(value);
      else {
        input.focus();
        input.select();
        if (!document.execCommand("copy")) throw new Error();
        button.focus();
      }
      notify(`${label}를 복사했어요`);
    } catch {
      input.focus();
      input.select();
      notify("선택한 내용을 길게 누르거나 Ctrl+C로 복사해 주세요");
    }
  }
  return (
    <label className="block min-w-0">
      <span className="mb-2 block text-sm font-semibold">{label}</span>
      <div className="flex min-w-0 items-center gap-2">
        <input
          aria-label={label}
          value={value}
          readOnly
          className="field min-w-0 flex-1 font-mono text-xs"
          spellCheck={false}
        />
        <HeaderTooltip label={`${label} 복사`}>
          <button
            type="button"
            className="icon-btn shrink-0"
            aria-label={`${label} 복사`}
            onClick={copy}
          >
            <Copy size={18} />
          </button>
        </HeaderTooltip>
      </div>
    </label>
  );
}

export default function Settings() {
  const { native, remote, notify, logout } = useFlow();
  const [state, setState] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [code, setCode] = useState("");
  const [codeDirty, setCodeDirty] = useState(false);
  const [codeError, setCodeError] = useState("");
  const [showCode, setShowCode] = useState(false);
  useEffect(() => {
    if (!codeDirty) setCode(state?.configuredAccessCode || "");
  }, [state?.configuredAccessCode, codeDirty]);
  const refresh = useCallback(() => {
    if (!native) return;
    try {
      setState(call("getServerState"));
      setError("");
    } catch (err) {
      setError(err.message);
    }
  }, [native]);
  useEffect(() => {
    refresh();
    if (!native) return;
    const timer = setInterval(refresh, 2000);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      clearInterval(timer);
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [native, refresh]);
  async function changeServer() {
    setBusy(true);
    try {
      if (state?.status !== "running" && codeDirty && !(await saveCode(false)))
        return;
      await call(state?.status === "running" ? "stopServer" : "startServer");
      refresh();
    } catch (err) {
      notify(err.message, "error");
    } finally {
      setBusy(false);
    }
  }
  async function saveCode(showToast) {
    if (code && !/^[!-~]{8,128}$/.test(code)) {
      setCodeError("공백 없이 영문, 숫자와 기호 8~128자로 입력해 주세요");
      return false;
    }
    const next = await call("saveServerAccessCode", code);
    setState(next);
    setCodeDirty(false);
    setCodeError("");
    if (showToast)
      notify(code ? "접속 코드를 저장했어요" : "자동 접속 코드를 사용해요");
    return true;
  }
  const running = state?.status === "running";
  const starting = state?.status === "starting";
  return (
    <div
      data-ui-audit-scroll-root
      className="flex-1 overflow-y-auto px-5 py-6 sm:px-8 xl:px-12"
    >
      <div className="mx-auto max-w-2xl space-y-5">
        <section className="panel">
          <div className="flex items-start gap-3">
            <span className="rounded-xl bg-accent p-2.5 text-primary">
              <Server size={22} />
            </span>
            <div className="min-w-0 flex-1">
              <h2 className="text-base font-bold">원격 편집 서버</h2>
              <p className="mt-2 text-sm leading-7 text-muted">
                Tailscale로 연결한 기기에서 규칙을 편집하고 실행 기록을 확인해요
              </p>
            </div>
          </div>
          {remote ? (
            <div className="mt-6 space-y-4">
              <p className="flex items-center gap-2 text-sm text-primary">
                <ShieldCheck className="shrink-0" size={18} />
                휴대폰 서버에 연결되어 있어요
              </p>
              <ConnectionField
                label="접속 주소"
                value={window.location.origin}
                notify={notify}
              />
              <p className="text-xs leading-6 text-muted">
                서버 실행과 중지는 Android 앱의 설정에서 관리해 주세요
              </p>
              <button className="btn-secondary" onClick={logout}>
                이 브라우저 연결 해제
              </button>
            </div>
          ) : !native ? (
            <div className="mt-6 rounded-xl bg-accent p-4 text-sm leading-7 text-muted">
              서버는 Android 앱에서 실행할 수 있어요
              <br />
              실행하면 접속 주소와 접속 코드가 표시됩니다
            </div>
          ) : !state && !error ? (
            <div role="status" className="mt-6 animate-pulse space-y-3">
              <div className="h-6 w-28 rounded-lg bg-accent" />
              <div className="h-12 rounded-xl bg-accent" />
              <span className="sr-only">서버 상태 확인 중</span>
            </div>
          ) : (
            <>
              <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-y border-line py-4">
                <span
                  role="status"
                  className={`inline-flex items-center gap-2 text-sm font-semibold ${running ? "text-primary" : "text-muted"}`}
                >
                  {starting ? (
                    <LoaderCircle size={16} className="animate-spin" />
                  ) : (
                    <span
                      className={`size-2 rounded-full ${running ? "bg-primary" : "bg-muted"}`}
                    />
                  )}
                  {running ? "실행 중" : starting ? "서버 시작 중" : "중지됨"}
                </span>
                <button
                  className={running ? "btn-secondary" : "btn-primary"}
                  disabled={busy || starting || Boolean(error)}
                  onClick={changeServer}
                >
                  {busy || starting ? (
                    <LoaderCircle size={17} className="animate-spin" />
                  ) : running ? (
                    <Square size={16} />
                  ) : (
                    <Play size={17} />
                  )}
                  {running ? "서버 중지" : starting ? "시작 중" : "서버 실행"}
                </button>
              </div>
              {(error || state?.error) && (
                <div
                  className="mt-4 rounded-xl bg-danger-surface p-4 text-sm leading-7 text-danger"
                  role="alert"
                >
                  {error || state.error}
                  {error && (
                    <button
                      className="mt-2 block font-semibold underline underline-offset-4"
                      onClick={refresh}
                    >
                      다시 확인
                    </button>
                  )}
                </div>
              )}
              {!running && (
                <div className="mt-5 space-y-3">
                  <Field
                    label="접속 코드"
                    error={codeError}
                    hint="공백 없이 영문, 숫자와 기호 8~128자 | 비워 두면 자동 생성"
                  >
                    {(props) => (
                      <div className="flex items-center gap-2">
                        <input
                          {...props}
                          className="field min-w-0 flex-1 font-mono text-xs"
                          type={showCode ? "text" : "password"}
                          value={code}
                          disabled={busy || starting}
                          autoComplete="new-password"
                          spellCheck={false}
                          onChange={(event) => {
                            setCode(event.target.value);
                            setCodeDirty(true);
                            setCodeError("");
                          }}
                        />
                        <HeaderTooltip
                          label={
                            showCode ? "접속 코드 숨기기" : "접속 코드 보기"
                          }
                        >
                          <button
                            type="button"
                            className="inline-flex size-11 shrink-0 items-center justify-center rounded-xl hover:bg-accent"
                            aria-label={
                              showCode ? "접속 코드 숨기기" : "접속 코드 보기"
                            }
                            aria-pressed={showCode}
                            onClick={() => setShowCode(!showCode)}
                          >
                            {showCode ? (
                              <EyeOff size={18} />
                            ) : (
                              <Eye size={18} />
                            )}
                          </button>
                        </HeaderTooltip>
                      </div>
                    )}
                  </Field>
                  <button
                    className="btn-secondary"
                    disabled={busy || starting || !codeDirty}
                    onClick={async () => {
                      setBusy(true);
                      try {
                        await saveCode(true);
                      } catch (err) {
                        notify(err.message, "error");
                      } finally {
                        setBusy(false);
                      }
                    }}
                  >
                    접속 코드 저장
                  </button>
                </div>
              )}
              {running && (
                <div className="mt-5 space-y-4">
                  <ConnectionField
                    label="접속 주소"
                    value={state.url}
                    notify={notify}
                  />
                  <ConnectionField
                    label="접속 코드"
                    value={state.accessCode}
                    notify={notify}
                  />
                  <p className="text-xs leading-6 text-muted">
                    {state.customAccessCode
                      ? "지정한 코드는 서버를 다시 실행해도 유지돼요"
                      : "자동 코드는 서버를 다시 실행할 때 바뀌어요"}
                    <br />
                    코드를 바꾸려면 서버를 중지해 주세요
                    <br />
                    로그아웃하거나 Tailscale 연결이 끊기면 서버가 종료됩니다
                  </p>
                </div>
              )}
              <div className="mt-5 flex flex-wrap items-center justify-between gap-3">
                <span
                  className={`text-xs ${state?.tailscaleConnected ? "text-primary" : "text-warning"}`}
                >
                  {state?.tailscaleConnected
                    ? "Tailscale 연결됨"
                    : "Tailscale 연결이 필요해요"}
                </span>
                <button
                  className="inline-flex min-h-10 items-center gap-2 text-sm font-semibold text-primary"
                  onClick={() => {
                    try {
                      call("openTailscale");
                    } catch (err) {
                      notify(err.message, "error");
                    }
                  }}
                >
                  Tailscale 열기
                  <ExternalLink size={15} />
                </button>
              </div>
            </>
          )}
        </section>
        {!remote && (
          <section className="panel">
            <h2 className="text-sm font-bold">다른 기기에서 접속하기</h2>
            <ol className="mt-4 space-y-3 text-sm leading-7 text-muted">
              <li className="flex gap-3">
                <span className="shrink-0 whitespace-nowrap font-mono text-primary">
                  01
                </span>
                <span>
                  휴대폰과 PC에서 Tailscale을 켜고 같은 네트워크에 연결해 주세요
                </span>
              </li>
              <li className="flex gap-3">
                <span className="shrink-0 whitespace-nowrap font-mono text-primary">
                  02
                </span>
                <span>
                  앱에서 서버를 실행하고 PC 브라우저로 접속 주소를 열어 주세요
                </span>
              </li>
              <li className="flex gap-3">
                <span className="shrink-0 whitespace-nowrap font-mono text-primary">
                  03
                </span>
                <span>
                  접속 코드를 입력하면 이 휴대폰의 규칙과 실행 기록이 표시돼요
                </span>
              </li>
            </ol>
            <p className="mt-5 border-t border-line pt-4 text-xs leading-6 text-muted">
              기기 간 연결이 차단된 네트워크에서는 Tailscale의 접근 정책에 포트
              8186을 허용해 주세요
            </p>
          </section>
        )}
      </div>
    </div>
  );
}
