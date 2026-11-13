import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, CheckCircle2, Lock, Unlock } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Badge, Button, Card, Empty, Field, PageHeader, Pagination, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, fmtDateTime, money } from '../utils'

export default function Shifts() {
  const { can } = useAuth()
  const toast = useToast()
  const mgmt = can('OWNER', 'MANAGER')
  const [cur, setCur] = useState(undefined)
  const [hist, setHist] = useState(null)
  const [page, setPage] = useState(0)
  const [opening, setOpening] = useState('')
  const [counted, setCounted] = useState('')
  const [notes, setNotes] = useState('')
  const [busy, setBusy] = useState(false)

  const loadCur = useCallback(() => api.get('/shifts/current').then((r) => setCur(r.data || null)).catch(() => setCur(null)), [])
  const loadHist = useCallback(() => api.get('/shifts', { params: { page, size: 15 } }).then((r) => setHist(r.data)).catch(() => {}), [page])
  useEffect(() => { loadCur() }, [loadCur])
  useEffect(() => { loadHist() }, [loadHist])

  const open = async () => {
    if (opening === '' || Number(opening) < 0) return toast('Enter the opening cash to start the shift', 'error')
    if (Number(opening) === 0 && !window.confirm('Opening cash is 0. Is the drawer really empty?')) return
    setBusy(true); try { setCur((await api.post('/shifts/open', { openingCash: Number(opening || 0) })).data); toast('Shift opened'); loadHist() } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) } }
  const close = async () => {
    if (!window.confirm('Close your shift? This cannot be undone.')) return
    setBusy(true)
    try { const { data } = await api.post('/shifts/close', { countedCash: Number(counted), notes: notes || null }); setCur(null); setCounted(''); setNotes(''); toast(Number(data.variance) === 0 ? 'Shift closed — cash matches. You can now sign out' : `Shift closed — variance ${money(data.variance)}`, Number(data.variance) === 0 ? 'success' : 'error'); loadHist() } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }

  return (
    <div>
      <PageHeader title="Shifts & cash reconciliation" subtitle="Expected cash = opening + cash sales − cash expenses − cash paid to suppliers" />
      {cur === undefined ? <Spinner /> : cur ? (
        <Card className="mb-4 p-4">
          <div className="mb-3 flex items-center justify-between"><h3 className="flex items-center gap-2 font-semibold text-emerald-700"><Unlock className="h-4 w-4" />Shift open since {fmtDateTime(cur.openedAt)}</h3><Badge tone="emerald">OPEN</Badge></div>
          <div className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
            <div className="rounded-lg bg-slate-50 p-3"><p className="text-xs text-slate-500">Opening cash</p><p className="font-semibold">{money(cur.openingCash)}</p></div>
            <div className="rounded-lg bg-slate-50 p-3"><p className="text-xs text-slate-500">Cash sales</p><p className="font-semibold">{money(cur.cashSales)}</p></div>
            <div className="rounded-lg bg-slate-50 p-3"><p className="text-xs text-slate-500">Cash expenses</p><p className="font-semibold">{money(cur.cashExpenses)}</p></div>
            <div className="rounded-lg bg-slate-50 p-3"><p className="text-xs text-slate-500">Supplier cash paid</p><p className="font-semibold">{money(cur.supplierCashPayments)}</p></div>
          </div>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            <Field label="Closing cash counted (₹) *" hint="Required. Count the drawer — you can sign out only after closing the shift"><input className="input text-lg" type="number" inputMode="decimal" min="0" value={counted} onChange={(e) => setCounted(e.target.value)} /></Field>
            <Field label="Notes" className="sm:col-span-2"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Optional" /></Field>
          </div>
          <Button className="mt-3" variant="danger" loading={busy} disabled={counted === ''} onClick={close}><Lock className="h-4 w-4" />Close shift</Button>
        </Card>
      ) : (
        <Card className="mb-4 flex flex-wrap items-end gap-3 p-4">
          <Field label="Opening cash in drawer (₹) *" hint="Required to open a shift" className="w-56"><input className="input" type="number" inputMode="decimal" min="0" placeholder="Enter amount" value={opening} onChange={(e) => setOpening(e.target.value)} /></Field>
          <Button loading={busy} disabled={opening === ''} onClick={open}><Unlock className="h-4 w-4" />Open shift</Button>
        </Card>
      )}

      <Card>
        <h3 className="border-b border-slate-200 p-3 text-sm font-semibold">{mgmt ? 'All shifts' : 'My shifts'}</h3>
        {!hist ? <Spinner /> : hist.content.length === 0 ? <Empty text="No shifts yet" /> : (
          <TableWrap>
            <thead><tr><th className="th">Staff</th><th className="th">Opened</th><th className="th">Closed</th><th className="th text-right">Opening</th><th className="th text-right">Cash sales</th><th className="th text-right">Expected</th><th className="th text-right">Counted</th><th className="th text-right">Variance</th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {hist.content.map((s) => {
                const closed = s.status === 'CLOSED', v = Number(s.variance || 0)
                return <tr key={s.id}><td className="td font-medium">{s.fullName}</td><td className="td text-slate-500">{fmtDateTime(s.openedAt)}</td><td className="td text-slate-500">{closed ? fmtDateTime(s.closedAt) : <Badge tone="emerald">Open</Badge>}</td><td className="td text-right">{money(s.openingCash)}</td><td className="td text-right">{money(s.cashSales)}</td><td className="td text-right">{closed || mgmt ? money(s.expectedCash) : '—'}</td><td className="td text-right">{closed ? money(s.countedCash) : '—'}</td>
                  <td className="td text-right">{closed ? (v === 0 ? <span className="inline-flex items-center gap-1 text-emerald-600"><CheckCircle2 className="h-3.5 w-3.5" />Balanced</span> : <span className="inline-flex items-center gap-1 font-semibold text-rose-600"><AlertTriangle className="h-3.5 w-3.5" />{v > 0 ? '+' : ''}{money(v)}</span>) : '—'}</td></tr>
              })}
            </tbody>
          </TableWrap>
        )}
        {hist && <Pagination page={hist.number} totalPages={hist.totalPages} total={hist.totalElements} onPage={setPage} />}
      </Card>
    </div>
  )
}
