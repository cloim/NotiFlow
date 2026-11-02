import { useState, useEffect, useCallback } from "react";
import {
  HashRouter,
  Routes,
  Route,
  Navigate,
  NavLink,
  Link,
  useLocation,
  useNavigate,
} from "react-router-dom";
import {
  Workflow,
  History,
  Share2,
  SlidersHorizontal,
  Webhook,
  ArrowRight,
  ArrowLeft,
  Plus,
  RefreshCw,
  X,
  Download,
  CircleAlert,
  CircleCheck,
} from "lucide-react";
import { FlowProvider, useFlow, call } from "./store.jsx";
import { Modal, GoogleMark } from "./components.jsx";
import { RuleList, RuleDetail, Logs } from "./RuleScreens.jsx";
import RuleEditor from "./RuleEditor.jsx";
import Settings from "./Settings.jsx";
import RemoteLogin from "./RemoteLogin.jsx";
import HeaderActions, { HeaderTooltip } from "./HeaderActions.jsx";
import * as Tooltip from "@radix-ui/react-tooltip";

const nav = [
  ["/rules", "규칙", Workflow],
  ["/logs", "실행 기록", History],
];
function Brand() {
  return (
    <Link
      to="/rules"
      className="flex items-center gap-2.5"
      aria-label="NotiFlow 홈"
    >
      <img
        src={`${import.meta.env.BASE_URL}notiflow-icon.png`}
        alt=""
        className="size-9 rounded-xl object-contain"
      />
      <span className="brand text-xl font-bold tracking-tight">
        NotiFlow<span className="text-primary">.</span>
      </span>
    </Link>
  );
}
function Login() {
  const { native, authBusy, login, startPreview, share } = useFlow();
  return (
    <div className="flex min-h-dvh flex-col bg-canvas lg:flex-row">
      <section className="relative flex flex-col justify-between overflow-hidden bg-sidebar px-6 py-6 text-ink lg:w-[52%] lg:p-14">
        <Brand />
        <div className="relative mb-2 mt-8 max-w-xl lg:my-24">
          <div className="mb-6 hidden items-center gap-2 text-sm text-primary sm:flex">
            <span className="size-2 rounded-full bg-primary" />
            작게 정하고, 자동으로 이어요
          </div>
          <h1 className="text-3xl font-bold leading-[1.4] tracking-tight sm:text-5xl">
            공유한 순간,
            <br />
            다음 일은 <span className="text-primary">자동으로.</span>
          </h1>
          <p className="mt-4 max-w-sm text-sm leading-7 text-muted lg:mt-6 lg:text-base lg:leading-8">
            콘텐츠를 공유하거나 알림을 받으면
            <br />
            내가 정한 규칙에 따라 웹훅을 실행해요
          </p>
          <div className="mt-10 hidden items-center gap-3 text-sm sm:flex">
            <span className="flow-node">
              <Share2 size={20} />
              공유
            </span>
            <ArrowRight size={18} className="text-primary" />
            <span className="flow-node">
              <SlidersHorizontal size={20} />
              규칙
            </span>
            <ArrowRight size={18} className="text-primary" />
            <span className="flow-node">
              <Webhook size={20} />
              웹훅
            </span>
          </div>
        </div>
        <p className="hidden text-xs text-muted lg:block">
          필요한 연결만, 간결하게
        </p>
      </section>
      <section className="flex flex-1 items-center justify-center px-6 py-8 lg:px-7 lg:py-12">
        <div className="w-full max-w-sm">
          <img
            src={`${import.meta.env.BASE_URL}notiflow-icon.png`}
            alt=""
            className="mb-6 hidden size-12 rounded-2xl object-contain lg:block"
          />
          <h2 className="text-xl font-bold tracking-tight sm:text-2xl">
            나만의 흐름을 시작해요
          </h2>
          <p className="mt-3 text-sm leading-7 text-muted">
            Google 계정으로 로그인하고
            <br />첫 번째 자동화 규칙을 만들어 보세요
          </p>
          {(share.received || share.busy) && (
            <div className="mt-6 rounded-2xl bg-accent p-4 text-sm leading-6 text-primary">
              {share.busy
                ? "공유 콘텐츠를 준비하고 있어요"
                : "공유 콘텐츠를 보관했어요. 로그인하면 규칙을 확인해 실행합니다"}
            </div>
          )}
          <button
            onClick={login}
            disabled={!native || authBusy}
            className="mt-8 flex min-h-12 w-full items-center justify-center gap-3 rounded-xl border border-line bg-surface px-5 text-sm font-semibold transition hover:border-primary disabled:opacity-60"
          >
            <GoogleMark />
            {authBusy ? "Google 계정 연결 중…" : "Google로 계속하기"}
          </button>
          <p className="mt-4 text-center text-xs leading-6 text-muted">
            규칙과 실행 기록은 이 기기에 저장합니다
          </p>
          {!native && (
            <div className="mt-8 border-t border-line pt-6 text-center">
              <p className="text-xs leading-6 text-muted">
                Google 로그인과 공유 수신은 Android 앱에서 사용할 수 있어요
              </p>
              <button
                className="mt-3 text-sm font-semibold text-primary underline underline-offset-4"
                onClick={startPreview}
              >
                화면 미리보기
              </button>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
function Shell() {
  const flow = useFlow(),
    location = useLocation(),
    navigate = useNavigate();
  const section = location.pathname.split("/")[1] || "rules";
  const editor = /\/(new|edit)$/.test(location.pathname),
    detail = /^\/rules\/\d+$/.test(location.pathname);
  const title = editor
    ? location.pathname.endsWith("/new")
      ? "새 규칙"
      : "규칙 수정"
    : detail
      ? "규칙 상세"
      : section === "logs"
        ? "실행 기록"
        : section === "settings"
          ? "설정"
          : "내 규칙";
  useEffect(() => {
    const back = () => {
      if (location.pathname !== "/rules") navigate("/rules");
      else if (flow.native) call("closeApp");
    };
    window.addEventListener("notiflowBack", back);
    return () => window.removeEventListener("notiflowBack", back);
  }, [location.pathname, navigate, flow.native]);
  if (!flow.auth)
    return (
      <div
        className="grid min-h-dvh place-items-center bg-canvas text-muted"
        role="status"
      >
        NotiFlow를 준비하고 있어요
      </div>
    );
  if (!flow.auth.signedIn)
    return (
      <>
        {flow.remote ? <RemoteLogin /> : <Login />}
        <Toast />
        <UpdateDialog />
      </>
    );
  return (
    <div className="flex h-dvh overflow-hidden bg-canvas text-ink">
      <aside className="hidden w-60 shrink-0 flex-col bg-sidebar p-6 text-ink lg:flex">
        <Brand />
        <p className="mb-10 mt-4 pl-1 text-xs leading-6 text-muted">
          조건을 정하면, 흐름이 이어져요
        </p>
        <nav className="space-y-2" aria-label="주 메뉴">
          {nav.map(([path, label, Icon]) => (
            <NavLink
              key={path}
              to={path}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-xl px-4 py-3.5 text-sm font-semibold transition ${isActive ? "bg-primary text-on-primary" : "text-muted hover:bg-accent hover:text-ink"}`
              }
            >
              <Icon size={19} />
              {label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="z-10 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-line bg-canvas px-5 sm:px-8 xl:px-12">
          <div className="flex min-w-0 items-center gap-3">
            {(editor || detail || section === "settings") && (
              <HeaderTooltip label="규칙 목록으로">
                <Link
                  to="/rules"
                  className="inline-flex size-9 shrink-0 items-center justify-center rounded-full transition hover:bg-accent"
                  aria-label="규칙 목록으로"
                >
                  <ArrowLeft size={20} />
                </Link>
              </HeaderTooltip>
            )}
            <h1
              data-ui-audit-context-title
              className="text-xl font-bold tracking-tight sm:text-2xl"
            >
              {title}
            </h1>
          </div>
          <div className="flex items-center gap-2 sm:gap-4">
            {section === "rules" && !editor && !detail && (
              <HeaderTooltip label="새 규칙">
                <Link
                  to="/rules/new"
                  className="inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-primary text-on-primary transition hover:bg-primary/90"
                  aria-label="새 규칙 만들기"
                >
                  <Plus size={19} />
                </Link>
              </HeaderTooltip>
            )}
            {section === "logs" && (
              <HeaderTooltip label="기록 새로고침">
                <button
                  className="inline-flex size-9 shrink-0 items-center justify-center rounded-full transition hover:bg-accent"
                  aria-label="기록 새로고침"
                  onClick={flow.refresh}
                >
                  <RefreshCw size={19} />
                </button>
              </HeaderTooltip>
            )}
            <HeaderActions />
          </div>
        </header>
        {flow.preview && location.pathname !== "/rules" && (
          <div className="shrink-0 border-b border-warning/30 bg-warning-surface px-5 py-2 text-center text-xs leading-5 text-warning">
            화면 미리보기 | 규칙은 브라우저에 저장하며 웹훅은 실행하지 않아요
          </div>
        )}
        {location.pathname !== "/rules" && <ShareStatus />}
        <main className="flex min-h-0 min-w-0 flex-1 flex-col pb-18 lg:pb-0">
          <Routes>
            <Route path="/rules" element={<RuleList />} />
            <Route path="/rules/new" element={<RuleEditor key="new" />} />
            <Route path="/rules/:id/edit" element={<RuleEditor />} />
            <Route path="/rules/:id" element={<RuleDetail />} />
            <Route path="/logs" element={<Logs />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="*" element={<Navigate to="/rules" replace />} />
          </Routes>
        </main>
        <nav
          className="fixed inset-x-0 bottom-0 z-20 flex min-h-18 items-center justify-around border-t border-line bg-canvas/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden"
          aria-label="주 메뉴"
        >
          {nav.map(([path, label, Icon]) => (
            <NavLink
              key={path}
              to={path}
              className={({ isActive }) =>
                `flex min-h-14 min-w-20 flex-col items-center justify-center gap-1 rounded-xl text-[11px] font-semibold ${isActive ? "text-primary" : "text-muted"}`
              }
            >
              <Icon
                size={21}
                strokeWidth={section === path.slice(1) ? 2.4 : 1.7}
              />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>
      </div>
      <Toast />
      <UpdateDialog />
    </div>
  );
}
function Toast() {
  const { toast } = useFlow();
  return (
    toast && (
      <div
        role={toast.type === "error" ? "alert" : "status"}
        className={`fixed bottom-24 left-1/2 z-[70] flex w-max max-w-[calc(100%-2rem)] -translate-x-1/2 items-center gap-3 rounded-2xl px-5 py-4 text-sm text-ink shadow-xl lg:bottom-8 ${toast.type === "error" ? "bg-danger-strong text-white" : "bg-accent"}`}
      >
        {toast.type === "error" ? (
          <CircleAlert size={19} className="shrink-0" />
        ) : (
          <CircleCheck size={19} className="shrink-0 text-primary" />
        )}
        <span>{toast.message}</span>
      </div>
    )
  );
}
function ShareStatus() {
  const { share } = useFlow(),
    [dismissed, setDismissed] = useState(null);
  if ((!share.busy && !share.received && !share.error) || dismissed === share)
    return null;
  return (
    <div
      role="status"
      className={`flex shrink-0 items-center gap-3 border-b px-5 py-3 text-sm sm:px-8 ${share.error ? "border-danger/30 bg-danger-surface text-danger" : "border-primary/10 bg-accent text-primary"}`}
    >
      <Share2 size={19} className="shrink-0" />
      <span className="min-w-0 flex-1">
        {share.busy
          ? "공유 콘텐츠를 준비하고 있어요"
          : share.error ||
            `${share.title || "공유 콘텐츠"} | ${share.mimeType || "타입 확인 중"} | ${share.waitingForLogin ? "로그인 후 실행" : "실행 기록에서 결과를 확인하세요"}`}
      </span>
      <button
        className="icon-btn shrink-0"
        aria-label="공유 안내 닫기"
        onClick={() => setDismissed(share)}
      >
        <X size={17} />
      </button>
    </div>
  );
}
function UpdateDialog() {
  const { update, updateOpen, setUpdateOpen, notify } = useFlow(),
    close = useCallback(() => setUpdateOpen(false), [setUpdateOpen]);
  if (!updateOpen || !update.available) return null;
  return (
    <Modal
      title={`새 버전 ${update.latestVersionName}`}
      onClose={close}
      footer={
        <>
          <button className="btn-secondary" onClick={close}>
            나중에
          </button>
          <button
            className="btn-primary"
            onClick={() => {
              try {
                call("downloadUpdate");
                close();
              } catch (err) {
                notify(err.message, "error");
              }
            }}
          >
            <Download size={17} />
            내려받기
          </button>
        </>
      }
    >
      <p>새 버전을 내려받아 설치하면 최신 기능을 쓸 수 있어요</p>
      <p className="mt-2 text-xs text-muted">
        설치 중 ‘알 수 없는 앱 설치’ 권한을 요청하면 허용해 주세요
      </p>
      {update.notes && (
        <p className="mt-5 whitespace-pre-wrap rounded-xl bg-canvas p-4 text-xs leading-6">
          {update.notes}
        </p>
      )}
    </Modal>
  );
}
export default function App() {
  return (
    <FlowProvider>
      <Tooltip.Provider delayDuration={250} skipDelayDuration={100}>
        <HashRouter>
          <Shell />
        </HashRouter>
      </Tooltip.Provider>
    </FlowProvider>
  );
}
