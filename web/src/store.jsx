import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useRef,
} from "react";

const Store = createContext(null);
export const useFlow = () => useContext(Store);
const previewKey = "notiflow.preview.rules.v2";
import {
  call,
  remote,
  connectRemote,
  disconnectRemote,
  hasRemoteCode,
} from "./transport.js";
export { call } from "./transport.js";
export function FlowProvider({ children }) {
  const native = Boolean(window.NotiFlowNative);
  const refreshVersion = useRef(0);
  const [preview, setPreview] = useState(
    !native &&
      !remote &&
      (sessionStorage.getItem("notiflow.preview") === "1" ||
        new URLSearchParams(window.location.search).get("preview") === "1"),
  );
  const [auth, setAuth] = useState(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [rules, setRules] = useState([]);
  const [pendingRule, setPendingRule] = useState(null);
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
  const refresh = useCallback(async () => {
    const version = ++refreshVersion.current;
    setError("");
    try {
      if (native || remote) {
        const [ruleData, logData] = await Promise.all([
          call("listRules"),
          call("listLogs", 200),
        ]);
        if (version !== refreshVersion.current) return;
        setRules(ruleData.rules || []);
        setLogs(logData.logs || []);
      } else if (preview) {
        setRules(JSON.parse(localStorage.getItem(previewKey) || "[]"));
        setLogs([]);
      }
    } catch (err) {
      if (version === refreshVersion.current) setError(err.message);
    } finally {
      if (version === refreshVersion.current) setLoading(false);
    }
  }, [native, preview]);
  useEffect(() => {
    if (remote) {
      let active = true;
      const expired = () => {
        refreshVersion.current++;
        setAuth({ signedIn: false });
        setRules([]);
        setLogs([]);
        setLoading(false);
      };
      if (hasRemoteCode())
        call("getAuthState")
          .then((value) => {
            if (active) setAuth(value);
          })
          .catch((err) => {
            if (active) {
              expired();
              notify(err.message, "error");
            }
          });
      else expired();
      window.addEventListener("notiflowRemoteExpired", expired);
      return () => {
        active = false;
        window.removeEventListener("notiflowRemoteExpired", expired);
      };
    }
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
    if ((!native && !remote) || !auth?.signedIn) return;
    const timer = setInterval(() => {
      if (document.visibilityState === "visible") refresh();
    }, 4000);
    const resume = () => {
      if (document.visibilityState === "visible") {
        refresh();
        if (native) setShare(call("getShareState"));
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
  const save = async (payload) => {
    refreshVersion.current++;
    if (native || remote)
      await call(
        payload.id ? "updateRule" : "createRule",
        JSON.stringify(payload),
      );
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
    await refresh();
    notify(payload.id ? "규칙을 저장했어요" : "새 규칙을 만들었어요");
  };
  const toggle = async (rule) => {
    if (pendingRule !== null) return;
    setPendingRule(rule.id);
    refreshVersion.current++;
    try {
      if (native || remote)
        await call("setRuleEnabled", rule.id, !rule.enabled);
      else
        persistPreview(
          rules.map((r) =>
            r.id === rule.id ? { ...r, enabled: !r.enabled } : r,
          ),
        );
      await refresh();
      notify(rule.enabled ? "규칙을 잠시 멈췄어요" : "규칙을 켰어요");
    } catch (err) {
      notify(err.message, "error");
    } finally {
      setPendingRule(null);
    }
  };
  const remove = async (id) => {
    if (pendingRule !== null)
      throw new Error(
        "변경 사항을 저장하고 있어요. 잠시 후 다시 시도해 주세요",
      );
    setPendingRule(id);
    refreshVersion.current++;
    try {
      if (native || remote) await call("deleteRule", id);
      else persistPreview(rules.filter((r) => r.id !== id));
      await refresh();
      notify("규칙을 삭제했어요");
    } finally {
      setPendingRule(null);
    }
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
  const pair = async (code) => {
    const value = await connectRemote(code);
    setLoading(true);
    setAuth(value);
  };
  const logout = () => {
    refreshVersion.current++;
    if (remote) {
      disconnectRemote();
      setAuth({ signedIn: false });
      setRules([]);
      setLogs([]);
    } else if (native) call("signOutGoogle");
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
        remote,
        pair,
        preview,
        auth,
        authBusy,
        rules,
        pendingRule,
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
