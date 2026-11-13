import { useCallback, useEffect, useState } from 'react'
import { BadgeCheck, BookOpen, Pencil, Phone, Plus, Power, Trash2 } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import ProductPicker from '../components/ProductPicker.jsx'
import { Badge, Button, Card, Empty, Field, Modal, PageHeader, Spinner, TableWrap, Tabs, useToast } from '../components/ui.jsx'
import { errMsg, fmtDateTime, money } from '../utils'

const blank = { name: '', contactPerson: '', phone: '', email: '', address: '', paymentTermsDays: 0, openingBalance: 0 }

export default function Suppliers() {
  const { can } = useAuth()
  const toast = useToast()
  const [list, setList] = useState(null)
  const [edit, setEdit] = useState(null)
  const [detail, setDetail] = useState(null)
  const load = useCallback(() => api.get('/suppliers').then((r) => setList(r.data)).catch((e) => toast(errMsg(e), 'error')), []) // eslint-disable-line
  useEffect(() => { load() }, [load])
  const act = async (fn, msg) => { try { await fn(); toast(msg); load() } catch (e) { toast(errMsg(e), 'error') } }
  const total = list?.reduce((a, s) => a + Number(s.outstanding), 0) || 0

  return (
    <div>
      <PageHeader title="Suppliers" subtitle={`Total outstanding ${money(total)}`}><Button size="sm" onClick={() => setEdit({ ...blank })}><Plus className="h-4 w-4" />Add supplier</Button></PageHeader>
      {!list ? <Spinner /> : list.length === 0 ? <Card><Empty text="No suppliers yet" /></Card> : (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
          {list.map((s) => (
            <Card key={s.id} className={`p-4 ${!s.active ? 'opacity-60' : ''}`}>
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0"><h3 className="truncate font-semibold">{s.name}</h3><p className="flex items-center gap-1 text-xs text-slate-500"><Phone className="h-3 w-3" />{s.phone || '—'}{s.contactPerson && ` · ${s.contactPerson}`}</p></div>
                <div className="flex flex-col items-end gap-1">{s.approved ? <Badge tone="emerald">Approved</Badge> : <Badge tone="amber">Pending approval</Badge>}{!s.active && <Badge>Inactive</Badge>}</div>
              </div>
              <div className="mt-3 flex items-end justify-between"><div><p className="text-xs text-slate-500">Outstanding</p><p className={`text-xl font-bold ${Number(s.outstanding) > 0 ? 'text-rose-600' : 'text-slate-800'}`}>{money(s.outstanding)}</p></div><p className="text-xs text-slate-500">Terms: {s.paymentTermsDays} days</p></div>
              <div className="mt-3 flex flex-wrap gap-1.5">
                <Button size="sm" variant="indigo" onClick={() => setDetail(s)}><BookOpen className="h-3.5 w-3.5" />Ledger & products</Button>
                <Button size="sm" variant="secondary" onClick={() => setEdit({ ...blank, ...s })}><Pencil className="h-3.5 w-3.5" />Edit</Button>
                {!s.approved && can('OWNER') && <Button size="sm" onClick={() => act(() => api.post(`/suppliers/${s.id}/approve`), 'Supplier approved')}><BadgeCheck className="h-3.5 w-3.5" />Approve</Button>}
                <Button size="sm" variant="ghost" onClick={() => act(() => api.patch(`/suppliers/${s.id}/active`, null, { params: { value: !s.active } }), s.active ? 'Deactivated' : 'Activated')}><Power className="h-3.5 w-3.5" /></Button>
              </div>
            </Card>
          ))}
        </div>
      )}
      {edit && <SupplierForm initial={edit} owner={can('OWNER')} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); load() }} />}
      {detail && <Detail supplier={detail} owner={can('OWNER')} onClose={() => { setDetail(null); load() }} />}
    </div>
  )
}

function SupplierForm({ initial, owner, onClose, onSaved }) {
  const toast = useToast()
  const [v, setV] = useState(initial)
  const [busy, setBusy] = useState(false)
  const set = (k) => (e) => setV({ ...v, [k]: e.target.value })
  const save = async () => {
    setBusy(true)
    const body = { name: v.name, contactPerson: v.contactPerson || null, phone: v.phone || null, email: v.email || null, address: v.address || null, paymentTermsDays: Number(v.paymentTermsDays || 0), openingBalance: Number(v.openingBalance || 0) }
    try { await (initial.id ? api.put(`/suppliers/${initial.id}`, body) : api.post('/suppliers', body)); toast('Supplier saved'); onSaved() } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal open onClose={onClose} title={initial.id ? 'Edit supplier' : 'Add supplier'} footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button onClick={save} loading={busy} disabled={!v.name.trim()}>Save</Button></>}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Name *" className="sm:col-span-2"><input className="input" value={v.name} onChange={set('name')} /></Field>
        <Field label="Contact person"><input className="input" value={v.contactPerson || ''} onChange={set('contactPerson')} /></Field>
        <Field label="Phone"><input className="input" value={v.phone || ''} onChange={set('phone')} /></Field>
        <Field label="Email"><input className="input" type="email" value={v.email || ''} onChange={set('email')} /></Field>
        <Field label="Payment terms (days)"><input className="input" type="number" min="0" value={v.paymentTermsDays} onChange={set('paymentTermsDays')} /></Field>
        <Field label="Address" className="sm:col-span-2"><input className="input" value={v.address || ''} onChange={set('address')} /></Field>
        <Field label="Opening balance (₹)" hint={owner ? 'Amount already owed to this supplier' : 'Only the owner can set this'}><input className="input" type="number" min="0" disabled={!owner} value={v.openingBalance} onChange={set('openingBalance')} /></Field>
      </div>
      {!owner && !initial.id && <p className="mt-3 rounded-lg bg-amber-50 p-2 text-xs text-amber-700">New suppliers created by a manager need owner approval before purchase orders can be approved.</p>}
    </Modal>
  )
}

function Detail({ supplier, owner, onClose }) {
  const [tab, setTab] = useState('ledger')
  return (
    <Modal open onClose={onClose} wide title={supplier.name}>
      <Tabs value={tab} onChange={setTab} tabs={[{ value: 'ledger', label: 'Ledger' }, { value: 'products', label: 'Supplied products' }]} />
      {tab === 'ledger' ? <Ledger id={supplier.id} owner={owner} /> : <Mapping id={supplier.id} />}
    </Modal>
  )
}

const TONE = { PURCHASE: 'amber', PAYMENT: 'emerald', DEBIT_ADJUSTMENT: 'rose', CREDIT_ADJUSTMENT: 'indigo' }

function Ledger({ id, owner }) {
  const toast = useToast()
  const [st, setSt] = useState(null)
  const [form, setForm] = useState(null) // 'pay' | 'adj'
  const [v, setV] = useState({ amount: '', method: 'CASH', kind: 'DEBIT', note: '' })
  const load = useCallback(() => api.get(`/suppliers/${id}/ledger`).then((r) => setSt(r.data)), [id])
  useEffect(() => { load() }, [load])
  const submit = async () => {
    try {
      if (form === 'pay') await api.post(`/suppliers/${id}/payments`, { amount: Number(v.amount), method: v.method, note: v.note || null })
      else await api.post(`/suppliers/${id}/adjustments`, { kind: v.kind, amount: Number(v.amount), note: v.note })
      toast('Recorded'); setForm(null); setV({ amount: '', method: 'CASH', kind: 'DEBIT', note: '' }); load()
    } catch (e) { toast(errMsg(e), 'error') }
  }
  if (!st) return <Spinner />
  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2 rounded-xl bg-slate-50 p-3">
        <div><p className="text-xs text-slate-500">Outstanding balance</p><p className="text-2xl font-bold">{money(st.supplier.outstanding)}</p><p className="text-xs text-slate-400">Opening {money(st.supplier.openingBalance)} + purchases − payments ± adjustments</p></div>
        <div className="flex gap-2"><Button size="sm" onClick={() => setForm('pay')}>Record payment</Button>{owner && <Button size="sm" variant="secondary" onClick={() => setForm('adj')}>Adjustment</Button>}</div>
      </div>
      {form && (
        <div className="mb-3 grid gap-2 rounded-xl border border-slate-200 p-3 sm:grid-cols-4">
          <Field label="Amount (₹)"><input className="input" type="number" min="0" value={v.amount} onChange={(e) => setV({ ...v, amount: e.target.value })} autoFocus /></Field>
          {form === 'pay' ? <Field label="Method"><select className="input" value={v.method} onChange={(e) => setV({ ...v, method: e.target.value })}><option>CASH</option><option>UPI</option></select></Field>
            : <Field label="Kind"><select className="input" value={v.kind} onChange={(e) => setV({ ...v, kind: e.target.value })}><option value="DEBIT">Debit (we owe more)</option><option value="CREDIT">Credit (we owe less)</option></select></Field>}
          <Field label={form === 'pay' ? 'Note' : 'Reason *'} className="sm:col-span-2"><input className="input" value={v.note} onChange={(e) => setV({ ...v, note: e.target.value })} /></Field>
          <div className="flex gap-2 sm:col-span-4"><Button size="sm" onClick={submit} disabled={!v.amount || (form === 'adj' && !v.note.trim())}>Save</Button><Button size="sm" variant="ghost" onClick={() => setForm(null)}>Cancel</Button></div>
        </div>
      )}
      <TableWrap className="max-h-80 rounded-lg border border-slate-200">
        <thead><tr><th className="th">Date</th><th className="th">Type</th><th className="th">Note</th><th className="th text-right">Amount</th><th className="th text-right">Balance</th></tr></thead>
        <tbody className="divide-y divide-slate-100">
          <tr><td className="td text-slate-500" colSpan={4}>Opening balance</td><td className="td text-right">{money(st.supplier.openingBalance)}</td></tr>
          {st.rows.map((r) => <tr key={r.id}><td className="td text-slate-500">{fmtDateTime(r.createdAt)}</td><td className="td"><Badge tone={TONE[r.type]}>{r.type.replace('_', ' ')}</Badge></td><td className="td max-w-[200px] truncate text-slate-500">{r.note}</td><td className="td text-right">{money(r.amount)}</td><td className="td text-right font-medium">{money(r.balance)}</td></tr>)}
        </tbody>
      </TableWrap>
    </div>
  )
}

function Mapping({ id }) {
  const toast = useToast()
  const [rows, setRows] = useState(null)
  const [pick, setPick] = useState(null)
  const [v, setV] = useState({ supplierPrice: '', leadTimeDays: 3, preferred: false })
  const load = useCallback(() => api.get(`/suppliers/${id}/products`).then((r) => setRows(r.data)), [id])
  useEffect(() => { load() }, [load])
  const add = async () => {
    try { await api.post(`/suppliers/${id}/products`, { productId: pick.id, supplierPrice: v.supplierPrice === '' ? null : Number(v.supplierPrice), leadTimeDays: Number(v.leadTimeDays), preferred: v.preferred }); setPick(null); setV({ supplierPrice: '', leadTimeDays: 3, preferred: false }); load() } catch (e) { toast(errMsg(e), 'error') }
  }
  return (
    <div>
      <div className="mb-3 rounded-xl border border-slate-200 p-3">
        <ProductPicker onPick={setPick} placeholder="Find a product this supplier provides…" />
        {pick && (
          <div className="mt-3 grid items-end gap-2 sm:grid-cols-5">
            <p className="text-sm font-medium sm:col-span-2">{pick.name}</p>
            <Field label="Supplier price"><input className="input" type="number" min="0" value={v.supplierPrice} onChange={(e) => setV({ ...v, supplierPrice: e.target.value })} /></Field>
            <Field label="Lead time (days)"><input className="input" type="number" min="0" value={v.leadTimeDays} onChange={(e) => setV({ ...v, leadTimeDays: e.target.value })} /></Field>
            <div className="flex items-center gap-2"><label className="flex items-center gap-1 text-xs"><input type="checkbox" checked={v.preferred} onChange={(e) => setV({ ...v, preferred: e.target.checked })} />Preferred</label><Button size="sm" onClick={add}>Map</Button></div>
          </div>
        )}
      </div>
      {!rows ? <Spinner /> : rows.length === 0 ? <Empty text="No products mapped" /> : (
        <TableWrap className="rounded-lg border border-slate-200"><thead><tr><th className="th">Product</th><th className="th text-right">Price</th><th className="th text-right">Lead time</th><th className="th" /><th className="th" /></tr></thead>
          <tbody className="divide-y divide-slate-100">{rows.map((r) => <tr key={r.id}><td className="td font-medium">{r.productName}<span className="ml-2 text-xs text-slate-400">{r.sku}</span></td><td className="td text-right">{r.supplierPrice != null ? money(r.supplierPrice) : '—'}</td><td className="td text-right">{r.leadTimeDays}d</td><td className="td">{r.preferred && <Badge tone="emerald">Preferred</Badge>}</td><td className="td"><Button variant="ghost" size="sm" onClick={() => api.delete(`/suppliers/${id}/products/${r.id}`).then(load)}><Trash2 className="h-4 w-4" /></Button></td></tr>)}</tbody></TableWrap>
      )}
    </div>
  )
}
