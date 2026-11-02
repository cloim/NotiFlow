import { useState } from "react";
import { LoaderCircle, ShieldCheck } from "lucide-react";
import { Field } from "./components.jsx";
import { useFlow } from "./store.jsx";

export default function RemoteLogin() {
  const { pair } = useFlow();
  const [code, setCode] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      await pair(code);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-canvas px-5 py-10 text-ink">
      <main className="w-full max-w-sm">
        <img
          src={`${import.meta.env.BASE_URL}notiflow-icon.png`}
          alt=""
          className="mb-8 size-14 rounded-2xl"
        />
        <h1 className="text-2xl font-bold tracking-tight">휴대폰에 연결해요</h1>
        <p className="mt-3 text-sm leading-7 text-muted">
          NotiFlow 앱의 설정 화면에 표시된 접속 코드를 입력해 주세요
        </p>
        <form onSubmit={submit} className="mt-8 space-y-5">
          <Field label="접속 코드" error={error}>
            {(props) => (
              <input
                {...props}
                className="field font-mono"
                type="password"
                value={code}
                onChange={(event) => {
                  setCode(event.target.value);
                  setError("");
                }}
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
              />
            )}
          </Field>
          <button className="btn-primary w-full" disabled={busy} type="submit">
            {busy ? (
              <LoaderCircle size={18} className="animate-spin" />
            ) : (
              <ShieldCheck size={18} />
            )}
            {busy ? "연결 중" : "연결하기"}
          </button>
        </form>
        <p className="mt-6 text-xs leading-6 text-muted">
          같은 Tailscale 네트워크에 연결되어 있어야 해요
          <br />
          서버를 다시 실행했다면 새 접속 코드를 사용해 주세요
        </p>
      </main>
    </div>
  );
}
