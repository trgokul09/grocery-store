import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { AlertTriangle, Banknote, Boxes, CalendarClock, Coins, IndianRupee, Landmark, PackageSearch, Receipt, ShoppingBag, TrendingUp, UserCheck, Wallet } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Badge, Button, Card, Empty, PageHeader, Spinner, StatCard, TableWrap, useToast } from '../components/ui.jsx'
import { cn } from '../components/ui.jsx'
import { errMsg, fmtDate, fmtDateTime, isoToday, money, num } from '../utils'

const RANGES = [['TODAY', 'Today'], ['WEEK', 'This week'], ['MONTH', 'This month'], ['CUSTOM', 'Custom']]
const COLORS = ['#059669', '#4f46e5', '#f59e0b', '#e11d48', '#0ea5e9', '#64748b']
const short = (v) => (v >= 100000 ? (v / 100000).toFixed(1) + 'L' : v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v)
const fmtBucket = (b) => b?.slice(5)

export default function Dashboard() {
  const { can } = useAuth()
  const nav = useNavigate()
  const toast = useToast()
  const [range, setRange] = useState('TODAY')
  const [from, setFrom] = useState(isoToday())
  const [to, setTo] = useState(isoToday())
  const [data, setData] = useState(null)
  const [pnl, setPnl] = useState(null)
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    if (range === 'CUSTOM' && (!from || !to || to < from)) return
    setLoading(true)
    try {
      const params = { range, ...(range === 'CUSTOM' ? { from, to } : {}) }
      const { data: d } = await api.get('/dashboard', { params })
      setData(d)
      if (can('OWNER')) setPnl((await api.get('/finance/pnl', { params })).data)
    } catch (e) { toast(errMsg(e), 'error') } finally { setLoading(false) }
  }, [range, from, to]) // eslint-disable-line

  useEffect(() => { load() }, [load])
  useEffect(() => { const t = setInterval(load, 60000); return () => clearInterval(t) }, [load])

  const s = data?.summary
  return (
    <div>
      <PageHeader title="Dashboard" subtitle={data ? `${fmtDate(data.from)} – ${fmtDate(data.to)}` : ''}>
        <Button variant="secondary" size="sm" onClick={load}>Refresh</Button>
      </PageHeader>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <div className="flex overflow-x-auto rounded-xl bg-slate-100 p-1">
          {RANGES.map(([v, l]) => <button key={v} onClick={() => setRange(v)} className={cn('whitespace-nowrap rounded-lg px-3 py-1.5 text-sm font-medium', range === v ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-600')}>{l}</button>)}
        </div>
        {range === 'CUSTOM' && (
          <div className="flex items-center gap-2"><input type="date" className="input w-auto" value={from} max={to} onChange={(e) => setFrom(e.target.value)} /><span className="text-slate-400">to</span><input type="date" className="input w-auto" value={to} min={from} onChange={(e) => setTo(e.target.value)} /></div>
        )}
      </div>

      {loading && !data ? <Spinner /> : !s ? <Empty text="No data" /> : (
        <>
          <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-5">
            <StatCard label="Total sales" value={money(s.totalSales)} sub={`${s.billCount} bills`} icon={IndianRupee} />
            <StatCard label="Cash vs UPI" value={`${money(s.cashSales)}`} sub={`UPI ${money(s.upiSales)}`} icon={Banknote} tone="indigo" />
            <StatCard label="Expenses" value={money(s.expenses)} icon={Wallet} tone="amber" />
            <StatCard label="Gross profit" value={money(s.grossProfit)} sub={`Revenue ${money(s.netRevenue)}`} icon={TrendingUp} />
            <StatCard label="Operating profit" value={money(s.operatingProfit)} icon={Coins} tone={Number(s.operatingProfit) < 0 ? 'rose' : 'emerald'} />
            <StatCard label="Supplier outstanding" value={money(s.supplierOutstanding)} icon={Landmark} tone="rose" onClick={() => nav('/suppliers')} />
            <StatCard label="Inventory valuation" value={money(s.inventoryValuation)} sub="at purchase price" icon={Boxes} tone="indigo" onClick={() => nav('/inventory')} />
            <StatCard label="Low-stock alerts" value={s.lowStockCount} sub={`${s.expiringCount} expiring soon`} icon={AlertTriangle} tone={s.lowStockCount ? 'rose' : 'emerald'} onClick={() => nav('/inventory')} />
            <StatCard label="Purchases" value={money(s.purchases)} sub="received in period" icon={ShoppingBag} tone="amber" />
            <StatCard label="Active shifts" value={s.activeShifts.length} sub={s.activeShifts.map((x) => x.fullName).join(', ') || 'None open'} icon={UserCheck} />
          </div>

          <div className="mt-3 grid grid-cols-2 gap-3">
            <StatCard label="Cash balance (ledger)" value={money(s.cashBalance)} sub="sales − cash expenses − cash supplier payments" icon={Banknote} />
            <StatCard label="UPI balance (ledger)" value={money(s.upiBalance)} sub="sales − UPI expenses − UPI supplier payments" icon={Receipt} tone="indigo" />
          </div>

          <div className="mt-4 grid gap-4 lg:grid-cols-3">
            <Card className="p-4 lg:col-span-2">
              <h3 className="mb-3 text-sm font-semibold text-slate-700">Sales & profit ({data.group}ly)</h3>
              <div className="h-64">
                <ResponsiveContainer>
                  <AreaChart data={data.salesSeries.map((r) => ({ ...r, sales: Number(r.sales), profit: Number(r.profit) }))}>
                    <defs><linearGradient id="gs" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor="#059669" stopOpacity={0.35} /><stop offset="95%" stopColor="#059669" stopOpacity={0} /></linearGradient></defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="bucket" tickFormatter={fmtBucket} tick={{ fontSize: 11 }} />
                    <YAxis tickFormatter={short} tick={{ fontSize: 11 }} width={40} />
                    <Tooltip formatter={(v) => money(v)} />
                    <Legend />
                    <Area type="monotone" dataKey="sales" name="Sales" stroke="#059669" fill="url(#gs)" strokeWidth={2} />
                    <Area type="monotone" dataKey="profit" name="Gross profit" stroke="#4f46e5" fill="none" strokeWidth={2} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </Card>

            <Card className="p-4">
              <h3 className="mb-3 text-sm font-semibold text-slate-700">Expenses by category</h3>
              {data.expenseBreakdown.length === 0 ? <Empty text="No expenses" /> : (
                <div className="h-64">
                  <ResponsiveContainer>
                    <PieChart>
                      <Pie data={data.expenseBreakdown.map((e) => ({ name: e.category, value: Number(e.amount) }))} dataKey="value" nameKey="name" innerRadius={45} outerRadius={80} paddingAngle={2}>
                        {data.expenseBreakdown.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
                      </Pie>
                      <Tooltip formatter={(v) => money(v)} /><Legend wrapperStyle={{ fontSize: 11 }} />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>
          </div>

          <div className="mt-4 grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <h3 className="mb-3 text-sm font-semibold text-slate-700">Top-selling items</h3>
              {data.topItems.length === 0 ? <Empty text="No sales in this period" /> : (
                <div style={{ height: Math.max(180, data.topItems.length * 32) }}>
                  <ResponsiveContainer>
                    <BarChart data={data.topItems.map((t) => ({ name: t.name.length > 18 ? t.name.slice(0, 17) + '…' : t.name, quantity: Number(t.quantity) }))} layout="vertical" margin={{ left: 10 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" horizontal={false} />
                      <XAxis type="number" tick={{ fontSize: 11 }} /><YAxis type="category" dataKey="name" width={110} tick={{ fontSize: 11 }} />
                      <Tooltip /><Bar dataKey="quantity" name="Qty sold" fill="#4f46e5" radius={[0, 4, 4, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>

            <Card className="p-0">
              <div className="flex items-center gap-2 p-4 pb-2"><PackageSearch className="h-4 w-4 text-amber-600" /><h3 className="text-sm font-semibold text-slate-700">Slow-moving inventory <span className="font-normal text-slate-400">(no sales in 30 days)</span></h3></div>
              {data.slowMoving.length === 0 ? <Empty text="Nothing is stuck on the shelves" /> : (
                <TableWrap className="max-h-72">
                  <thead><tr><th className="th">Product</th><th className="th">Stock</th><th className="th">Value</th><th className="th">Last sold</th></tr></thead>
                  <tbody className="divide-y divide-slate-100">
                    {data.slowMoving.map((p) => <tr key={p.id}><td className="td">{p.name}</td><td className="td">{num(p.currentStock)}</td><td className="td">{money(p.value)}</td><td className="td text-slate-500">{p.lastSold ? fmtDate(p.lastSold) : 'Never'}</td></tr>)}
                  </tbody>
                </TableWrap>
              )}
            </Card>
          </div>

          {s.activeShifts.length > 0 && (
            <Card className="mt-4 p-4">
              <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold text-slate-700"><CalendarClock className="h-4 w-4" />Open shifts</h3>
              <div className="flex flex-wrap gap-2">{s.activeShifts.map((x) => <Badge key={x.id} tone="emerald">{x.fullName} · since {fmtDateTime(x.openedAt)} · float {money(x.openingCash)}</Badge>)}</div>
            </Card>
          )}

          {pnl && (
            <Card className="mt-4 p-4">
              <h3 className="mb-3 text-sm font-semibold text-slate-700">Profit & loss statement</h3>
              <dl className="grid gap-x-8 gap-y-1.5 text-sm sm:grid-cols-2">
                {[['Net revenue', pnl.netRevenue], ['Cost of goods sold', pnl.cogs], ['Gross profit', pnl.grossProfit, true], ['Operating expenses', pnl.operatingExpenses], ['Operating profit', pnl.operatingProfit, true]].map(([k, v, b]) => (
                  <div key={k} className={cn('flex justify-between border-b border-slate-100 py-1', b && 'font-semibold')}><dt className="text-slate-600">{k}</dt><dd className={Number(v) < 0 ? 'text-rose-600' : ''}>{money(v)}</dd></div>
                ))}
                <div className="flex justify-between border-b border-slate-100 py-1"><dt className="text-slate-600">Gross margin</dt><dd>{pnl.grossMarginPct}%</dd></div>
              </dl>
            </Card>
          )}
        </>
      )}
    </div>
  )
}
