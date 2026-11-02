import { useEffect, useRef, useState, useId } from "react";
import { createPortal } from "react-dom";
import { X, ChevronDown } from "lucide-react";

export function SelectControl({ children, className = "", ...props }) {
  return (
    <span
      className={`relative flex w-full min-w-0 self-start items-center ${className}`}
    >
      <select {...props} className="field appearance-none pr-9">
        {children}
      </select>
      <ChevronDown
        size={16}
        aria-hidden="true"
        className="pointer-events-none absolute right-3 text-muted"
      />
    </span>
  );
}
export function Field({ label, error, hint, children, className = "" }) {
  const id = useId();
  return (
    <div className={`min-w-0 ${className}`}>
      <label className="mb-2 block text-sm font-semibold" htmlFor={id}>
        {label}
      </label>
      {typeof children === "function"
        ? children({
            id,
            "aria-invalid": Boolean(error),
            "aria-describedby": error || hint ? `${id}-help` : undefined,
          })
        : children}
      {(error || hint) && (
        <p
          id={`${id}-help`}
          className={`mt-2 text-xs leading-relaxed ${error ? "text-danger" : "text-muted"}`}
          role={error ? "alert" : undefined}
        >
          {error || hint}
        </p>
      )}
    </div>
  );
}
export function Modal({ title, children, onClose, footer }) {
  const ref = useRef(null),
    lastFocus = useRef(document.activeElement),
    titleId = useId();
  const [position, setPosition] = useState({ x: 0, y: 0 });
  useEffect(() => {
    const element = ref.current;
    element.querySelector('button, input, [tabindex="0"]')?.focus();
    const oldOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const key = (e) => {
      if (e.key === "Escape") onClose();
      if (e.key !== "Tab") return;
      const list = [
        ...element.querySelectorAll(
          'button:not(:disabled), a[href], input, [tabindex="0"]',
        ),
      ];
      if (e.shiftKey && document.activeElement === list[0]) {
        e.preventDefault();
        list.at(-1)?.focus();
      } else if (!e.shiftKey && document.activeElement === list.at(-1)) {
        e.preventDefault();
        list[0]?.focus();
      }
    };
    document.addEventListener("keydown", key);
    return () => {
      document.body.style.overflow = oldOverflow;
      document.removeEventListener("keydown", key);
      lastFocus.current?.focus();
    };
  }, [onClose]);
  function drag(e) {
    if (window.innerWidth < 640 || e.target.closest("button")) return;
    const start = { x: e.clientX - position.x, y: e.clientY - position.y };
    const move = (event) =>
      setPosition({
        x: Math.max(
          -innerWidth / 3,
          Math.min(innerWidth / 3, event.clientX - start.x),
        ),
        y: Math.max(
          -innerHeight / 4,
          Math.min(innerHeight / 4, event.clientY - start.y),
        ),
      });
    const end = () => {
      document.removeEventListener("pointermove", move);
      document.removeEventListener("pointerup", end);
    };
    document.addEventListener("pointermove", move);
    document.addEventListener("pointerup", end);
  }
  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-scrim/70 backdrop-blur-sm sm:items-center"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <section
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        data-ui-audit-overlay
        className="modal w-full max-w-md rounded-t-3xl bg-surface shadow-2xl sm:rounded-3xl"
        style={{
          transform:
            innerWidth >= 640
              ? `translate(${position.x}px, ${position.y}px)`
              : undefined,
        }}
      >
        <div className="mx-auto mt-3 h-1 w-9 rounded-full bg-line sm:hidden" />
        <header
          className="flex touch-none items-center justify-between gap-3 p-6 pb-4 sm:cursor-move"
          onPointerDown={drag}
        >
          <h2 id={titleId} className="text-lg font-bold">
            {title}
          </h2>
          <button className="icon-btn" aria-label="닫기" onClick={onClose}>
            <X size={20} />
          </button>
        </header>
        <div className="max-h-[60dvh] overflow-y-auto px-6 pb-6 text-sm leading-7">
          {children}
        </div>
        {footer && (
          <footer className="flex justify-end gap-2 border-t border-line p-5 pb-[max(1.25rem,env(safe-area-inset-bottom))]">
            {footer}
          </footer>
        )}
      </section>
    </div>,
    document.body,
  );
}
export function Empty({ icon: Icon, title, children, action }) {
  return (
    <div className="flex min-h-64 flex-1 flex-col items-center justify-center px-5 py-12 text-center">
      <div className="mb-5 flex size-16 items-center justify-center rounded-2xl border border-line bg-surface text-primary">
        <Icon size={28} strokeWidth={1.5} />
      </div>
      <h2 className="text-lg font-bold">{title}</h2>
      <p className="mt-2 max-w-xs text-sm leading-6 text-muted">{children}</p>
      {action && <div className="mt-6">{action}</div>}
    </div>
  );
}
export function Skeleton() {
  return (
    <div
      className="animate-pulse space-y-3"
      aria-label="불러오는 중"
      role="status"
    >
      {[1, 2, 3].map((i) => (
        <div key={i} className="h-32 rounded-2xl bg-line/60" />
      ))}
    </div>
  );
}
export function SectionHead({ number, title, text }) {
  return (
    <div className="mb-5 flex items-start gap-3">
      <span className="mt-0.5 shrink-0 whitespace-nowrap font-mono text-xs font-bold text-primary/60">
        {number}
      </span>
      <div className="min-w-0">
        <h2 className="text-base font-bold">{title}</h2>
        <p className="mt-1.5 text-xs leading-6 text-muted">{text}</p>
      </div>
    </div>
  );
}
export function GoogleMark() {
  return (
    <svg width="20" height="20" viewBox="0 0 48 48" aria-hidden="true">
      <path
        fill="#4285F4"
        d="M43.6 24.5c0-1.4-.1-2.8-.4-4.1H24v7.8h11c-.5 2.5-1.9 4.5-4 5.9v4.9h6.4c3.8-3.5 6.2-8.6 6.2-14.5Z"
      />
      <path
        fill="#34A853"
        d="M24 44c5.4 0 9.9-1.8 13.4-4.9L31 34.1c-1.8 1.2-4.1 1.9-7 1.9-5.2 0-9.6-3.5-11.2-8.2H6.2v5.1C9.6 39.5 16.3 44 24 44Z"
      />
      <path
        fill="#FBBC05"
        d="M12.8 27.8c-.4-1.2-.6-2.5-.6-3.8s.2-2.6.6-3.8v-5.1H6.2A20 20 0 0 0 4 24c0 3.2.8 6.2 2.2 8.9l6.6-5.1Z"
      />
      <path
        fill="#EA4335"
        d="M24 12c3 0 5.7 1 7.8 3.1l5.8-5.8C34 6 29.4 4 24 4c-7.7 0-14.4 4.5-17.8 11.1l6.6 5.1C14.4 15.5 18.8 12 24 12Z"
      />
    </svg>
  );
}
