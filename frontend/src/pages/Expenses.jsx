import { useCallback, useEffect, useState } from 'react'
import { Plus } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Badge, Button, Card, Empty, Field, Modal, PageHeader, Pagination, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, fmtDate, isoToday, money } from '../utils'

const ALL = ['TRANSPORTATION', 'ELECTRICITY', 'RENT', 'STAFF_SALARY', 'MAINTENANCE', 'MISCELLANEOUS']
const STAFF_OK = ['TRANSPORTATION', 'MAINTENANCE', 'MISCELLANEOUS']
const label = (c) => c.replace('_', ' ').toLowerCase().replace(/^\w/, (x) => x.toUpperCase())

export default function Expenses() {
  const { can } = useAuth()
  const toast = useToast()
  const mgmt = can('OWNER', 'MANAGER')
  const [data, setData] = useState(null)
  const [f, setF] = useState({ category: '', from: '', to: '' })
  const [page, setPage] = useState(0)
  const [open, setOpen] = useState(false)
  const load = useCallback(() => api.get('/expenses', { params: { page, size: 20, category: f.category || undefined, from: f.from || undefined, to: f.to || undefined } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error')), [page, f]) // eslint-disable-line
  useEffect(() => { load() }, [load])
  const total = data?.content.reduce((a, e) => a + Number(e.amount), 0) || 0

  return (
    <div>
      <PageHeader title="Expenses" subtitle={mgmt ? 'Operating expenses (kept separate from stock purchases)' : "Today's expenses you recorded"}><Button size="sm" onClick={() => setOpen(true)}><Plus className="h-4 w-4" />Add expense</Button></PageHeader>
      <Card>
        {mgmt && <div className="grid gap-2 border-b border-slate-200 p-3 sm:grid-cols-3"><select className="input" value={f.category} onChange={(e) => { setPage(0); setF({ ...f, category: e.target.value }) }}><option value="">All categories</option>{ALL.map((c) => <option key={c} value={c}>{label(c)}</option>)}</select><input className="input" type="date" value={f.from} onChange={(e) => { setPage(0); setF({ ...f, from: e.target.value }) }} /><input className="input" type="date" value={f.to} onChange={(e) => { setPage(0); setF({ ...f, to: e.target.value }) }} /></div>}
        {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No expenses" /> : (
          <TableWrap>
            <thead><tr><th className="th">Date</th><th className="th">Category</th><th className="th">Description</th><th className="th">Paid via</th><th className="th">By</th><th className="th text-right">Amount</th></tr></thead>
            <tbody className="divide-y divide-slate-100">{data.content.map((e) => <tr key={e.id}><td className="td">{fmtDate(e.expenseDate)}</td><td className="td"><Badge tone="amber">{label(e.category)}</Badge></td><td className="td max-w-[260px] truncate">{e.description}</td><td className="td">{e.method}</td><td className="td text-slate-500">{e.recordedBy}</td><td className="td text-right font-medium">{money(e.amount)}</td></tr>)}</tbody>
          </TableWrap>
        )}
        {data && <p className="border-t border-slate-100 px-3 pt-2 text-right text-sm font-semibold">Page total {money(total)}</p>}
        {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
      </Card>
      {open && <Form categories={mgmt ? ALL : STAFF_OK} mgmt={mgmt} onClose={() => setOpen(false)} onSaved={() => { setOpen(false); load() }} />}
    </div>
  )
}

function Form({ categories, mgmt, onClose, onSaved }) {
  const toast = useToast()
  const [v, setV] = useState({ category: categories[0], amount: '', method: 'CASH', description: '', expenseDate: isoToday() })
  const [busy, setBusy] = useState(false)
  const set = (k) => (e) => setV({ ...v, [k]: e.target.value })
  const save = async () => {
    setBusy(true)
    try { await api.post('/expenses', { ...v, amount: Number(v.amount), expenseDate: mgmt ? v.expenseDate : null }); toast('Expense recorded'); onSaved() } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal open onClose={onClose} title="Add expense" footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button onClick={save} loading={busy} disabled={!v.amount || !v.description.trim()}>Save</Button></>}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Category"><select className="input" value={v.category} onChange={set('category')}>{categories.map((c) => <option key={c} value={c}>{label(c)}</option>)}</select></Field>
        <Field label="Amount (₹)"><input className="input" type="number" inputMode="decimal" min="0" value={v.amount} onChange={set('amount')} autoFocus /></Field>
        <Field label="Paid via"><select className="input" value={v.method} onChange={set('method')}><option>CASH</option><option>UPI</option></select></Field>
        {mgmt && <Field label="Date"><input className="input" type="date" value={v.expenseDate} onChange={set('expenseDate')} /></Field>}
        <Field label="Description" className="sm:col-span-2"><input className="input" value={v.description} onChange={set('description')} placeholder="What was this for?" /></Field>
      </div>
      {!mgmt && <p className="mt-3 rounded-lg bg-slate-50 p-2 text-xs text-slate-500">Cash expenses reduce the expected cash of your open shift.</p>}
    </Modal>
  )
}
