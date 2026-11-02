import { useEffect, useState } from "react";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import * as Tooltip from "@radix-ui/react-tooltip";
import {
  BellOff,
  BellRing,
  CircleHelp,
  LoaderCircle,
  LogOut,
} from "lucide-react";
import { call, useFlow } from "./store.jsx";

export function HeaderTooltip({ label, children }) {
  return (
    <Tooltip.Root>
      <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
      <Tooltip.Portal>
        <Tooltip.Content
          side="bottom"
          align="end"
          sideOffset={8}
          collisionPadding={12}
          data-ui-audit-overlay
          className="z-50 max-w-[calc(100vw-24px)] rounded-lg border border-line bg-surface px-3 py-2 text-xs text-ink shadow-lg"
        >
          {label}
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  );
}

export default function HeaderActions() {
  const { native, auth, logout, notify } = useFlow();
  const [permission, setPermission] = useState(native ? null : "browser");
  const [open, setOpen] = useState(false);
  const [logoutBusy, setLogoutBusy] = useState(false);

  useEffect(() => {
    if (!native) return;
    const refresh = () => {
      if (document.visibilityState === "hidden") return;
      try {
        setPermission(call("isNotificationListenerEnabled"));
      } catch {
        setPermission("error");
      }
    };
    refresh();
    const changed = (event) => setPermission(event.detail.enabled);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    window.addEventListener("notiflowNotificationAccessChanged", changed);
    return () => {
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
      window.removeEventListener("notiflowNotificationAccessChanged", changed);
    };
  }, [native]);

  useEffect(() => {
    if (!open) return;
    const back = (event) => {
      event.stopImmediatePropagation();
      event.preventDefault();
      setOpen(false);
    };
    window.addEventListener("notiflowBack", back, true);
    return () => window.removeEventListener("notiflowBack", back, true);
  }, [open]);

  const label =
    permission === "browser"
      ? "알림 접근: Android 앱에서 사용"
      : permission === null
        ? "알림 접근 상태 확인 중"
        : permission === "error"
          ? "알림 접근 상태 확인 필요"
          : permission
            ? "알림 접근 허용됨"
            : "알림 접근 꺼짐";
  const StatusIcon =
    permission === true
      ? BellRing
      : permission === false || permission === "browser"
        ? BellOff
        : permission === null
          ? LoaderCircle
          : CircleHelp;

  function openNotificationAccess() {
    if (!native) {
      notify("알림 접근 권한은 Android 앱에서 설정할 수 있어요");
      return;
    }
    try {
      call("openNotificationListenerSettings");
    } catch (error) {
      notify(error.message, "error");
    }
  }

  function signOut() {
    setLogoutBusy(true);
    try {
      logout();
    } catch (error) {
      setLogoutBusy(false);
      notify(error.message, "error");
    }
  }

  return (
    <>
      <HeaderTooltip label={label}>
        <button
          type="button"
          aria-label={native ? `${label}, 알림 접근 권한 열기` : label}
          onClick={openNotificationAccess}
          className={`inline-flex size-9 shrink-0 items-center justify-center rounded-full transition hover:bg-accent ${permission === true ? "text-primary" : permission === false || permission === "error" ? "text-warning" : "text-muted"}`}
        >
          <StatusIcon
            size={19}
            aria-hidden="true"
            className={permission === null ? "animate-spin" : undefined}
          />
        </button>
      </HeaderTooltip>
      <DropdownMenu.Root open={open} onOpenChange={setOpen} modal={false}>
        <HeaderTooltip label="계정 메뉴">
          <DropdownMenu.Trigger asChild>
            <button
              type="button"
              aria-label="계정 메뉴"
              disabled={logoutBusy}
              className="inline-flex size-9 shrink-0 items-center justify-center rounded-full border border-primary/15 bg-accent text-xs font-bold text-primary transition hover:border-primary/50"
            >
              {logoutBusy ? (
                <LoaderCircle
                  size={16}
                  className="animate-spin"
                  aria-hidden="true"
                />
              ) : (
                (auth.displayName || "G").slice(0, 2)
              )}
            </button>
          </DropdownMenu.Trigger>
        </HeaderTooltip>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            align="end"
            sideOffset={8}
            collisionPadding={12}
            data-ui-audit-overlay
            className="z-50 w-56 max-w-[calc(100vw-24px)] rounded-xl border border-line bg-surface p-1.5 text-ink shadow-xl"
          >
            <DropdownMenu.Label className="px-3 py-2.5">
              <span className="block truncate text-sm font-semibold">
                {auth.displayName || "Google 계정"}
              </span>
              {auth.email && (
                <span className="mt-1 block truncate text-xs font-normal text-muted">
                  {auth.email}
                </span>
              )}
            </DropdownMenu.Label>
            <DropdownMenu.Separator className="my-1 h-px bg-line" />
            <DropdownMenu.Item
              disabled={logoutBusy}
              onSelect={signOut}
              className="flex min-h-10 cursor-pointer select-none items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-danger outline-none data-[highlighted]:bg-danger-surface data-[disabled]:opacity-50"
            >
              <LogOut size={16} aria-hidden="true" />
              로그아웃
            </DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
    </>
  );
}
