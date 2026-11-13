import { useCallback, useEffect, useState } from 'react'
import { Printer } from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import Receipt from '../components/Receipt.jsx'
import { Badge, Button, Card, Empty, Modal, PageHeader, Pagination, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { errMsg, fmtDateTime, money } from '../utils'

export default function Sales() {
  const { can } = useAuth()
  const toast = useToast()
  const mgmt = can('OWNER', 'MANAGER')
  const [data, setData] = useState(null)
  const [f, setF] = useState({ invoice: '', from: '', to: '' })
  const [page, setPage] = useState(0)
  const [sel, setSel] = useState(null)
  const load = useCallback(() => api.get('/sales', { params: { page, size: 20, invoice: f.invoice || undefined, from: f.from || undefined, to: f.to || undefined } }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error')), [page, f]) // eslint-disable-line
  useEffect(() => { const t = setTimeout(load, 200); return () => clearTimeout(t) }, [load])
  return (
    <div>
      <PageHeader title="Sales" subtitle={mgmt ? 'All bills' : 'Your bills from today'} />
      <Card>
        {mgmt && <div className="grid gap-2 border-b border-slate-200 p-3 sm:grid-cols-3"><input className="input" placeholder="Invoice no." value={f.invoice} onChange={(e) => { setPage(0); setF({ ...f, invoice: e.target.value }) }} /><input className="input" type="date" value={f.from} onChange={(e) => { setPage(0); setF({ ...f, from: e.target.value }) }} /><input className="input" type="date" value={f.to} onChange={(e) => { setPage(0); setF({ ...f, to: e.target.value }) }} /></div>}
        {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No sales" /> : (
          <TableWrap>
            <thead><tr><th className="th">Invoice</th><th className="th">Time</th><th className="th">Cashier</th><th className="th">Items</th><th className="th">Paid via</th><th className="th text-right">Total</th></tr></thead>
            <tbody className="divide-y divide-slate-100">{data.content.map((s) => <tr key={s.id} className="cursor-pointer hover:bg-slate-50" onClick={() => setSel(s)}><td className="td font-medium text-indigo-600">{s.invoiceNo}</td><td className="td text-slate-500">{fmtDateTime(s.createdAt)}</td><td className="td">{s.cashier}</td><td className="td">{s.items.length}</td><td className="td">{s.payments.map((p) => <Badge key={p.method} tone={p.method === 'CASH' ? 'emerald' : 'indigo'}>{p.method}</Badge>)}</td><td className="td text-right font-medium">{money(s.total)}</td></tr>)}</tbody>
          </TableWrap>
        )}
        {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
      </Card>
      <Modal open={!!sel} onClose={() => setSel(null)} title={sel?.invoiceNo} footer={<Button variant="secondary" onClick={() => window.print()}><Printer className="h-4 w-4" />Print</Button>}><Receipt sale={sel} /></Modal>
    </div>
  )
}
