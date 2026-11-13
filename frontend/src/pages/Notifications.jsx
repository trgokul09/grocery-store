import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { AlertTriangle, Banknote, CalendarX2, CheckCheck, ClipboardCheck } from 'lucide-react'
import api from '../api'
import { Button, Card, Empty, PageHeader, Pagination, Spinner, cn, useToast } from '../components/ui.jsx'
import { errMsg, fmtDateTime } from '../utils'

const ICON = { LOW_STOCK: AlertTriangle, EXPIRY: CalendarX2, PO_APPROVAL: ClipboardCheck, SHIFT_VARIANCE: Banknote }
const TONE = { LOW_STOCK: 'text-rose-600 bg-rose-50', EXPIRY: 'text-amber-600 bg-amber-50', PO_APPROVAL: 'text-indigo-600 bg-indigo-50', SHIFT_VARIANCE: 'text-rose-600 bg-rose-50' }
const LINK = { PRODUCT: '/inventory', PURCHASE_ORDER: '/purchase-orders', SHIFT: '/shifts' }

export default function Notifications() {
  const toast = useToast()
  const nav = useNavigate()
  const [data, setData] = useState(null)
  const [page, setPage] = useState(0)
  const load = useCallback(() => api.get('/notifications', { params: { page, size: 20 } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error')), [page]) // eslint-disable-line
  useEffect(() => { load(); const t = setInterval(load, 30000); return () => clearInterval(t) }, [load])

  const open = async (n) => { if (!n.read) await api.post(`/notifications/${n.id}/read`).catch(() => {}); if (LINK[n.refType]) nav(LINK[n.refType]); else load() }
  const all = async () => { await api.post('/notifications/read-all'); load() }

  return (
    <div>
      <PageHeader title="Notifications" subtitle="Low stock, expiring products, PO approvals and shift variances"><Button variant="secondary" size="sm" onClick={all}><CheckCheck className="h-4 w-4" />Mark all read</Button></PageHeader>
      <Card>
        {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="You're all caught up" /> : (
          <ul className="divide-y divide-slate-100">
            {data.content.map((n) => { const Icon = ICON[n.type] || AlertTriangle; return (
              <li key={n.id}><button onClick={() => open(n)} className={cn('flex w-full items-start gap-3 p-3 text-left hover:bg-slate-50', !n.read && 'bg-emerald-50/40')}>
                <span className={cn('mt-0.5 rounded-lg p-2', TONE[n.type])}><Icon className="h-4 w-4" /></span>
                <span className="min-w-0 flex-1"><span className={cn('block text-sm', !n.read && 'font-semibold')}>{n.message}</span><span className="text-xs text-slate-400">{fmtDateTime(n.createdAt)}</span></span>
                {!n.read && <span className="mt-2 h-2 w-2 rounded-full bg-emerald-500" />}
              </button></li>
            ) })}
          </ul>
        )}
        {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
      </Card>
    </div>
  )
}
