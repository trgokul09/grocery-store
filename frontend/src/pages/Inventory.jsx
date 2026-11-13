import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, ArrowDownRight, ArrowUpRight, SlidersHorizontal } from 'lucide-react'
import api from '../api'
import { Badge, Button, Card, Empty, Field, Modal, PageHeader, Pagination, Spinner, TableWrap, Tabs, useToast } from '../components/ui.jsx'
import { errMsg, fmtDate, fmtDateTime, money, num, unitLabel } from '../utils'

const TYPES = ['SALE', 'PURCHASE_RECEIPT', 'MANUAL_ADD', 'MANUAL_DEDUCT', 'DAMAGE', 'RETURN']
const ADJ = [
  ['INCREASE', 'Increase stock (manual add)'], ['DECREASE', 'Decrease stock (manual deduct)'], ['DAMAGE', 'Damaged / expired goods'],
  ['RETURN', 'Approved customer return'], ['AUDIT', 'Audit — set to physically counted quantity'],
]
const INC = ['PURCHASE_RECEIPT', 'MANUAL_ADD', 'RETURN']

export default function Inventory() {
  const [tab, setTab] = useState('stock')
  const [adj, setAdj] = useState(null)
  const [refresh, setRefresh] = useState(0)
  return (
    <div>
      <PageHeader title="Inventory" subtitle="Stock is updated by sales and receipts; use adjustments for corrections" />
      <Tabs value={tab} onChange={setTab} tabs={[{ value: 'stock', label: 'Stock levels' }, { value: 'movements', label: 'Stock movements' }]} />
      {tab === 'stock' ? <Stock key={refresh} onAdjust={setAdj} /> : <Movements key={refresh} />}
      {adj && <AdjustModal product={adj} onClose={() => setAdj(null)} onDone={() => { setAdj(null); setRefresh((x) => x + 1) }} />}
    </div>
  )
}

function Stock({ onAdjust }) {
  const toast = useToast()
  const [data, setData] = useState(null)
  const [q, setQ] = useState('')
  const [low, setLow] = useState(false)
  const [page, setPage] = useState(0)
  const load = useCallback(() => api.get('/products', { params: { page, size: 20, q: q || undefined, lowStockOnly: low || undefined, active: true, sort: low ? 'currentStock' : 'name' } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error')), [page, q, low]) // eslint-disable-line
  useEffect(() => { const t = setTimeout(load, 200); return () => clearTimeout(t) }, [load])
  const value = data?.content.reduce((a, p) => a + Number(p.currentStock) * Number(p.purchasePrice), 0) || 0
  return (
    <Card>
      <div className="flex flex-wrap items-center gap-3 border-b border-slate-200 p-3">
        <input className="input max-w-xs" placeholder="Search product" value={q} onChange={(e) => { setPage(0); setQ(e.target.value) }} />
        <label className="flex items-center gap-2 text-sm text-slate-600"><input type="checkbox" checked={low} onChange={(e) => { setPage(0); setLow(e.target.checked) }} />Low stock only</label>
        <span className="ml-auto text-xs text-slate-500">Page value {money(value)}</span>
      </div>
      {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No products" /> : (
        <TableWrap>
          <thead><tr><th className="th">Product</th><th className="th">SKU</th><th className="th text-right">Stock</th><th className="th text-right">Min</th><th className="th">Expiry</th><th className="th" /></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {data.content.map((p) => (
              <tr key={p.id}>
                <td className="td font-medium">{p.name}</td><td className="td text-slate-500">{p.sku}</td>
                <td className="td text-right">{p.lowStock ? <Badge tone="rose"><AlertTriangle className="mr-1 h-3 w-3" />{num(p.currentStock)} {unitLabel(p.unit)}</Badge> : <>{num(p.currentStock)} {unitLabel(p.unit)}</>}</td>
                <td className="td text-right text-slate-500">{num(p.minStockLevel)}</td><td className="td">{fmtDate(p.expiryDate)}</td>
                <td className="td"><Button variant="secondary" size="sm" onClick={() => onAdjust(p)}><SlidersHorizontal className="h-3.5 w-3.5" />Adjust</Button></td>
              </tr>
            ))}
          </tbody>
        </TableWrap>
      )}
      {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
    </Card>
  )
}

function Movements() {
  const toast = useToast()
  const [data, setData] = useState(null)
  const [f, setF] = useState({ type: '', from: '', to: '' })
  const [page, setPage] = useState(0)
  useEffect(() => {
    api.get('/inventory/movements', { params: { page, size: 25, type: f.type || undefined, from: f.from || undefined, to: f.to || undefined } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error'))
  }, [page, f]) // eslint-disable-line
  return (
    <Card>
      <div className="grid gap-2 border-b border-slate-200 p-3 sm:grid-cols-3">
        <select className="input" value={f.type} onChange={(e) => { setPage(0); setF({ ...f, type: e.target.value }) }}><option value="">All movement types</option>{TYPES.map((t) => <option key={t}>{t}</option>)}</select>
        <input className="input" type="date" value={f.from} onChange={(e) => { setPage(0); setF({ ...f, from: e.target.value }) }} />
        <input className="input" type="date" value={f.to} onChange={(e) => { setPage(0); setF({ ...f, to: e.target.value }) }} />
      </div>
      {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No movements" /> : (
        <TableWrap>
          <thead><tr><th className="th">When</th><th className="th">Product</th><th className="th">Type</th><th className="th text-right">Qty</th><th className="th text-right">Before → After</th><th className="th">By</th><th className="th">Reason / ref</th></tr></thead>
          <tbody className="divide-y divide-slate-100">
            {data.content.map((m) => (
              <tr key={m.id}>
                <td className="td text-slate-500">{fmtDateTime(m.createdAt)}</td><td className="td font-medium">{m.productName}</td>
                <td className="td"><Badge tone={INC.includes(m.type) ? 'emerald' : 'amber'}>{m.type}</Badge></td>
                <td className="td text-right">{INC.includes(m.type) ? <ArrowUpRight className="mr-1 inline h-3.5 w-3.5 text-emerald-600" /> : <ArrowDownRight className="mr-1 inline h-3.5 w-3.5 text-rose-600" />}{num(m.quantity)}</td>
                <td className="td text-right text-slate-500">{num(m.previousStock)} → <b className="text-slate-800">{num(m.newStock)}</b></td>
                <td className="td">{m.username || '—'}</td><td className="td max-w-[260px] truncate text-slate-500" title={m.reason}>{m.reason || (m.refType ? `${m.refType} #${m.refId ?? ''}` : '')}</td>
              </tr>
            ))}
          </tbody>
        </TableWrap>
      )}
      {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
    </Card>
  )
}

function AdjustModal({ product, onClose, onDone }) {
  const toast = useToast()
  const [type, setType] = useState('INCREASE')
  const [quantity, setQuantity] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const submit = async () => {
    setBusy(true)
    try {
      const { data } = await api.post('/inventory/adjust', { productId: product.id, type, quantity: Number(quantity), reason })
      toast(data?.message || 'Stock adjusted'); onDone()
    } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal open onClose={onClose} title={`Adjust stock — ${product.name}`} footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button onClick={submit} loading={busy} disabled={quantity === '' || reason.trim().length < 3}>Apply</Button></>}>
      <p className="mb-3 rounded-lg bg-slate-50 p-2 text-sm text-slate-600">System stock: <b>{num(product.currentStock)} {unitLabel(product.unit)}</b></p>
      <div className="space-y-3">
        <Field label="Adjustment type"><select className="input" value={type} onChange={(e) => setType(e.target.value)}>{ADJ.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></Field>
        <Field label={type === 'AUDIT' ? 'Counted quantity' : 'Quantity'}><input className="input" type="number" inputMode="decimal" min="0" value={quantity} onChange={(e) => setQuantity(e.target.value)} autoFocus /></Field>
        <Field label="Reason *" hint="Required — saved in the stock movement and audit log"><input className="input" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. Cartons damaged in transit" /></Field>
      </div>
    </Modal>
  )
}
