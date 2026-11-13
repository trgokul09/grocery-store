import { useCallback, useEffect, useState } from 'react'
import { AlertCircle, FileText, RefreshCw } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Badge, Button, Card, Empty, PageHeader, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, money, num, unitLabel } from '../utils'

export default function Reorder() {
  const { can } = useAuth()
  const toast = useToast()
  const [groups, setGroups] = useState(null)
  const [qty, setQty] = useState({})
  const [busy, setBusy] = useState(null)
  const load = useCallback(() => api.get('/reorder/recommendations').then((r) => {
    setGroups(r.data)
    const q = {}; r.data.forEach((g) => g.items.forEach((i) => { q[i.productId] = i.suggestedQty })); setQty(q)
  }).catch((e) => toast(errMsg(e), 'error')), []) // eslint-disable-line
  useEffect(() => { load() }, [load])

  const convert = async (g) => {
    setBusy(g.supplierId)
    try {
      const { data } = await api.post('/reorder/convert', { supplierId: g.supplierId, items: g.items.filter((i) => Number(qty[i.productId]) > 0).map((i) => ({ productId: i.productId, quantity: Number(qty[i.productId]), unitCost: i.unitCost })) })
      toast(`${data.poNumber} created (${data.status.replace('_', ' ').toLowerCase()})`)
      load()
    } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(null) }
  }

  return (
    <div>
      <PageHeader title="Smart reorder" subtitle="Reorder point = (avg daily sales × lead time) + safety stock"><Button variant="secondary" size="sm" onClick={load}><RefreshCw className="h-4 w-4" />Recalculate</Button></PageHeader>
      {!groups ? <Spinner /> : groups.length === 0 ? <Card><Empty text="All stock levels are healthy — nothing to reorder" /></Card> : (
        <div className="space-y-4">
          {groups.map((g) => (
            <Card key={g.supplierId ?? 'none'}>
              <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-200 p-3">
                <div><h3 className="font-semibold">{g.supplierName}</h3><p className="text-xs text-slate-500">{g.items.length} item(s) · estimated {money(g.estimatedTotal)}</p></div>
                {g.supplierId == null ? <Badge tone="amber"><AlertCircle className="mr-1 h-3 w-3" />Map a supplier under Suppliers → Supplied products</Badge>
                  : can('OWNER') && <Button size="sm" loading={busy === g.supplierId} onClick={() => convert(g)}><FileText className="h-4 w-4" />Create purchase order</Button>}
              </div>
              <TableWrap>
                <thead><tr><th className="th">Product</th><th className="th text-right">In stock</th><th className="th text-right">Avg/day</th><th className="th text-right">Lead</th><th className="th text-right">Safety</th><th className="th text-right">Reorder pt</th><th className="th w-28">Order qty</th><th className="th text-right">Cost</th></tr></thead>
                <tbody className="divide-y divide-slate-100">
                  {g.items.map((i) => (
                    <tr key={i.productId}>
                      <td className="td font-medium">{i.name}<span className="ml-2 text-xs text-slate-400">{unitLabel(i.unit)}</span></td>
                      <td className="td text-right text-rose-600">{num(i.currentStock)}</td><td className="td text-right">{num(i.avgDailySales, 2)}</td><td className="td text-right">{i.leadTimeDays}d</td>
                      <td className="td text-right">{num(i.safetyStock)}</td><td className="td text-right">{num(i.reorderPoint, 1)}</td>
                      <td className="td"><input className="input" type="number" min="0" value={qty[i.productId] ?? ''} disabled={!can('OWNER') || g.supplierId == null} onChange={(e) => setQty({ ...qty, [i.productId]: e.target.value })} /></td>
                      <td className="td text-right">{money(i.unitCost)}</td>
                    </tr>
                  ))}
                </tbody>
              </TableWrap>
            </Card>
          ))}
        </div>
      )}
    </div>
  )
}
