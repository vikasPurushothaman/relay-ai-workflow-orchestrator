import { useEffect, useRef, type ReactNode } from 'react';
import { Link, useLocation } from 'react-router';
import { ApiError } from './api.ts';
import { errorMessage } from './session.ts';
import { formatTime, statusLabel, type Json } from './model.ts';
import type { Resource } from './useResource.ts';
import { useConnected } from './SessionContext.tsx';

export function PageHeading({ title, description, children }: { title: string; description: string; children?: ReactNode }) {
  const heading = useRef<HTMLHeadingElement>(null);
  const location = useLocation();
  useEffect(() => {
    document.title = `${title} — Relay`;
    const frame = requestAnimationFrame(() => heading.current?.focus());
    return () => cancelAnimationFrame(frame);
  }, [location.pathname, title]);
  return <div className="page-heading"><p className="eyebrow">Workflow console</p>
    <h1 ref={heading} tabIndex={-1}>{title}</h1><p>{description}</p>{children}
  </div>;
}

export function Status({ value }: { value: string }) {
  return <span className={`status status-${value}`}><span aria-hidden="true" className="dot" />{statusLabel(value)}</span>;
}
export function Time({ value, missing }: { value: string | null; missing?: string }) {
  return value ? <time dateTime={value}>{formatTime(value)}</time> : <span className="muted">{missing ?? 'Not recorded'}</span>;
}
/** Untrusted JSON rendered as text only; React escapes it and it scrolls inside its own box. */
export function JsonBlock({ value, label, empty = 'None' }: { value: Json | undefined; label: string; empty?: string }) {
  if (value === undefined) return <p className="muted">{empty}</p>;
  return <pre className="json" aria-label={label} tabIndex={0}>{JSON.stringify(value, null, 2)}</pre>;
}

export function NotConnected() {
  return <section className="notice" aria-label="Connection required"><h2>Connect to view data</h2>
    <p>Enter the management token above. The console reads live data from the Relay API; nothing is shown without access.</p></section>;
}

export function isNotFound(error: unknown) { return error instanceof ApiError && error.kind === 'http' && error.status === 404; }

/**
 * Standard view states: first load, first-load failure with Retry, missing resource, and stale data retained
 * after a refresh failure. Renders children only when data exists.
 */
export function ResourceView<T>({ resource, label, notFound, children }: {
  resource: Resource<T>; label: string; notFound?: { text: string; to: string; link: string }; children: (data: T) => ReactNode;
}) {
  const connected = useConnected();
  if (!connected) return <NotConnected />;
  const { data, error, loading, updatedAt, failedAt, refresh } = resource;
  if (data === null) {
    if (error && notFound && isNotFound(error)) return <section className="notice" role="alert"><h2>Not found</h2><p>{notFound.text}</p><Link to={notFound.to}>{notFound.link}</Link></section>;
    if (error) return <section className="notice error" role="alert"><h2>Could not load {label}</h2><p>{errorMessage(error)}</p>
      <button type="button" onClick={refresh} disabled={loading}>{loading ? 'Retrying…' : 'Retry'}</button></section>;
    return <p className="loading" role="status" aria-live="polite"><span className="spinner" aria-hidden="true" />Loading {label}…</p>;
  }
  return <>
    <div className="refresh-bar">
      <span role="status" aria-live="polite">{error
        ? <span className="stale">Showing data from {updatedAt ? formatTime(updatedAt.toISOString()) : 'earlier'} — refresh failed{failedAt ? ` at ${formatTime(failedAt.toISOString())}` : ''}: {errorMessage(error)}</span>
        : updatedAt ? <span className="muted">Last updated {formatTime(updatedAt.toISOString())}</span> : null}</span>
      <button type="button" className="secondary" onClick={refresh} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button>
    </div>
    {children(data)}
  </>;
}

/** Labeled field list used for summaries; turns into stacked blocks on small screens. */
export function Fields({ items }: { items: [string, ReactNode][] }) {
  return <dl className="fields">{items.map(([k, v]) => <div key={k}><dt>{k}</dt><dd>{v}</dd></div>)}</dl>;
}

/** Path segment helper for links built from untrusted IDs. */
export const seg = (id: string) => encodeURIComponent(id);
export function useQuery() { return new URLSearchParams(useLocation().search); }
