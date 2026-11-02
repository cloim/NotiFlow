import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
} from "react";

const Store = createContext(null);
export const useFlow = () => useContext(Store);
const previewKey = "notiflow.preview.rules.v2";
export function call(name, ...args) {
  const bridge = window.NotiFlowNative;
  if (!bridge?.[name]) throw new Error("Android 앱에서 사용할 수 있어요");
  const raw = bridge[name](...args);
  if (raw == null || typeof raw === "boolean") return raw;
  const result = JSON.parse(raw);
  if (result.ok === false)
    throw new Error(result.error || "다시 시도해 주세요");
  return result.data ?? result;
}
export function FlowProvider({ children }) {
  const native = Boolean(window.NotiFlowNative);
  const [preview, setPreview] = useState(
    !native &&
      (sessionStorage.getItem("notiflow.preview") === "1" ||
        new URLSearchParams(window.location.search).get("preview") === "1"),
  );
  const [auth, setAuth] = useState(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [rules, setRules] = useState([]);
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [toast, setToast] = useState(null);
  const [share, setShare] = useState({});
  const [update, setUpdate] = useState({});
  const [updateOpen, setUpdateOpen] = useState(false);
  const notify = useCallback(
    (message, type = "success") => setToast({ message, type, id: Date.now() }),
    [],
  );
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 4200);
    return () => clearTimeout(timer);
  }, [toast]);
  const refresh = useCallback(() => {
    setError("");
    try {
      if (native) {
        setRules(call("listRules").rules || []);
        setLogs(call("listLogs", 200).logs || []);
      } else if (preview) {
        setRules(JSON.parse(localStorage.getItem(previewKey) || "[]"));
        setLogs([]);
      }
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [native, preview]);
  useEffect(() => {
    if (!native) {
      setAuth({
        signedIn: preview,
        displayName: "미리보기",
        email: "이 브라우저에만 저장합니다",
      });
      setLoading(false);
      return;
    }
    try {
      setAuth(call("getAuthState"));
      setShare(call("getShareState"));
      setUpdate(call("getUpdateState"));
    } catch (err) {
      setError(err.message);
      setAuth({ signedIn: false });
      setLoading(false);
    }
    const onAuth = (e) => {
      setAuth(e.detail);
      setAuthBusy(false);
      if (e.detail.authError) notify(e.detail.authError, "error");
    };
    const onShare = (e) => setShare(e.detail);
    const onUpdate = (e) => {
      setUpdate(e.detail);
      if (e.detail.available) setUpdateOpen(true);
      else if (e.detail.error) notify(e.detail.error, "error");
    };
    window.addEventListener("notiflowAuthChanged", onAuth);
    window.addEventListener("notiflowShareChanged", onShare);
    window.addEventListener("notiflowUpdateChanged", onUpdate);
    return () => {
      window.removeEventListener("notiflowAuthChanged", onAuth);
      window.removeEventListener("notiflowShareChanged", onShare);
      window.removeEventListener("notiflowUpdateChanged", onUpdate);
    };
  }, [native, preview, notify]);
  useEffect(() => {
    if (auth?.signedIn) refresh();
  }, [auth?.signedIn, refresh]);
  useEffect(() => {
    if (!native) return;
    call("checkForUpdate");
  }, [native]);
  useEffect(() => {
    if (!native || !auth?.signedIn) return;
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") refresh();
    }, 4000);
    const resume = () => {
      if (document.visibilityState === "visible") {
        refresh();
        setShare(call("getShareState"));
      }
    };
    document.addEventListener("visibilitychange", resume);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", resume);
    };
  }, [native, auth?.signedIn, refresh]);
  const persistPreview = (items) => {
    localStorage.setItem(previewKey, JSON.stringify(items));
    setRules(items);
  };
  const save = (payload) => {
    if (native)
      call(payload.id ? "updateRule" : "createRule", JSON.stringify(payload));
    else {
      const item = {
        ...payload,
        id: payload.id || Date.now(),
        targetPackages: payload.packageName ? [payload.packageName] : [],
        filters: [],
        actions: [{ type: "webhook.post", config: payload.webhook }],
      };
      persistPreview(
        payload.id
          ? rules.map((r) => (r.id === payload.id ? item : r))
          : [...rules, item],
      );
    }
    refresh();
    notify(payload.id ? "규칙을 저장했어요" : "새 규칙을 만들었어요");
  };
  const toggle = (rule) => {
    try {
      if (native) call("setRuleEnabled", rule.id, !rule.enabled);
      else
        persistPreview(
          rules.map((r) =>
            r.id === rule.id ? { ...r, enabled: !r.enabled } : r,
          ),
        );
      refresh();
      notify(rule.enabled ? "규칙을 잠시 멈췄어요" : "규칙을 켰어요");
    } catch (err) {
      notify(err.message, "error");
    }
  };
  const remove = (id) => {
    if (native) call("deleteRule", id);
    else persistPreview(rules.filter((r) => r.id !== id));
    refresh();
    notify("규칙을 삭제했어요");
  };
  const login = () => {
    try {
      setAuthBusy(true);
      call("signInWithGoogle");
    } catch (err) {
      setAuthBusy(false);
      notify(err.message, "error");
    }
  };
  const logout = () => {
    if (native) call("signOutGoogle");
    else {
      sessionStorage.removeItem("notiflow.preview");
      setPreview(false);
    }
  };
  const startPreview = () => {
    sessionStorage.setItem("notiflow.preview", "1");
    setPreview(true);
  };
  return (
    <Store.Provider
      value={{
        native,
        preview,
        auth,
        authBusy,
        rules,
        logs,
        loading,
        error,
        toast,
        share,
        update,
        setUpdate,
        updateOpen,
        setUpdateOpen,
        notify,
        refresh,
        save,
        toggle,
        remove,
        login,
        logout,
        startPreview,
      }}
    >
      {children}
    </Store.Provider>
  );
}
