import { useEffect, useState } from 'react'
import api from '../api'
import { Badge, Card, Empty, PageHeader, Pagination, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, fmtDateTime } from '../utils'

const ENTITIES = ['PRODUCT', 'SALE', 'SUPPLIER', 'PURCHASE_ORDER', 'EXPENSE', 'SHIFT', 'USER']
const pretty = (s) => { try { return JSON.stringify(JSON.parse(s), null, 2) } catch { return s } }

export default function AuditLogs() {
  const toast = useToast()
  const [data, setData] = useState(null)
  const [f, setF] = useState({ entityType: '', action: '', username: '', from: '', to: '' })
  const [page, setPage] = useState(0)
  useEffect(() => {
    const t = setTimeout(() => api.get('/audit-logs', { params: { page, size: 30, ...Object.fromEntries(Object.entries(f).filter(([, v]) => v)) } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error')), 200)
    return () => clearTimeout(t)
  }, [page, f]) // eslint-disable-line
  const set = (k) => (e) => { setPage(0); setF({ ...f, [k]: e.target.value }) }
  return (
    <div>
      <PageHeader title="Audit log" subtitle="Immutable record of who changed what — enforced by a database trigger" />
      <Card>
        <div className="grid gap-2 border-b border-slate-200 p-3 sm:grid-cols-2 lg:grid-cols-5">
          <select className="input" value={f.entityType} onChange={set('entityType')}><option value="">All entities</option>{ENTITIES.map((e) => <option key={e}>{e}</option>)}</select>
          <input className="input" placeholder="Action (e.g. UPDATE)" value={f.action} onChange={set('action')} />
          <input className="input" placeholder="Username" value={f.username} onChange={set('username')} />
          <input className="input" type="date" value={f.from} onChange={set('from')} /><input className="input" type="date" value={f.to} onChange={set('to')} />
        </div>
        {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No audit entries" /> : (
          <TableWrap>
            <thead><tr><th className="th">When</th><th className="th">User</th><th className="th">Action</th><th className="th">Entity</th><th className="th">Details</th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {data.content.map((a) => (
                <tr key={a.id} className="align-top"><td className="td text-slate-500">{fmtDateTime(a.createdAt)}</td><td className="td">{a.username}</td><td className="td"><Badge tone="indigo">{a.action}</Badge></td><td className="td">{a.entityType} {a.entityId && `#${a.entityId}`}</td>
                  <td className="td whitespace-normal">{(a.oldValue || a.newValue) ? <details className="text-xs"><summary className="cursor-pointer text-indigo-600">View changes</summary><div className="mt-1 grid gap-2 sm:grid-cols-2">{a.oldValue && <pre className="overflow-auto rounded bg-rose-50 p-2">{pretty(a.oldValue)}</pre>}{a.newValue && <pre className="overflow-auto rounded bg-emerald-50 p-2">{pretty(a.newValue)}</pre>}</div></details> : '—'}</td></tr>
              ))}
            </tbody>
          </TableWrap>
        )}
        {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
      </Card>
    </div>
  )
}
