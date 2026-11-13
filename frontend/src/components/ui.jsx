import { createContext, useCallback, useContext, useState } from 'react'
import { X, Loader2, Inbox, ChevronLeft, ChevronRight, CheckCircle2, AlertCircle } from 'lucide-react'

export const cn = (...c) => c.filter(Boolean).join(' ')

const variants = {
  primary: 'bg-emerald-600 text-white hover:bg-emerald-700 disabled:bg-emerald-300',
  secondary: 'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 disabled:opacity-50',
  indigo: 'bg-indigo-600 text-white hover:bg-indigo-700 disabled:bg-indigo-300',
  danger: 'bg-rose-600 text-white hover:bg-rose-700 disabled:bg-rose-300',
  ghost: 'text-slate-600 hover:bg-slate-100 disabled:opacity-50',
}
export function Button({ variant = 'primary', size = 'md', className, loading, children, ...p }) {
  const sz = size === 'sm' ? 'px-2.5 py-1.5 text-xs' : size === 'lg' ? 'px-5 py-3 text-base' : 'px-4 py-2 text-sm'
  return (
    <button {...p} disabled={p.disabled || loading}
      className={cn('inline-flex items-center justify-center gap-2 rounded-lg font-medium transition active:scale-[0.98] disabled:cursor-not-allowed', variants[variant], sz, className)}>
      {loading && <Loader2 className="h-4 w-4 animate-spin" />}{children}
    </button>
  )
}

export const Card = ({ className, children, ...p }) => <div {...p} className={cn('rounded-xl border border-slate-200 bg-white shadow-sm', className)}>{children}</div>

const tones = {
  emerald: 'bg-emerald-50 text-emerald-700', indigo: 'bg-indigo-50 text-indigo-700', rose: 'bg-rose-50 text-rose-700',
  amber: 'bg-amber-50 text-amber-700', slate: 'bg-slate-100 text-slate-600',
}
export const Badge = ({ tone = 'slate', children }) => <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium', tones[tone])}>{children}</span>

export function StatCard({ label, value, sub, icon: Icon, tone = 'emerald', onClick }) {
  return (
    <Card className={cn('p-3.5 sm:p-4', onClick && 'cursor-pointer hover:border-emerald-300')} onClick={onClick}>
      <div className="flex items-start justify-between gap-2">
        <p className="text-xs font-medium text-slate-500">{label}</p>
        {Icon && <span className={cn('rounded-lg p-1.5', tones[tone])}><Icon className="h-4 w-4" /></span>}
      </div>
      <p className="mt-1.5 truncate text-lg font-bold text-slate-900 sm:text-xl">{value}</p>
      {sub && <p className="mt-0.5 truncate text-xs text-slate-500">{sub}</p>}
    </Card>
  )
}

export function Modal({ open, onClose, title, children, wide, footer }) {
  if (!open) return null
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-900/50 sm:items-center sm:p-4" onMouseDown={(e) => e.target === e.currentTarget && onClose?.()}>
      <div className={cn('flex max-h-[92vh] w-full flex-col rounded-t-2xl bg-white shadow-xl sm:rounded-2xl', wide ? 'sm:max-w-4xl' : 'sm:max-w-lg')}>
        <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
          <h3 className="text-base font-semibold text-slate-900">{title}</h3>
          <button onClick={onClose} className="rounded-lg p-1 text-slate-500 hover:bg-slate-100"><X className="h-5 w-5" /></button>
        </div>
        <div className="overflow-y-auto px-4 py-4">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-slate-200 px-4 py-3">{footer}</div>}
      </div>
    </div>
  )
}

export const Field = ({ label, hint, className, children }) => (
  <label className={cn('block', className)}>
    <span className="mb-1 block text-xs font-medium text-slate-600">{label}</span>
    {children}
    {hint && <span className="mt-0.5 block text-xs text-slate-400">{hint}</span>}
  </label>
)

export const Spinner = ({ className }) => <div className={cn('flex justify-center py-10', className)}><Loader2 className="h-6 w-6 animate-spin text-emerald-600" /></div>
export const Empty = ({ text = 'Nothing here yet' }) => <div className="flex flex-col items-center gap-2 py-10 text-slate-400"><Inbox className="h-8 w-8" /><p className="text-sm">{text}</p></div>

export function PageHeader({ title, subtitle, children }) {
  return (
    <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
      <div><h1 className="text-xl font-bold text-slate-900">{title}</h1>{subtitle && <p className="text-sm text-slate-500">{subtitle}</p>}</div>
      <div className="flex flex-wrap items-center gap-2">{children}</div>
    </div>
  )
}

export function Pagination({ page, totalPages, onPage, total }) {
  if (!totalPages || totalPages <= 1) return total != null ? <p className="px-3 py-2 text-xs text-slate-500">{total} record(s)</p> : null
  return (
    <div className="flex items-center justify-between border-t border-slate-200 px-3 py-2 text-sm">
      <span className="text-xs text-slate-500">Page {page + 1} of {totalPages}{total != null && ` · ${total} records`}</span>
      <div className="flex gap-1">
        <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => onPage(page - 1)}><ChevronLeft className="h-4 w-4" /></Button>
        <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}><ChevronRight className="h-4 w-4" /></Button>
      </div>
    </div>
  )
}

/** Scrollable table container with sticky headers. */
export const TableWrap = ({ children, className }) => <div className={cn('max-h-[70vh] overflow-auto', className)}><table className="min-w-full divide-y divide-slate-100">{children}</table></div>

export function Tabs({ tabs, value, onChange }) {
  return (
    <div className="mb-4 flex gap-1 overflow-x-auto rounded-xl bg-slate-100 p-1">
      {tabs.map((t) => (
        <button key={t.value} onClick={() => onChange(t.value)}
          className={cn('whitespace-nowrap rounded-lg px-3 py-1.5 text-sm font-medium', value === t.value ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-600 hover:text-slate-900')}>{t.label}</button>
      ))}
    </div>
  )
}

const ToastCtx = createContext(() => {})
export const useToast = () => useContext(ToastCtx)
export function ToastProvider({ children }) {
  const [items, setItems] = useState([])
  const push = useCallback((message, type = 'success') => {
    const id = Math.random()
    setItems((x) => [...x, { id, message, type }])
    setTimeout(() => setItems((x) => x.filter((i) => i.id !== id)), type === 'error' ? 6000 : 3000)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 top-3 z-[60] flex flex-col items-center gap-2 px-3">
        {items.map((t) => (
          <div key={t.id} className={cn('pointer-events-auto flex max-w-md items-center gap-2 rounded-lg px-4 py-2.5 text-sm text-white shadow-lg', t.type === 'error' ? 'bg-rose-600' : 'bg-emerald-600')}>
            {t.type === 'error' ? <AlertCircle className="h-4 w-4 shrink-0" /> : <CheckCircle2 className="h-4 w-4 shrink-0" />}<span>{t.message}</span>
          </div>
        ))}
      </div>
    </ToastCtx.Provider>
  )
}
