export const remote = Boolean(
  document.querySelector('meta[name="notiflow-remote"][content="true"]'),
);
const key = "notiflow.remote.code";
export const hasRemoteCode = () => Boolean(sessionStorage.getItem(key));
export const disconnectRemote = () => sessionStorage.removeItem(key);

async function requestRemote(method, args, code = sessionStorage.getItem(key)) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch("/api/rpc", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${code || ""}`,
      },
      body: JSON.stringify({ method, args }),
      signal: controller.signal,
      cache: "no-store",
      credentials: "omit",
    });
    const result = await response.json();
    if (response.status === 401 && sessionStorage.getItem(key) === code) {
      disconnectRemote();
      window.dispatchEvent(new Event("notiflowRemoteExpired"));
    }
    if (!response.ok || result.ok === false)
      throw new Error(result.error || "휴대폰과의 연결을 다시 확인해 주세요");
    return result.data;
  } catch (error) {
    if (error instanceof TypeError || error.name === "AbortError")
      throw new Error(
        "휴대폰 서버에 연결하지 못했어요. Tailscale과 서버 실행 상태를 확인해 주세요",
      );
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

export async function connectRemote(code) {
  const normalized = code.trim();
  if (!normalized)
    throw new Error("앱의 설정 화면에 표시된 접속 코드를 입력해 주세요");
  const auth = await requestRemote("getAuthState", [], normalized);
  sessionStorage.setItem(key, normalized);
  return auth;
}

export function call(name, ...args) {
  if (remote) return requestRemote(name, args);
  const bridge = window.NotiFlowNative;
  if (!bridge?.[name]) throw new Error("Android 앱에서 사용할 수 있어요");
  const raw = bridge[name](...args);
  if (raw == null || typeof raw === "boolean") return raw;
  const result = JSON.parse(raw);
  if (result.ok === false)
    throw new Error(result.error || "다시 시도해 주세요");
  return result.data ?? result;
}
