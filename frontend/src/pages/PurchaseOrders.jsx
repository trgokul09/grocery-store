import { useCallback, useEffect, useState } from 'react'
import { Check, PackageCheck, Plus, Send, Trash2, X, Undo2 } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import ProductPicker from '../components/ProductPicker.jsx'
import { Badge, Button, Card, Empty, Field, Modal, PageHeader, Pagination, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, fmtDate, fmtDateTime, money, num, unitLabel } from '../utils'

const STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED']
const TONE = { DRAFT: 'slate', PENDING_APPROVAL: 'amber', APPROVED: 'indigo', PARTIALLY_RECEIVED: 'amber', RECEIVED: 'emerald', CANCELLED: 'rose' }
export const StatusBadge = ({ s }) => <Badge tone={TONE[s]}>{s.replace('_', ' ')}</Badge>

export default function PurchaseOrders() {
  const { can } = useAuth()
  const toast = useToast()
  const mgmt = can('OWNER', 'MANAGER')
  const [data, setData] = useState(null)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const [form, setForm] = useState(null)
  const [openId, setOpenId] = useState(null)
  const load = useCallback(() => api.get('/purchase-orders', { params: { page, size: 15, status: status || undefined } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error')), [page, status]) // eslint-disable-line
  useEffect(() => { load() }, [load])

  return (
    <div>
      <PageHeader title="Purchase orders" subtitle={mgmt ? 'Raise, approve and receive stock' : 'Deliveries awaiting receipt'}>
        {mgmt && <Button size="sm" onClick={() => setForm({})}><Plus className="h-4 w-4" />New PO</Button>}
      </PageHeader>
      <Card>
        {mgmt && <div className="border-b border-slate-200 p-3"><select className="input max-w-xs" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value) }}><option value="">All statuses</option>{STATUSES.map((s) => <option key={s}>{s}</option>)}</select></div>}
        {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No purchase orders" /> : (
          <TableWrap>
            <thead><tr><th className="th">PO</th><th className="th">Supplier</th><th className="th">Created</th><th className="th">Expected</th><th className="th text-right">Total</th><th className="th">Status</th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {data.content.map((p) => <tr key={p.id} className="cursor-pointer hover:bg-slate-50" onClick={() => setOpenId(p.id)}><td className="td font-medium text-indigo-600">{p.poNumber}</td><td className="td">{p.supplierName}</td><td className="td text-slate-500">{fmtDateTime(p.createdAt)}</td><td className="td">{fmtDate(p.expectedDate)}</td><td className="td text-right">{money(p.total)}</td><td className="td"><StatusBadge s={p.status} /></td></tr>)}
            </tbody>
          </TableWrap>
        )}
        {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
      </Card>
      {form && <PoForm initial={form} onClose={() => setForm(null)} onSaved={(po) => { setForm(null); load(); setOpenId(po.id) }} />}
      {openId && <PoDetail id={openId} onClose={() => { setOpenId(null); load() }} onEdit={(po) => { setOpenId(null); setForm(po) }} />}
    </div>
  )
}

function PoForm({ initial, onClose, onSaved }) {
  const toast = useToast()
  const [suppliers, setSuppliers] = useState([])
  const [supplierId, setSupplierId] = useState(initial.supplierId || '')
  const [expected, setExpected] = useState(initial.expectedDate || '')
  const [notes, setNotes] = useState(initial.notes || '')
  const [items, setItems] = useState((initial.items || []).map((i) => ({ productId: i.productId, name: i.productName, unit: i.unit, quantity: i.orderedQty, unitCost: i.unitCost })))
  const [busy, setBusy] = useState(false)
  useEffect(() => { api.get('/suppliers').then((r) => setSuppliers(r.data.filter((s) => s.active))) }, [])

  const add = (p) => setItems((x) => (x.some((i) => i.productId === p.id) ? x : [...x, { productId: p.id, name: p.name, unit: p.unit, quantity: 1, unitCost: p.purchasePrice }]))
  const upd = (idx, k, val) => setItems((x) => x.map((i, n) => (n === idx ? { ...i, [k]: val } : i)))
  const total = items.reduce((a, i) => a + Number(i.quantity || 0) * Number(i.unitCost || 0), 0)

  const save = async () => {
    setBusy(true)
    const body = { supplierId: Number(supplierId), expectedDate: expected || null, notes: notes || null, items: items.map((i) => ({ productId: i.productId, quantity: Number(i.quantity), unitCost: Number(i.unitCost) })) }
    try { const { data } = await (initial.id ? api.put(`/purchase-orders/${initial.id}`, body) : api.post('/purchase-orders', body)); toast('Purchase order saved'); onSaved(data) } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }

  return (
    <Modal open onClose={onClose} wide title={initial.id ? `Edit ${initial.poNumber}` : 'New purchase order'} footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button onClick={save} loading={busy} disabled={!supplierId || !items.length || items.some((i) => !(Number(i.quantity) > 0))}>Save as draft</Button></>}>
      <div className="grid gap-3 sm:grid-cols-3">
        <Field label="Supplier *"><select className="input" value={supplierId} onChange={(e) => setSupplierId(e.target.value)}><option value="">Select…</option>{suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}{!s.approved ? ' (unapproved)' : ''}</option>)}</select></Field>
        <Field label="Expected date"><input className="input" type="date" value={expected} onChange={(e) => setExpected(e.target.value)} /></Field>
        <Field label="Notes"><input className="input" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
      </div>
      <div className="mt-4"><ProductPicker onPick={add} placeholder="Add product to this order…" /></div>
      {items.length === 0 ? <Empty text="No items yet" /> : (
        <TableWrap className="mt-3 rounded-lg border border-slate-200">
          <thead><tr><th className="th">Product</th><th className="th w-28">Qty</th><th className="th w-32">Unit cost</th><th className="th text-right">Line</th><th className="th" /></tr></thead>
          <tbody className="divide-y divide-slate-100">{items.map((i, n) => <tr key={i.productId}><td className="td font-medium">{i.name}</td><td className="td"><input className="input" type="number" min="0" value={i.quantity} onChange={(e) => upd(n, 'quantity', e.target.value)} /></td><td className="td"><input className="input" type="number" min="0" step="0.01" value={i.unitCost} onChange={(e) => upd(n, 'unitCost', e.target.value)} /></td><td className="td text-right">{money(Number(i.quantity || 0) * Number(i.unitCost || 0))}</td><td className="td"><Button variant="ghost" size="sm" onClick={() => setItems(items.filter((_, k) => k !== n))}><Trash2 className="h-4 w-4" /></Button></td></tr>)}</tbody>
        </TableWrap>
      )}
      <p className="mt-3 text-right text-sm font-semibold">Total {money(total)}</p>
    </Modal>
  )
}

function PoDetail({ id, onClose, onEdit }) {
  const { can } = useAuth()
  const toast = useToast()
  const [po, setPo] = useState(null)
  const [recv, setRecv] = useState(false)
  const load = useCallback(() => api.get(`/purchase-orders/${id}`).then((r) => setPo(r.data)).catch((e) => { toast(errMsg(e), 'error'); onClose() }), [id]) // eslint-disable-line
  useEffect(() => { load() }, [load])
  const act = async (path, msg) => { try { setPo((await api.post(`/purchase-orders/${id}/${path}`)).data); toast(msg) } catch (e) { toast(errMsg(e), 'error') } }
  const mgmt = can('OWNER', 'MANAGER'), owner = can('OWNER')

  return (
    <Modal open onClose={onClose} wide title={po ? `${po.poNumber} · ${po.supplierName}` : 'Purchase order'}>
      {!po ? <Spinner /> : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2"><StatusBadge s={po.status} /><span className="text-sm text-slate-500">Expected {fmtDate(po.expectedDate)} · Total {money(po.total)}</span>{po.notes && <span className="text-xs text-slate-400">— {po.notes}</span>}</div>
          <div className="flex flex-wrap gap-2">
            {mgmt && po.status === 'DRAFT' && <><Button size="sm" variant="secondary" onClick={() => onEdit(po)}>Edit</Button><Button size="sm" onClick={() => act('submit', 'Submitted for approval')}><Send className="h-3.5 w-3.5" />Submit for approval</Button></>}
            {owner && po.status === 'PENDING_APPROVAL' && <><Button size="sm" onClick={() => act('approve', 'Approved')}><Check className="h-3.5 w-3.5" />Approve</Button><Button size="sm" variant="secondary" onClick={() => act('reject', 'Sent back to draft')}><Undo2 className="h-3.5 w-3.5" />Reject</Button></>}
            {owner && po.status === 'DRAFT' && <Button size="sm" variant="indigo" onClick={async () => { await act('submit', 'Submitted'); await act('approve', 'Submitted & approved') }}>Submit & approve</Button>}
            {['APPROVED', 'PARTIALLY_RECEIVED'].includes(po.status) && <Button size="sm" onClick={() => setRecv(true)}><PackageCheck className="h-3.5 w-3.5" />Receive delivery</Button>}
            {mgmt && ['DRAFT', 'PENDING_APPROVAL', 'APPROVED'].includes(po.status) && <Button size="sm" variant="danger" onClick={() => window.confirm('Cancel this purchase order?') && act('cancel', 'Cancelled')}><X className="h-3.5 w-3.5" />Cancel</Button>}
          </div>
          <TableWrap className="rounded-lg border border-slate-200">
            <thead><tr><th className="th">Product</th><th className="th text-right">Ordered</th><th className="th text-right">Received</th><th className="th text-right">Damaged</th><th className="th text-right">Unit cost</th></tr></thead>
            <tbody className="divide-y divide-slate-100">{po.items.map((i) => <tr key={i.id}><td className="td font-medium">{i.productName}</td><td className="td text-right">{num(i.orderedQty)} {unitLabel(i.unit)}</td><td className="td text-right">{num(i.receivedQty)}</td><td className="td text-right text-rose-600">{Number(i.damagedQty) > 0 ? num(i.damagedQty) : '—'}</td><td className="td text-right">{money(i.unitCost)}</td></tr>)}</tbody>
          </TableWrap>
          {po.receipts.length > 0 && (
            <div><h4 className="mb-2 text-sm font-semibold">Receipts</h4>
              {po.receipts.map((r) => <div key={r.id} className="mb-2 rounded-lg bg-slate-50 p-3 text-sm"><div className="flex flex-wrap justify-between gap-2"><span>{fmtDateTime(r.receivedAt)} · {r.receivedBy}</span><span>Value <b>{money(r.totalValue)}</b> · Paid <b>{money(r.paidAmount)}</b>{r.paymentMethod && ` (${r.paymentMethod})`}</span></div><p className="text-xs text-slate-500">{r.lines.map((l) => `${l.productName}: ${num(l.receivedQty)}${Number(l.damagedQty) > 0 ? ` (${num(l.damagedQty)} damaged)` : ''}`).join(' · ')}</p></div>)}
            </div>
          )}
        </div>
      )}
      {recv && po && <ReceiveModal po={po} canPay={mgmt} onClose={() => setRecv(false)} onDone={(p) => { setRecv(false); setPo(p) }} />}
    </Modal>
  )
}

function ReceiveModal({ po, canPay, onClose, onDone }) {
  const toast = useToast()
  const [lines, setLines] = useState(po.items.map((i) => ({ itemId: i.id, name: i.productName, remaining: Number(i.orderedQty) - Number(i.receivedQty), cost: Number(i.unitCost), received: '', damaged: '' })))
  const [paid, setPaid] = useState('')
  const [method, setMethod] = useState('CASH')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const upd = (n, k, v) => setLines((x) => x.map((l, i) => (i === n ? { ...l, [k]: v } : l)))
  const value = lines.reduce((a, l) => a + Math.max(Number(l.received || 0) - Number(l.damaged || 0), 0) * l.cost, 0)

  const submit = async () => {
    setBusy(true)
    try {
      const { data } = await api.post(`/purchase-orders/${po.id}/receive`, {
        lines: lines.filter((l) => Number(l.received) > 0 || Number(l.damaged) > 0).map((l) => ({ itemId: l.itemId, receivedQty: Number(l.received || 0), damagedQty: Number(l.damaged || 0) })),
        paidAmount: paid ? Number(paid) : 0, paymentMethod: paid ? method : null, note: note || null,
      })
      toast('Delivery received — stock updated'); onDone(data)
    } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }

  return (
    <Modal open onClose={onClose} wide title="Receive delivery" footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button onClick={submit} loading={busy} disabled={!lines.some((l) => Number(l.received) > 0)}>Confirm receipt</Button></>}>
      <p className="mb-3 text-sm text-slate-500">Enter what physically arrived. Damaged units are part of “received” but are not added to stock or billed.</p>
      <TableWrap className="rounded-lg border border-slate-200">
        <thead><tr><th className="th">Product</th><th className="th text-right">Pending</th><th className="th w-28">Received</th><th className="th w-28">Damaged</th></tr></thead>
        <tbody className="divide-y divide-slate-100">{lines.map((l, n) => <tr key={l.itemId}><td className="td font-medium">{l.name}</td><td className="td text-right">{num(l.remaining)}</td><td className="td"><input className="input" type="number" min="0" max={l.remaining} value={l.received} disabled={l.remaining <= 0} onChange={(e) => upd(n, 'received', e.target.value)} /></td><td className="td"><input className="input" type="number" min="0" value={l.damaged} disabled={l.remaining <= 0} onChange={(e) => upd(n, 'damaged', e.target.value)} /></td></tr>)}</tbody>
      </TableWrap>
      <p className="mt-3 text-right text-sm">Payable for this delivery: <b>{money(value)}</b></p>
      {canPay ? (
        <div className="mt-3 grid gap-3 rounded-xl border border-slate-200 p-3 sm:grid-cols-3">
          <Field label="Pay now (₹)" hint="0 = full credit, partial = part-payment"><input className="input" type="number" min="0" max={value} value={paid} onChange={(e) => setPaid(e.target.value)} /></Field>
          <Field label="Method"><select className="input" value={method} onChange={(e) => setMethod(e.target.value)}><option>CASH</option><option>UPI</option></select></Field>
          <div className="flex items-end"><Button variant="secondary" size="sm" onClick={() => setPaid(value.toFixed(2))}>Pay in full</Button></div>
        </div>
      ) : <p className="mt-3 rounded-lg bg-slate-50 p-2 text-xs text-slate-500">The owner or manager will settle the supplier payment.</p>}
      <Field label="Note" className="mt-3"><input className="input" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Invoice #, driver name" /></Field>
    </Modal>
  )
}
