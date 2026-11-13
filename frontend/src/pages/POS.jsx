import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Banknote, CheckCircle2, Minus, Plus, Printer, QrCode, ScanBarcode, Search, ShoppingCart, Trash2, Timer, Smartphone, SplitSquareHorizontal } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import Receipt from '../components/Receipt.jsx'
import { Badge, Button, Card, Field, Modal, useToast, cn } from '../components/ui.jsx'
import { calcLine, errMsg, isCountable, money, num, unitLabel } from '../utils'

export default function POS() {
  const { user } = useAuth()
  const toast = useToast()
  const [shift, setShift] = useState(undefined) // undefined = loading, null = none
  const [openingCash, setOpeningCash] = useState('')
  const [quick, setQuick] = useState([])
  const [q, setQ] = useState('')
  const [results, setResults] = useState([])
  const [cart, setCart] = useState([]) // {product, qty, discount}
  const [customer, setCustomer] = useState('')
  const [payOpen, setPayOpen] = useState(false)
  const [done, setDone] = useState(null)
  const searchRef = useRef(null)

  const loadShift = useCallback(() => api.get('/shifts/current').then((r) => setShift(r.data || null)).catch(() => setShift(null)), [])
  const loadQuick = useCallback(() => api.get('/pos/quick-items', { params: { limit: 12 } }).then((r) => setQuick(r.data)).catch(() => {}), [])
  useEffect(() => { loadShift(); loadQuick() }, [loadShift, loadQuick])

  // live search
  useEffect(() => {
    if (!q.trim()) { setResults([]); return }
    const t = setTimeout(() => api.get('/products', { params: { q, active: true, size: 8 } }).then((r) => setResults(r.data.content)).catch(() => {}), 150)
    return () => clearTimeout(t)
  }, [q])

  const add = useCallback((product, qty = 1) => {
    if (!product.active) return toast('Product is inactive', 'error')
    if (Number(product.currentStock) <= 0) return toast(`${product.name} is out of stock`, 'error')
    setCart((c) => {
      const i = c.findIndex((x) => x.product.id === product.id)
      if (i >= 0) { const n = [...c]; n[i] = { ...n[i], qty: Number(n[i].qty) + qty }; return n }
      return [...c, { product, qty, discount: '' }]
    })
    setQ(''); setResults([])
    searchRef.current?.focus()
  }, [toast])

  const byBarcode = useCallback(async (code) => {
    try { const { data } = await api.get(`/products/barcode/${encodeURIComponent(code)}`); add(data); return true } catch { return false }
  }, [add])

  // Keyboard-wedge barcode scanner: fast burst of keys ending in Enter while no field is focused
  useEffect(() => {
    let buf = '', last = 0
    const onKey = (e) => {
      const tag = document.activeElement?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || payOpen || done) return
      const now = Date.now()
      if (now - last > 80) buf = ''
      last = now
      if (e.key === 'Enter') { if (buf.length >= 4) { byBarcode(buf).then((ok) => !ok && toast(`No product with barcode ${buf}`, 'error')) } buf = '' }
      else if (e.key.length === 1) buf += e.key
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [byBarcode, payOpen, done, toast])

  const onSearchEnter = async (e) => {
    if (e.key !== 'Enter' || !q.trim()) return
    if (await byBarcode(q.trim())) return           // scanner typed into the box
    if (results[0]) add(results[0])
  }

  const lines = useMemo(() => cart.map((l) => ({ ...l, calc: calcLine(l.product.sellingPrice, l.qty, l.discount, 0) })), [cart])
  const totals = useMemo(() => lines.reduce((a, l) => ({ gross: a.gross + l.calc.gross, discount: a.discount + l.calc.discount, tax: a.tax + l.calc.tax, total: a.total + l.calc.total }), { gross: 0, discount: 0, tax: 0, total: 0 }), [lines])
  const total = Math.round(totals.total * 100) / 100

  const setQty = (id, v) => setCart((c) => c.map((l) => (l.product.id === id ? { ...l, qty: v } : l)))
  const bump = (id, d) => setCart((c) => c.map((l) => (l.product.id === id ? { ...l, qty: Math.max(isCountable(l.product.unit) ? 1 : 0.001, Number(l.qty) + d) } : l)))
  const setDisc = (id, v) => setCart((c) => c.map((l) => (l.product.id === id ? { ...l, discount: v } : l)))
  const remove = (id) => setCart((c) => c.filter((l) => l.product.id !== id))

  const openShift = async () => {
    if (openingCash === '' || Number(openingCash) < 0) return toast('Enter the cash in your drawer to open the shift', 'error')
    if (Number(openingCash) === 0 && !window.confirm('Opening cash is 0. Is the drawer really empty?')) return
    try { setShift((await api.post('/shifts/open', { openingCash: Number(openingCash || 0) })).data); toast('Shift opened') } catch (e) { toast(errMsg(e), 'error') }
  }

  const needShift = user.role === 'STAFF' && !shift
  if (shift === undefined) return null

  return (
    <div>
      {needShift ? (
        <Card className="mx-auto mt-8 max-w-sm p-6 text-center">
          <Timer className="mx-auto mb-2 h-10 w-10 text-emerald-600" />
          <h2 className="text-lg font-semibold">Start your shift</h2>
          <p className="mb-4 text-sm text-slate-500">Count the cash in the drawer and enter it to begin billing.</p>
          <Field label="Opening cash (₹) *" hint="Required — count the drawer before you start"><input className="input text-center text-lg" type="number" inputMode="decimal" min="0" placeholder="Enter amount" value={openingCash} onChange={(e) => setOpeningCash(e.target.value)} autoFocus /></Field>
          <Button className="mt-4 w-full" size="lg" disabled={openingCash === ''} onClick={openShift}>Open shift</Button>
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-5">
          <div className="space-y-4 lg:col-span-3">
            {!shift && <Card className="flex items-center justify-between gap-2 bg-amber-50 p-3 text-sm text-amber-800"><span>No open shift — sales won’t count towards cash reconciliation.</span><Button size="sm" variant="secondary" onClick={openShift}>Open shift</Button></Card>}
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-3 h-5 w-5 text-slate-400" />
              <input ref={searchRef} autoFocus className="input py-2.5 pl-10 pr-10 text-base" placeholder="Scan barcode or search name / SKU…" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onSearchEnter} />
              <ScanBarcode className="pointer-events-none absolute right-3 top-3 h-5 w-5 text-slate-400" />
              {results.length > 0 && (
                <ul className="absolute z-20 mt-1 max-h-80 w-full overflow-auto rounded-xl border border-slate-200 bg-white shadow-lg">
                  {results.map((p) => (
                    <li key={p.id}><button className="flex w-full items-center justify-between gap-3 px-3 py-3 text-left hover:bg-emerald-50" onClick={() => add(p)}>
                      <span className="min-w-0"><span className="block truncate font-medium">{p.name}</span><span className="text-xs text-slate-500">{p.sku}{p.barcode && ` · ${p.barcode}`}</span></span>
                      <span className="shrink-0 text-right"><span className="block font-semibold">{money(p.sellingPrice)}</span><span className={cn('text-xs', Number(p.currentStock) <= 0 ? 'text-rose-600' : 'text-slate-500')}>{num(p.currentStock)} {unitLabel(p.unit)}</span></span>
                    </button></li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <h3 className="mb-2 text-sm font-semibold text-slate-600">Quick access</h3>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-4">
                {quick.map((p) => (
                  <button key={p.id} onClick={() => add(p)} className="flex min-h-[72px] flex-col justify-between rounded-xl border border-slate-200 bg-white p-3 text-left shadow-sm transition active:scale-95 hover:border-emerald-400">
                    <span className="line-clamp-2 text-sm font-medium leading-tight">{p.name}</span>
                    <span className="mt-1 flex items-center justify-between text-xs"><b className="text-emerald-700">{money(p.sellingPrice)}</b><span className="text-slate-400">{num(p.currentStock)}</span></span>
                  </button>
                ))}
                {quick.length === 0 && <p className="col-span-full text-sm text-slate-400">No items yet — add products first.</p>}
              </div>
            </div>
          </div>

          <Card className="flex flex-col lg:sticky lg:top-16 lg:col-span-2 lg:max-h-[calc(100vh-5rem)]">
            <div className="flex items-center justify-between border-b border-slate-200 p-3"><h3 className="flex items-center gap-2 font-semibold"><ShoppingCart className="h-4 w-4" />Cart <Badge tone="emerald">{cart.length}</Badge></h3>{cart.length > 0 && <button className="text-xs text-rose-600" onClick={() => setCart([])}>Clear</button>}</div>
            <div className="flex-1 divide-y divide-slate-100 overflow-y-auto">
              {lines.length === 0 && <p className="py-10 text-center text-sm text-slate-400">Scan or tap an item to begin</p>}
              {lines.map((l) => (
                <div key={l.product.id} className="p-3">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0"><p className="truncate text-sm font-medium">{l.product.name}</p><p className="text-xs text-slate-500">{money(l.product.sellingPrice)} / {unitLabel(l.product.unit)}</p></div>
                    <button onClick={() => remove(l.product.id)} className="rounded p-1 text-slate-400 hover:text-rose-600"><Trash2 className="h-4 w-4" /></button>
                  </div>
                  <div className="mt-2 flex items-center gap-2">
                    <button className="h-9 w-9 rounded-lg border border-slate-300 active:bg-slate-100" onClick={() => bump(l.product.id, -1)}><Minus className="mx-auto h-4 w-4" /></button>
                    <input className="input h-9 w-20 text-center" type="number" inputMode="decimal" min="0" step={isCountable(l.product.unit) ? 1 : 0.1} value={l.qty} onChange={(e) => setQty(l.product.id, e.target.value)} />
                    <button className="h-9 w-9 rounded-lg border border-slate-300 active:bg-slate-100" onClick={() => bump(l.product.id, 1)}><Plus className="mx-auto h-4 w-4" /></button>
                    <input className="input h-9 w-20" type="number" inputMode="decimal" min="0" placeholder="Disc ₹" value={l.discount} onChange={(e) => setDisc(l.product.id, e.target.value)} />
                    <span className="ml-auto text-sm font-semibold">{money(l.calc.total)}</span>
                  </div>
                  {Number(l.qty) > Number(l.product.currentStock) && <p className="mt-1 text-xs text-rose-600">Only {num(l.product.currentStock)} in stock</p>}
                </div>
              ))}
            </div>
            <div className="space-y-1 border-t border-slate-200 p-3 text-sm">
              <div className="flex justify-between text-slate-500"><span>Subtotal</span><span>{money(totals.gross)}</span></div>
              {totals.discount > 0 && <div className="flex justify-between text-slate-500"><span>Discount</span><span>−{money(totals.discount)}</span></div>}
              <div className="flex justify-between pt-1 text-xl font-bold"><span>Total</span><span>{money(total)}</span></div>
              <input className="input mt-2" placeholder="Customer name (optional)" value={customer} onChange={(e) => setCustomer(e.target.value)} />
              <Button size="lg" className="mt-2 w-full" disabled={!cart.length || !(total > 0) || lines.some((l) => !(Number(l.qty) > 0))} onClick={() => setPayOpen(true)}>Charge {money(total)}</Button>
            </div>
          </Card>
        </div>
      )}

      <PaymentModal open={payOpen} onClose={() => setPayOpen(false)} total={total} lines={lines} customer={customer}
        onDone={(sale) => { setPayOpen(false); setDone(sale); setCart([]); setCustomer(''); loadQuick() }} />

      <Modal open={!!done} onClose={() => { setDone(null); searchRef.current?.focus() }} title="Sale complete"
        footer={<><Button variant="secondary" onClick={() => window.print()}><Printer className="h-4 w-4" />Print</Button><Button onClick={() => { setDone(null); searchRef.current?.focus() }}>New sale</Button></>}>
        <div className="mb-3 flex items-center justify-center gap-2 text-emerald-600"><CheckCircle2 className="h-6 w-6" /><span className="font-semibold">Payment received</span></div>
        <Receipt sale={done} />
      </Modal>
    </div>
  )
}

function PaymentModal({ open, onClose, total, lines, customer, onDone }) {
  const toast = useToast()
  const [mode, setMode] = useState('CASH')
  const [tendered, setTendered] = useState('')
  const [splitCash, setSplitCash] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => { if (open) { setMode('CASH'); setTendered(''); setSplitCash('') } }, [open])

  const cashPart = mode === 'CASH' ? total : mode === 'UPI' ? 0 : Math.min(Math.max(Number(splitCash || 0), 0), total)
  const upiPart = Math.round((total - cashPart) * 100) / 100
  const change = mode === 'CASH' && tendered ? Math.round((Number(tendered) - total) * 100) / 100 : null

  const confirm = async () => {
    const payments = []
    if (cashPart > 0) payments.push({ method: 'CASH', amount: cashPart })
    if (upiPart > 0) payments.push({ method: 'UPI', amount: upiPart })
    setBusy(true)
    try {
      const { data } = await api.post('/sales/checkout', {
        customerName: customer || null, payments,
        items: lines.map((l) => ({ productId: l.product.id, quantity: Number(l.qty), discount: Number(l.discount || 0) })),
      })
      onDone(data)
    } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }

  const modes = [['CASH', 'Cash', Banknote], ['UPI', 'UPI', Smartphone], ['SPLIT', 'Split', SplitSquareHorizontal]]
  return (
    <Modal open={open} onClose={onClose} title={`Collect ${money(total)}`} footer={<Button size="lg" className="w-full sm:w-auto" loading={busy} disabled={mode === 'CASH' && tendered !== '' && Number(tendered) < total} onClick={confirm}>Confirm payment</Button>}>
      <div className="grid grid-cols-3 gap-2">
        {modes.map(([v, l, Icon]) => <button key={v} onClick={() => setMode(v)} className={cn('flex flex-col items-center gap-1 rounded-xl border-2 p-3 text-sm font-medium', mode === v ? 'border-emerald-600 bg-emerald-50 text-emerald-700' : 'border-slate-200 text-slate-600')}><Icon className="h-5 w-5" />{l}</button>)}
      </div>
      <div className="mt-4 space-y-3">
        {mode === 'CASH' && (
          <>
            <Field label="Cash tendered (optional)"><input className="input text-lg" type="number" inputMode="decimal" autoFocus value={tendered} onChange={(e) => setTendered(e.target.value)} /></Field>
            <div className="flex flex-wrap gap-2">{[total, Math.ceil(total / 10) * 10, Math.ceil(total / 100) * 100, Math.ceil(total / 500) * 500].filter((v, i, a) => a.indexOf(v) === i).map((v) => <Button key={v} variant="secondary" size="sm" onClick={() => setTendered(String(v))}>{money(v)}</Button>)}</div>
            {change !== null && <p className={cn('rounded-lg px-3 py-2 text-center text-lg font-semibold', change >= 0 ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700')}>{change >= 0 ? `Return change ${money(change)}` : `Short by ${money(-change)}`}</p>}
          </>
        )}
        {mode === 'UPI' && <div className="rounded-xl bg-indigo-50 p-4 text-center text-indigo-700"><QrCode className="mx-auto mb-1 h-8 w-8" /><p className="text-sm">Ask the customer to pay <b>{money(total)}</b> via UPI and confirm once received.</p></div>}
        {mode === 'SPLIT' && (
          <>
            <Field label="Cash portion (₹)"><input className="input text-lg" type="number" inputMode="decimal" autoFocus min="0" max={total} value={splitCash} onChange={(e) => setSplitCash(e.target.value)} /></Field>
            <div className="grid grid-cols-2 gap-2 text-center text-sm"><div className="rounded-lg bg-emerald-50 p-2 text-emerald-700">Cash<br /><b>{money(cashPart)}</b></div><div className="rounded-lg bg-indigo-50 p-2 text-indigo-700">UPI<br /><b>{money(upiPart)}</b></div></div>
          </>
        )}
      </div>
    </Modal>
  )
}
