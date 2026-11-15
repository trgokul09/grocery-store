import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, ComposedChart,
  Legend, Line, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import {
  AlertTriangle, ArrowDownRight, ArrowUpRight, Award, BarChart3, Calendar,
  Clock, Coins, Download, FileSpreadsheet, Filter, Flame, IndianRupee,
  Layers, Package, PackageMinus, Printer, RefreshCw, Search, ShoppingBag,
  TrendingDown, TrendingUp, Users, Zap,
} from 'lucide-react'
import api from '../api'
import {
  Badge, Button, Card, Empty, PageHeader, Pagination, Spinner, StatCard,
  TableWrap, Tabs, cn, useToast,
} from '../components/ui.jsx'
import { download, errMsg, fmtDate, isoToday, money, num, unitLabel } from '../utils'

const DATE_PRESETS = [
  ['TODAY', 'Today'],
  ['YESTERDAY', 'Yesterday'],
  ['LAST_7_DAYS', 'Last 7 days'],
  ['LAST_30_DAYS', 'Last 30 days'],
  ['THIS_MONTH', 'This month'],
  ['LAST_MONTH', 'Last month'],
  ['CUSTOM', 'Custom range'],
]

const GRANULARITIES = [
  { value: 'DAY', label: 'Daily' },
  { value: 'WEEK', label: 'Weekly' },
  { value: 'MONTH', label: 'Monthly' },
  { value: 'YEAR', label: 'Yearly' },
]

const VELOCITY_FILTERS = [
  { value: 'ALL', label: 'All Products' },
  { value: 'FAST_MOVING', label: 'Fast Moving' },
  { value: 'SLOW_MOVING', label: 'Slow Moving' },
  { value: 'ZERO_SALES', label: 'Zero Sales' },
  { value: 'DECLINING', label: 'Declining Sales' },
]

const COLORS = ['#059669', '#4f46e5', '#f59e0b', '#e11d48', '#0ea5e9', '#8b5cf6', '#14b8a6', '#64748b']

const shortMoney = (v) => {
  const n = Number(v || 0)
  if (Math.abs(n) >= 100000) return '₹' + (n / 100000).toFixed(1) + 'L'
  if (Math.abs(n) >= 1000) return '₹' + (n / 1000).toFixed(1) + 'k'
  return '₹' + n.toFixed(0)
}

function GrowthPill({ pct }) {
  const val = Number(pct || 0)
  if (val === 0) return <span className="text-xs text-slate-400">0.0% vs prev</span>
  const up = val > 0
  const Icon = up ? ArrowUpRight : ArrowDownRight
  return (
    <span className={cn('inline-flex items-center gap-0.5 text-xs font-medium', up ? 'text-emerald-600' : 'text-rose-600')}>
      <Icon className="h-3.5 w-3.5" />
      {up ? '+' : ''}{val.toFixed(1)}% vs prev
    </span>
  )
}

function VelocityBadge({ status }) {
  const map = {
    FAST: { tone: 'emerald', label: 'Fast Moving' },
    MODERATE: { tone: 'indigo', label: 'Moderate' },
    SLOW: { tone: 'amber', label: 'Slow Moving' },
    ZERO_SALES: { tone: 'rose', label: 'Zero Sales' },
  }
  const item = map[status] || { tone: 'slate', label: status || 'Unknown' }
  return <Badge tone={item.tone}>{item.label}</Badge>
}

export default function Analytics() {
  const toast = useToast()

  // Filter states
  const [range, setRange] = useState('LAST_30_DAYS')
  const [from, setFrom] = useState(isoToday())
  const [to, setTo] = useState(isoToday())
  const [granularity, setGranularity] = useState('DAY')
  const [categoryId, setCategoryId] = useState('')
  const [productId, setProductId] = useState('')

  // Reference lists for filter dropdowns
  const [categoryList, setCategoryList] = useState([])
  const [productList, setProductList] = useState([])

  // Main dashboard data
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [exporting, setExporting] = useState(false)

  // UI view toggles
  const [topRankBy, setTopRankBy] = useState('QUANTITY') // QUANTITY | REVENUE | PROFIT
  const [bottomTab, setBottomTab] = useState('BOTTOM10') // BOTTOM10 | SLOW | ZERO | DECLINING

  // Paginated Product Velocity Table state
  const [velPage, setVelPage] = useState(0)
  const [velFilter, setVelFilter] = useState('ALL')
  const [velSortBy, setVelSortBy] = useState('QUANTITY')
  const [velDirection, setVelDirection] = useState('DESC')
  const [velSearch, setVelSearch] = useState('')
  const [velData, setVelData] = useState(null)
  const [velLoading, setVelLoading] = useState(false)

  // Load filter options (categories & products)
  useEffect(() => {
    api.get('/categories').then((r) => setCategoryList(r.data || [])).catch(() => {})
    api.get('/products', { params: { size: 200, active: true } })
      .then((r) => setProductList(r.data?.content || []))
      .catch(() => {})
  }, [])

  const filteredProductOptions = useMemo(() => {
    if (!categoryId) return productList
    return productList.filter((p) => String(p.categoryId) === String(categoryId))
  }, [productList, categoryId])

  const buildQueryParams = useCallback(() => {
    const p = { range }
    if (range === 'CUSTOM') {
      p.from = from
      p.to = to
    }
    if (granularity) p.granularity = granularity
    if (categoryId) p.categoryId = categoryId
    if (productId) p.productId = productId
    return p
  }, [range, from, to, granularity, categoryId, productId])

  const loadDashboard = useCallback(async () => {
    if (range === 'CUSTOM' && (!from || !to || to < from)) return
    setLoading(true)
    try {
      const { data: res } = await api.get('/analytics/dashboard', { params: buildQueryParams() })
      setData(res)
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setLoading(false)
    }
  }, [range, from, to, buildQueryParams]) // eslint-disable-line

  const loadVelocityTable = useCallback(async () => {
    if (range === 'CUSTOM' && (!from || !to || to < from)) return
    setVelLoading(true)
    try {
      const params = {
        ...buildQueryParams(),
        search: velSearch || undefined,
        filter: velFilter,
        sortBy: velSortBy,
        direction: velDirection,
        page: velPage,
        size: 12,
      }
      const { data: res } = await api.get('/analytics/products', { params })
      setVelData(res)
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setVelLoading(false)
    }
  }, [range, from, to, buildQueryParams, velSearch, velFilter, velSortBy, velDirection, velPage]) // eslint-disable-line

  useEffect(() => {
    loadDashboard()
  }, [loadDashboard])

  useEffect(() => {
    loadVelocityTable()
  }, [loadVelocityTable])

  const handleExportExcel = async () => {
    setExporting(true)
    try {
      const res = await api.get('/analytics/export/excel', {
        params: buildQueryParams(),
        responseType: 'blob',
      })
      const fname = `sales-analytics-${data?.from || isoToday()}-to-${data?.to || isoToday()}.xlsx`
      download(res.data, fname)
      toast('Excel analytics report downloaded')
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setExporting(false)
    }
  }

  const handleExportPdf = () => {
    window.print()
  }

  const resetFilters = () => {
    setCategoryId('')
    setProductId('')
    setRange('LAST_30_DAYS')
    setGranularity('DAY')
    setVelPage(0)
  }

  // Select active trend series based on granularity switcher
  const activeTrendSeries = useMemo(() => {
    if (!data) return []
    const raw =
      granularity === 'WEEK' ? data.weeklySeries :
      granularity === 'MONTH' ? data.monthlySeries :
      granularity === 'YEAR' ? data.yearlySeries :
      data.dailySeries || data.trendSeries || []
    return raw.map((pt) => ({
      ...pt,
      revenue: Number(pt.revenue || 0),
      profit: Number(pt.profit || 0),
      cogs: Number(pt.cogs || 0),
      quantitySold: Number(pt.quantitySold || 0),
      billCount: Number(pt.billCount || 0),
    }))
  }, [data, granularity])

  const topProducts = useMemo(() => {
    if (!data) return []
    if (topRankBy === 'REVENUE') return data.topByRevenue || []
    if (topRankBy === 'PROFIT') return data.topByProfit || []
    return data.topByQuantity || []
  }, [data, topRankBy])

  const categoryNames = useMemo(() => {
    if (!data?.categories) return []
    return data.categories.slice(0, 6).map((c) => c.categoryName)
  }, [data])

  const s = data?.summary

  return (
    <div className="space-y-4 print:space-y-3">
      <PageHeader
        title="Sales Analytics Dashboard"
        subtitle={
          data
            ? `${fmtDate(data.from)} – ${fmtDate(data.to)} (${s?.rangeDays || 1} day${s?.rangeDays === 1 ? '' : 's'}) · Compared with ${fmtDate(data.prevFrom)} – ${fmtDate(data.prevTo)}`
            : 'Real-time product, category, velocity & profitability intelligence'
        }
      >
        <div className="flex flex-wrap items-center gap-2 print:hidden">
          <Button variant="secondary" size="sm" onClick={() => { loadDashboard(); loadVelocityTable() }}>
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </Button>
          <Button variant="secondary" size="sm" onClick={handleExportExcel} loading={exporting}>
            <FileSpreadsheet className="h-3.5 w-3.5 text-emerald-600" /> Export Excel
          </Button>
          <Button variant="secondary" size="sm" onClick={handleExportPdf}>
            <Printer className="h-3.5 w-3.5 text-indigo-600" /> Export PDF
          </Button>
        </div>
      </PageHeader>

      {/* Filter Bar */}
      <Card className="p-3 print:hidden">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div className="flex flex-wrap items-center gap-1.5">
            <div className="flex flex-wrap gap-1 rounded-xl bg-slate-100 p-1">
              {DATE_PRESETS.map(([val, label]) => (
                <button
                  key={val}
                  onClick={() => { setRange(val); setVelPage(0) }}
                  className={cn(
                    'whitespace-nowrap rounded-lg px-2.5 py-1.5 text-xs font-medium transition',
                    range === val ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-600 hover:text-slate-900'
                  )}
                >
                  {label}
                </button>
              ))}
            </div>

            {range === 'CUSTOM' && (
              <div className="flex items-center gap-1.5">
                <input
                  type="date"
                  className="input h-8 w-auto text-xs"
                  value={from}
                  max={to}
                  onChange={(e) => { setFrom(e.target.value); setVelPage(0) }}
                />
                <span className="text-xs text-slate-400">to</span>
                <input
                  type="date"
                  className="input h-8 w-auto text-xs"
                  value={to}
                  min={from}
                  onChange={(e) => { setTo(e.target.value); setVelPage(0) }}
                />
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1.5">
              <Filter className="h-3.5 w-3.5 text-slate-400" />
              <select
                className="input h-8 w-auto min-w-[140px] py-1 text-xs"
                value={categoryId}
                onChange={(e) => {
                  setCategoryId(e.target.value)
                  setProductId('')
                  setVelPage(0)
                }}
              >
                <option value="">All Categories</option>
                {categoryList.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
            </div>

            <select
              className="input h-8 w-auto min-w-[160px] py-1 text-xs"
              value={productId}
              onChange={(e) => { setProductId(e.target.value); setVelPage(0) }}
            >
              <option value="">All Products</option>
              {filteredProductOptions.map((p) => (
                <option key={p.id} value={p.id}>{p.name} ({p.sku})</option>
              ))}
            </select>

            {(categoryId || productId || range !== 'LAST_30_DAYS') && (
              <Button variant="ghost" size="sm" onClick={resetFilters}>Reset</Button>
            )}
          </div>
        </div>
      </Card>

      {loading && !data ? (
        <Spinner />
      ) : !s ? (
        <Empty text="No analytics data available" />
      ) : (
        <>
          {/* Primary KPI Cards */}
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
            <Card className="p-3.5">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium text-slate-500">Total Revenue</p>
                <span className="rounded-lg bg-emerald-50 p-1.5 text-emerald-700"><IndianRupee className="h-4 w-4" /></span>
              </div>
              <p className="mt-1.5 truncate text-lg font-bold text-slate-900 sm:text-xl">{money(s.totalRevenue)}</p>
              <div className="mt-1 flex items-center justify-between gap-1">
                <GrowthPill pct={s.revenueGrowthPct} />
                <span className="text-[11px] text-slate-500">{s.billCount} bills</span>
              </div>
            </Card>

            <Card className="p-3.5">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium text-slate-500">Total Qty Sold</p>
                <span className="rounded-lg bg-indigo-50 p-1.5 text-indigo-700"><ShoppingBag className="h-4 w-4" /></span>
              </div>
              <p className="mt-1.5 truncate text-lg font-bold text-slate-900 sm:text-xl">{num(s.totalQuantitySold)}</p>
              <div className="mt-1 flex items-center justify-between gap-1">
                <GrowthPill pct={s.quantityGrowthPct} />
                <span className="text-[11px] text-slate-500">Avg {num(s.totalQuantitySold / Math.max(1, s.rangeDays), 1)}/day</span>
              </div>
            </Card>

            <Card className="p-3.5">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium text-slate-500">Total Profit</p>
                <span className={cn('rounded-lg p-1.5', Number(s.totalProfit) < 0 ? 'bg-rose-50 text-rose-700' : 'bg-emerald-50 text-emerald-700')}>
                  <Coins className="h-4 w-4" />
                </span>
              </div>
              <p className="mt-1.5 truncate text-lg font-bold text-slate-900 sm:text-xl">{money(s.totalProfit)}</p>
              <div className="mt-1 flex items-center justify-between gap-1">
                <GrowthPill pct={s.profitGrowthPct} />
                <Badge tone="emerald">{s.grossMarginPct}% margin</Badge>
              </div>
            </Card>

            <Card className="p-3.5">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium text-slate-500">Best-Selling Product</p>
                <span className="rounded-lg bg-amber-50 p-1.5 text-amber-700"><Award className="h-4 w-4" /></span>
              </div>
              <p className="mt-1.5 truncate text-base font-bold text-slate-900" title={s.bestSellingProduct?.productName}>
                {s.bestSellingProduct ? s.bestSellingProduct.productName : 'No sales yet'}
              </p>
              <p className="mt-1 truncate text-xs text-slate-500">
                {s.bestSellingProduct
                  ? `${num(s.bestSellingProduct.quantitySold)} ${unitLabel(s.bestSellingProduct.unit)} · ${money(s.bestSellingProduct.revenue)}`
                  : '0 units sold in period'}
              </p>
            </Card>

            <Card className="p-3.5">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium text-slate-500">Least-Selling Product</p>
                <span className="rounded-lg bg-rose-50 p-1.5 text-rose-700"><PackageMinus className="h-4 w-4" /></span>
              </div>
              <p className="mt-1.5 truncate text-base font-bold text-slate-900" title={s.leastSellingProduct?.productName}>
                {s.leastSellingProduct ? s.leastSellingProduct.productName : '—'}
              </p>
              <p className="mt-1 truncate text-xs text-slate-500">
                {s.leastSellingProduct
                  ? `${num(s.leastSellingProduct.quantitySold)} sold · ${s.zeroSalesProductCount} with 0 sales`
                  : 'No products'}
              </p>
            </Card>

            <Card className="p-3.5">
              <div className="flex items-start justify-between gap-2">
                <p className="text-xs font-medium text-slate-500">Best Category</p>
                <span className="rounded-lg bg-indigo-50 p-1.5 text-indigo-700"><Layers className="h-4 w-4" /></span>
              </div>
              <p className="mt-1.5 truncate text-base font-bold text-slate-900" title={s.bestPerformingCategory?.categoryName}>
                {s.bestPerformingCategory ? s.bestPerformingCategory.categoryName : 'No category sales'}
              </p>
              <p className="mt-1 truncate text-xs text-slate-500">
                {s.bestPerformingCategory
                  ? `${money(s.bestPerformingCategory.revenue)} (${s.bestPerformingCategory.revenueContributionPct}%) · Low: ${s.lowestPerformingCategory?.categoryName || '—'}`
                  : `Lowest: ${s.lowestPerformingCategory?.categoryName || '—'}`}
              </p>
            </Card>
          </div>

          {/* Secondary Operational Insights Strip */}
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <StatCard
              label="Average Order Value (AOV)"
              value={money(s.avgOrderValue)}
              sub={`Avg ${num(s.avgBasketSize, 1)} units / bill · Disc ${money(s.totalDiscount)}`}
              icon={TrendingUp}
              tone="indigo"
            />
            <StatCard
              label="Peak Sales Day"
              value={s.peakSalesDay ? `${fmtDate(s.peakSalesDay.date)} (${s.peakSalesDay.dayName})` : 'No sales'}
              sub={s.peakSalesDay ? `${money(s.peakSalesDay.revenue)} across ${s.peakSalesDay.billCount} bills` : '—'}
              icon={Calendar}
              tone="emerald"
            />
            <StatCard
              label="Peak Sales Hour"
              value={s.peakSalesHour ? s.peakSalesHour.label : 'No sales'}
              sub={s.peakSalesHour ? `${money(s.peakSalesHour.revenue)} · ${s.peakSalesHour.billCount} bills` : '—'}
              icon={Clock}
              tone="amber"
            />
            <StatCard
              label="Inventory Health Alerts"
              value={`${s.slowMovingProductCount} Slow / Stuck`}
              sub={`${s.zeroSalesProductCount} zero-sale items · ${s.decliningProductCount} declining`}
              icon={AlertTriangle}
              tone={s.slowMovingProductCount > 0 ? 'rose' : 'emerald'}
            />
          </div>

          {/* Row 1 Charts: Sales & Profit Trend + Category Performance */}
          <div className="grid gap-4 lg:grid-cols-3">
            <Card className="p-4 lg:col-span-2">
              <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h3 className="text-sm font-semibold text-slate-800">Sales & Profit Trend</h3>
                  <p className="text-xs text-slate-500">Revenue, gross profit and order volume over time</p>
                </div>
                <div className="flex rounded-lg bg-slate-100 p-0.5 print:hidden">
                  {GRANULARITIES.map((g) => (
                    <button
                      key={g.value}
                      onClick={() => setGranularity(g.value)}
                      className={cn(
                        'rounded-md px-2.5 py-1 text-xs font-medium transition',
                        granularity === g.value ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-600 hover:text-slate-900'
                      )}
                    >
                      {g.label}
                    </button>
                  ))}
                </div>
              </div>

              {activeTrendSeries.length === 0 ? (
                <Empty text="No sales trend data for the selected period" />
              ) : (
                <div className="h-72">
                  <ResponsiveContainer>
                    <ComposedChart data={activeTrendSeries}>
                      <defs>
                        <linearGradient id="revGrad" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="#059669" stopOpacity={0.3} />
                          <stop offset="95%" stopColor="#059669" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                      <XAxis dataKey="label" tick={{ fontSize: 11 }} />
                      <YAxis yAxisId="left" tickFormatter={shortMoney} tick={{ fontSize: 11 }} width={52} />
                      <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 11 }} width={36} />
                      <Tooltip
                        formatter={(val, name) =>
                          name === 'Qty Sold' || name === 'Bills' ? [num(val), name] : [money(val), name]
                        }
                      />
                      <Legend wrapperStyle={{ fontSize: 12 }} />
                      <Area
                        yAxisId="left"
                        type="monotone"
                        dataKey="revenue"
                        name="Revenue"
                        stroke="#059669"
                        fill="url(#revGrad)"
                        strokeWidth={2}
                      />
                      <Line
                        yAxisId="left"
                        type="monotone"
                        dataKey="profit"
                        name="Profit"
                        stroke="#4f46e5"
                        strokeWidth={2}
                        dot={{ r: 2 }}
                      />
                      <Bar
                        yAxisId="right"
                        dataKey="quantitySold"
                        name="Qty Sold"
                        fill="#f59e0b"
                        opacity={0.45}
                        radius={[3, 3, 0, 0]}
                      />
                    </ComposedChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>

            <Card className="p-4">
              <div className="mb-3">
                <h3 className="text-sm font-semibold text-slate-800">Category Contribution %</h3>
                <p className="text-xs text-slate-500">Share of total store revenue by category</p>
              </div>
              {data.categories.filter((c) => Number(c.revenue) > 0).length === 0 ? (
                <Empty text="No category sales in this period" />
              ) : (
                <div className="h-72">
                  <ResponsiveContainer>
                    <PieChart>
                      <Pie
                        data={data.categories
                          .filter((c) => Number(c.revenue) > 0)
                          .map((c) => ({
                            name: c.categoryName,
                            value: Number(c.revenue),
                            pct: Number(c.revenueContributionPct),
                          }))}
                        dataKey="value"
                        nameKey="name"
                        innerRadius={50}
                        outerRadius={85}
                        paddingAngle={2}
                      >
                        {data.categories.map((_, i) => (
                          <Cell key={i} fill={COLORS[i % COLORS.length]} />
                        ))}
                      </Pie>
                      <Tooltip formatter={(v, n, p) => [`${money(v)} (${p.payload.pct}%)`, n]} />
                      <Legend wrapperStyle={{ fontSize: 11 }} />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>
          </div>

          {/* Row 2: Category Comparison Chart + Category Performance Over Time + Category Details Table */}
          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <div className="mb-3">
                <h3 className="text-sm font-semibold text-slate-800">Category Revenue vs Profit Comparison</h3>
                <p className="text-xs text-slate-500">Current vs previous period revenue and category profitability</p>
              </div>
              {data.categories.length === 0 ? (
                <Empty text="No categories found" />
              ) : (
                <div className="h-64">
                  <ResponsiveContainer>
                    <BarChart
                      data={data.categories.map((c) => ({
                        name: c.categoryName.length > 14 ? c.categoryName.slice(0, 13) + '…' : c.categoryName,
                        revenue: Number(c.revenue),
                        prevRevenue: Number(c.prevRevenue),
                        profit: Number(c.profit),
                      }))}
                    >
                      <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                      <XAxis dataKey="name" tick={{ fontSize: 11 }} />
                      <YAxis tickFormatter={shortMoney} tick={{ fontSize: 11 }} width={50} />
                      <Tooltip formatter={(v) => money(v)} />
                      <Legend wrapperStyle={{ fontSize: 11 }} />
                      <Bar dataKey="revenue" name="Current Revenue" fill="#059669" radius={[4, 4, 0, 0]} />
                      <Bar dataKey="prevRevenue" name="Previous Period" fill="#94a3b8" radius={[4, 4, 0, 0]} />
                      <Bar dataKey="profit" name="Gross Profit" fill="#4f46e5" radius={[4, 4, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>

            <Card className="p-4">
              <div className="mb-3">
                <h3 className="text-sm font-semibold text-slate-800">Category Performance Over Time</h3>
                <p className="text-xs text-slate-500">Revenue trajectory across top product categories</p>
              </div>
              {!data.categoryTrendSeries || data.categoryTrendSeries.length === 0 ? (
                <Empty text="No category time-series data in this period" />
              ) : (
                <div className="h-64">
                  <ResponsiveContainer>
                    <AreaChart data={data.categoryTrendSeries}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                      <XAxis dataKey="bucket" tickFormatter={(b) => (b?.length >= 10 ? b.slice(5) : b)} tick={{ fontSize: 11 }} />
                      <YAxis tickFormatter={shortMoney} tick={{ fontSize: 11 }} width={50} />
                      <Tooltip formatter={(v) => money(v)} />
                      <Legend wrapperStyle={{ fontSize: 11 }} />
                      {categoryNames.map((catName, idx) => (
                        <Area
                          key={catName}
                          type="monotone"
                          stackId="1"
                          dataKey={catName}
                          name={catName}
                          stroke={COLORS[idx % COLORS.length]}
                          fill={COLORS[idx % COLORS.length]}
                          fillOpacity={0.35}
                        />
                      ))}
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              )}
            </Card>
          </div>

          {/* Category Analytics Table */}
          <Card className="p-0">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
              <div>
                <h3 className="text-sm font-semibold text-slate-800">Category Performance Breakdown</h3>
                <p className="text-xs text-slate-500">Revenue, quantity sold, profit margin, contribution % and period-over-period growth</p>
              </div>
              {s.bestPerformingCategory && (
                <div className="flex flex-wrap gap-2">
                  <Badge tone="emerald">Best: {s.bestPerformingCategory.categoryName} ({money(s.bestPerformingCategory.revenue)})</Badge>
                  {s.lowestPerformingCategory && (
                    <Badge tone="amber">Lowest: {s.lowestPerformingCategory.categoryName} ({money(s.lowestPerformingCategory.revenue)})</Badge>
                  )}
                </div>
              )}
            </div>
            {data.categories.length === 0 ? (
              <Empty text="No categories available" />
            ) : (
              <TableWrap className="max-h-80">
                <thead>
                  <tr>
                    <th className="th">Category</th>
                    <th className="th text-right">Products</th>
                    <th className="th text-right">Bills</th>
                    <th className="th text-right">Qty Sold</th>
                    <th className="th text-right">Revenue</th>
                    <th className="th text-right">Profit</th>
                    <th className="th text-right">Margin %</th>
                    <th className="th text-right">Contribution %</th>
                    <th className="th text-right">Prev Revenue</th>
                    <th className="th text-right">Growth</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {data.categories.map((c) => (
                    <tr key={c.categoryId ?? 'uncat'} className="hover:bg-slate-50/70">
                      <td className="td font-medium text-slate-900">{c.categoryName}</td>
                      <td className="td text-right">{c.productCount}</td>
                      <td className="td text-right">{c.billCount}</td>
                      <td className="td text-right">{num(c.quantitySold)}</td>
                      <td className="td text-right font-semibold text-slate-900">{money(c.revenue)}</td>
                      <td className={cn('td text-right font-medium', Number(c.profit) < 0 ? 'text-rose-600' : 'text-emerald-700')}>
                        {money(c.profit)}
                      </td>
                      <td className="td text-right">{c.marginPct}%</td>
                      <td className="td text-right">
                        <div className="flex items-center justify-end gap-2">
                          <div className="h-1.5 w-16 overflow-hidden rounded-full bg-slate-100">
                            <div className="h-full bg-emerald-600" style={{ width: `${Math.min(100, Number(c.revenueContributionPct))}%` }} />
                          </div>
                          <span className="w-11 text-xs">{c.revenueContributionPct}%</span>
                        </div>
                      </td>
                      <td className="td text-right text-slate-500">{money(c.prevRevenue)}</td>
                      <td className="td text-right"><GrowthPill pct={c.revenueGrowthPct} /></td>
                    </tr>
                  ))}
                </tbody>
              </TableWrap>
            )}
          </Card>

          {/* Top 10 & Bottom 10 / Slow-Moving / Zero-Sales / Declining Products */}
          <div className="grid gap-4 lg:grid-cols-2">
            {/* Top 10 Best-Selling Products */}
            <Card className="p-0">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
                <div>
                  <h3 className="flex items-center gap-1.5 text-sm font-semibold text-slate-800">
                    <Flame className="h-4 w-4 text-emerald-600" /> Top 10 Best-Selling Products
                  </h3>
                  <p className="text-xs text-slate-500">Ranked by quantity sold, total revenue or profit</p>
                </div>
                <div className="flex rounded-lg bg-slate-100 p-0.5 print:hidden">
                  {[
                    ['QUANTITY', 'By Qty'],
                    ['REVENUE', 'By Revenue'],
                    ['PROFIT', 'By Profit'],
                  ].map(([k, lbl]) => (
                    <button
                      key={k}
                      onClick={() => setTopRankBy(k)}
                      className={cn(
                        'rounded-md px-2 py-1 text-xs font-medium transition',
                        topRankBy === k ? 'bg-white text-emerald-700 shadow-sm' : 'text-slate-600'
                      )}
                    >
                      {lbl}
                    </button>
                  ))}
                </div>
              </div>

              {topProducts.length === 0 ? (
                <Empty text="No product sales recorded in this period" />
              ) : (
                <TableWrap className="max-h-96">
                  <thead>
                    <tr>
                      <th className="th">#</th>
                      <th className="th">Product</th>
                      <th className="th text-right">Qty Sold</th>
                      <th className="th text-right">Revenue</th>
                      <th className="th text-right">Profit</th>
                      <th className="th text-right">Share %</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {topProducts.map((p, idx) => (
                      <tr key={p.productId} className="hover:bg-slate-50/70">
                        <td className="td font-semibold text-slate-500">{idx + 1}</td>
                        <td className="td">
                          <div className="font-medium text-slate-900">{p.productName}</div>
                          <div className="text-xs text-slate-400">{p.sku} · {p.categoryName}</div>
                        </td>
                        <td className="td text-right font-medium">
                          {num(p.quantitySold)} <span className="text-xs text-slate-400">{unitLabel(p.unit)}</span>
                        </td>
                        <td className="td text-right font-semibold text-slate-900">{money(p.revenue)}</td>
                        <td className="td text-right text-emerald-700">
                          <div>{money(p.profit)}</div>
                          <div className="text-[11px] text-slate-400">{p.marginPct}% margin</div>
                        </td>
                        <td className="td text-right">
                          <div>{p.revenueContributionPct}%</div>
                          <GrowthPill pct={p.salesGrowthPct} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              )}
            </Card>

            {/* Bottom 10 / Slow-Moving / Zero-Sales / Declining Products */}
            <Card className="p-0">
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
                <div>
                  <h3 className="flex items-center gap-1.5 text-sm font-semibold text-slate-800">
                    <TrendingDown className="h-4 w-4 text-rose-600" /> Underperforming & Slow Inventory
                  </h3>
                  <p className="text-xs text-slate-500">Least-selling, slow-moving, zero-sales & declining items</p>
                </div>
                <div className="flex flex-wrap rounded-lg bg-slate-100 p-0.5 print:hidden">
                  {[
                    ['BOTTOM10', 'Bottom 10'],
                    ['SLOW', `Slow (${s.slowMovingProductCount})`],
                    ['ZERO', `Zero Sales (${s.zeroSalesProductCount})`],
                    ['DECLINING', `Declining (${s.decliningProductCount})`],
                  ].map(([k, lbl]) => (
                    <button
                      key={k}
                      onClick={() => setBottomTab(k)}
                      className={cn(
                        'rounded-md px-2 py-1 text-xs font-medium transition',
                        bottomTab === k ? 'bg-white text-rose-700 shadow-sm' : 'text-slate-600'
                      )}
                    >
                      {lbl}
                    </button>
                  ))}
                </div>
              </div>

              {(() => {
                const list =
                  bottomTab === 'SLOW' ? data.slowMovingProducts :
                  bottomTab === 'ZERO' ? data.zeroSalesProducts :
                  bottomTab === 'DECLINING' ? data.decliningProducts :
                  data.bottomProducts || []

                if (!list || list.length === 0) {
                  return <Empty text="No products match this underperforming filter" />
                }

                return (
                  <TableWrap className="max-h-96">
                    <thead>
                      <tr>
                        <th className="th">Product</th>
                        <th className="th text-right">Qty Sold</th>
                        <th className="th text-right">Revenue</th>
                        <th className="th text-right">Stock & Value</th>
                        <th className="th text-right">
                          {bottomTab === 'DECLINING' ? 'Prev Period' : 'Last Sold / Status'}
                        </th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {list.map((p) => (
                        <tr key={p.productId} className="hover:bg-slate-50/70">
                          <td className="td">
                            <div className="font-medium text-slate-900">{p.productName}</div>
                            <div className="text-xs text-slate-400">{p.sku} · {p.categoryName}</div>
                          </td>
                          <td className="td text-right font-medium">
                            {num(p.quantitySold)} <span className="text-xs text-slate-400">{unitLabel(p.unit)}</span>
                          </td>
                          <td className="td text-right">{money(p.revenue)}</td>
                          <td className="td text-right">
                            <div className="font-medium text-slate-800">{num(p.currentStock)} {unitLabel(p.unit)}</div>
                            <div className="text-xs text-slate-400">{money(p.stockValue)} at cost</div>
                          </td>
                          <td className="td text-right">
                            {bottomTab === 'DECLINING' ? (
                              <div>
                                <div className="text-xs text-slate-600">Prev: {num(p.prevQuantitySold)}</div>
                                <GrowthPill pct={p.salesGrowthPct} />
                              </div>
                            ) : (
                              <div className="flex flex-col items-end gap-1">
                                <VelocityBadge status={p.velocityStatus} />
                                <span className="text-[11px] text-slate-400">
                                  {p.lastSoldAt ? `Last: ${fmtDate(p.lastSoldAt)} (${p.daysSinceLastSale}d ago)` : 'Never sold'}
                                </span>
                              </div>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </TableWrap>
                )
              })()}
            </Card>
          </div>

          {/* Product Velocity & Stock Coverage Table (Paginated) */}
          <Card className="p-0">
            <div className="flex flex-col gap-3 border-b border-slate-100 p-4 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <h3 className="flex items-center gap-1.5 text-sm font-semibold text-slate-800">
                  <Zap className="h-4 w-4 text-amber-500" /> Product Sales Velocity & Stock Coverage
                </h3>
                <p className="text-xs text-slate-500">
                  Consistent velocity over {s.rangeDays} available selling day(s): Avg units/day, week, month & estimated days of stock remaining
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-2 print:hidden">
                <div className="relative">
                  <Search className="pointer-events-none absolute left-2.5 top-2 h-3.5 w-3.5 text-slate-400" />
                  <input
                    type="text"
                    placeholder="Search SKU or product..."
                    className="input h-8 w-44 pl-8 text-xs"
                    value={velSearch}
                    onChange={(e) => { setVelSearch(e.target.value); setVelPage(0) }}
                  />
                </div>

                <select
                  className="input h-8 w-auto py-1 text-xs"
                  value={velFilter}
                  onChange={(e) => { setVelFilter(e.target.value); setVelPage(0) }}
                >
                  {VELOCITY_FILTERS.map((f) => (
                    <option key={f.value} value={f.value}>{f.label}</option>
                  ))}
                </select>

                <select
                  className="input h-8 w-auto py-1 text-xs"
                  value={`${velSortBy}:${velDirection}`}
                  onChange={(e) => {
                    const [sb, dir] = e.target.value.split(':')
                    setVelSortBy(sb)
                    setVelDirection(dir)
                    setVelPage(0)
                  }}
                >
                  <option value="QUANTITY:DESC">Sort: Highest Qty Sold</option>
                  <option value="QUANTITY:ASC">Sort: Lowest Qty Sold</option>
                  <option value="VELOCITY:DESC">Sort: Highest Velocity / Day</option>
                  <option value="REVENUE:DESC">Sort: Highest Revenue</option>
                  <option value="PROFIT:DESC">Sort: Highest Profit</option>
                  <option value="STOCK_COVERAGE:ASC">Sort: Lowest Stock Coverage</option>
                  <option value="STOCK_COVERAGE:DESC">Sort: Highest Stock Coverage</option>
                  <option value="GROWTH:ASC">Sort: Steepest Decline</option>
                </select>
              </div>
            </div>

            {velLoading && !velData ? (
              <Spinner />
            ) : !velData || velData.content.length === 0 ? (
              <Empty text="No products match the selected velocity criteria" />
            ) : (
              <>
                <TableWrap>
                  <thead>
                    <tr>
                      <th className="th">Product</th>
                      <th className="th">Category</th>
                      <th className="th text-right">Qty Sold</th>
                      <th className="th text-right">Revenue</th>
                      <th className="th text-right">Profit</th>
                      <th className="th text-right">Avg / Day</th>
                      <th className="th text-right">Avg / Week</th>
                      <th className="th text-right">Avg / Month</th>
                      <th className="th text-right">Current Stock</th>
                      <th className="th text-right">Stock Coverage</th>
                      <th className="th text-right">Contribution</th>
                      <th className="th text-center">Velocity</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {velData.content.map((p) => (
                      <tr key={p.productId} className="hover:bg-slate-50/70">
                        <td className="td">
                          <div className="font-medium text-slate-900">{p.productName}</div>
                          <div className="text-xs text-slate-400">{p.sku}</div>
                        </td>
                        <td className="td text-xs text-slate-600">{p.categoryName}</td>
                        <td className="td text-right font-medium">
                          {num(p.quantitySold)} <span className="text-xs text-slate-400">{unitLabel(p.unit)}</span>
                        </td>
                        <td className="td text-right font-medium text-slate-900">{money(p.revenue)}</td>
                        <td className={cn('td text-right font-medium', Number(p.profit) < 0 ? 'text-rose-600' : 'text-emerald-700')}>
                          {money(p.profit)}
                        </td>
                        <td className="td text-right font-semibold text-indigo-700">{num(p.avgDailyUnits, 2)}</td>
                        <td className="td text-right text-slate-600">{num(p.avgWeeklyUnits, 1)}</td>
                        <td className="td text-right text-slate-600">{num(p.avgMonthlyUnits, 1)}</td>
                        <td className="td text-right">
                          {num(p.currentStock)} <span className="text-xs text-slate-400">{unitLabel(p.unit)}</span>
                        </td>
                        <td className="td text-right">
                          {p.stockCoverageDays === null ? (
                            <span className="text-xs text-slate-400">∞ (No sales)</span>
                          ) : Number(p.stockCoverageDays) === 0 ? (
                            <Badge tone="rose">Out of stock</Badge>
                          ) : Number(p.stockCoverageDays) <= 7 ? (
                            <Badge tone="amber">{p.stockCoverageDays} days</Badge>
                          ) : (
                            <span className="font-medium text-slate-700">{p.stockCoverageDays} days</span>
                          )}
                        </td>
                        <td className="td text-right text-xs text-slate-600">{p.revenueContributionPct}%</td>
                        <td className="td text-center"><VelocityBadge status={p.velocityStatus} /></td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
                <Pagination
                  page={velData.number}
                  totalPages={velData.totalPages}
                  total={velData.totalElements}
                  onPage={setVelPage}
                />
              </>
            )}
          </Card>

          {/* Peak Sales Hours, Peak Sales Days, Payment Methods & Cashier Performance */}
          <div className="grid gap-4 lg:grid-cols-2">
            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-slate-800">Peak Sales Hours (Store Time)</h3>
                  <p className="text-xs text-slate-500">Hourly revenue & bill distribution (00:00 – 23:00)</p>
                </div>
                {s.peakSalesHour && (
                  <Badge tone="amber">Peak: {s.peakSalesHour.label} ({money(s.peakSalesHour.revenue)})</Badge>
                )}
              </div>
              <div className="h-56">
                <ResponsiveContainer>
                  <BarChart data={data.salesByHour.map((h) => ({ ...h, revenue: Number(h.revenue), billCount: Number(h.billCount) }))}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="label" tick={{ fontSize: 10 }} interval={2} />
                    <YAxis tickFormatter={shortMoney} tick={{ fontSize: 11 }} width={48} />
                    <Tooltip formatter={(v, n) => [n === 'Revenue' ? money(v) : v, n]} />
                    <Bar dataKey="revenue" name="Revenue" fill="#f59e0b" radius={[3, 3, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>

            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <div>
                  <h3 className="text-sm font-semibold text-slate-800">Sales by Day of Week</h3>
                  <p className="text-xs text-slate-500">Identifies strongest shopping days of the week</p>
                </div>
                {s.peakSalesDay && (
                  <Badge tone="emerald">Top Date: {fmtDate(s.peakSalesDay.date)}</Badge>
                )}
              </div>
              <div className="h-56">
                <ResponsiveContainer>
                  <BarChart data={data.salesByDayOfWeek.map((d) => ({ ...d, revenue: Number(d.revenue), profit: Number(d.profit) }))}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                    <XAxis dataKey="dayName" tick={{ fontSize: 11 }} />
                    <YAxis tickFormatter={shortMoney} tick={{ fontSize: 11 }} width={48} />
                    <Tooltip formatter={(v) => money(v)} />
                    <Legend wrapperStyle={{ fontSize: 11 }} />
                    <Bar dataKey="revenue" name="Revenue" fill="#059669" radius={[4, 4, 0, 0]} />
                    <Bar dataKey="profit" name="Profit" fill="#4f46e5" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Card>
          </div>

          {/* Payment Method Split & Cashier Performance */}
          <div className="grid gap-4 lg:grid-cols-3">
            <Card className="p-4">
              <h3 className="mb-1 text-sm font-semibold text-slate-800">Sales by Payment Method</h3>
              <p className="mb-3 text-xs text-slate-500">Revenue split across Cash, UPI & Card</p>
              {data.paymentMethods.length === 0 ? (
                <Empty text="No payments recorded" />
              ) : (
                <div className="space-y-2.5">
                  {data.paymentMethods.map((pm, idx) => (
                    <div key={pm.method} className="rounded-lg border border-slate-100 p-2.5">
                      <div className="flex items-center justify-between text-sm">
                        <span className="font-semibold text-slate-800">{pm.method}</span>
                        <span className="font-bold text-slate-900">{money(pm.amount)}</span>
                      </div>
                      <div className="mt-1.5 flex items-center justify-between text-xs text-slate-500">
                        <span>{pm.transactionCount} transactions</span>
                        <span>{pm.sharePct}% share</span>
                      </div>
                      <div className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
                        <div
                          className="h-full rounded-full"
                          style={{
                            width: `${Math.min(100, Number(pm.sharePct))}%`,
                            backgroundColor: COLORS[idx % COLORS.length],
                          }}
                        />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </Card>

            <Card className="p-0 lg:col-span-2">
              <div className="border-b border-slate-100 p-4">
                <h3 className="flex items-center gap-1.5 text-sm font-semibold text-slate-800">
                  <Users className="h-4 w-4 text-indigo-600" /> Sales by Cashier Performance
                </h3>
                <p className="text-xs text-slate-500">Completed bills, quantity billed, revenue, discounts and profit per cashier</p>
              </div>
              {data.cashiers.length === 0 ? (
                <Empty text="No cashier activity in this period" />
              ) : (
                <TableWrap>
                  <thead>
                    <tr>
                      <th className="th">Cashier</th>
                      <th className="th text-right">Bills</th>
                      <th className="th text-right">Qty Sold</th>
                      <th className="th text-right">Revenue</th>
                      <th className="th text-right">Avg Bill</th>
                      <th className="th text-right">Discounts</th>
                      <th className="th text-right">Profit</th>
                      <th className="th text-right">Share %</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {data.cashiers.map((c) => (
                      <tr key={c.cashierId} className="hover:bg-slate-50/70">
                        <td className="td font-medium text-slate-900">{c.cashierName}</td>
                        <td className="td text-right">{c.billCount}</td>
                        <td className="td text-right">{num(c.quantitySold)}</td>
                        <td className="td text-right font-semibold text-slate-900">{money(c.revenue)}</td>
                        <td className="td text-right text-slate-600">{money(c.avgBillValue)}</td>
                        <td className="td text-right text-amber-700">{money(c.discountGiven)}</td>
                        <td className="td text-right font-medium text-emerald-700">{money(c.profit)}</td>
                        <td className="td text-right">{c.sharePct}%</td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              )}
            </Card>
          </div>
        </>
      )}
    </div>
  )
}
