import { useCallback, useEffect, useState } from 'react'
import { AlertTriangle, Download, FileSpreadsheet, Layers, Pencil, Plus, Power, Search, Upload } from 'lucide-react'
import * as XLSX from 'xlsx'
import api from '../api'
import { useAuth } from '../auth.jsx'
import { Badge, Button, Card, Empty, Field, Modal, PageHeader, Pagination, Spinner, TableWrap, useToast } from '../components/ui.jsx'
import { UNITS, download, errMsg, fmtDate, money, num, unitLabel } from '../utils'

const blank = { name: '', sku: '', barcode: '', brand: '', categoryId: '', purchasePrice: '', sellingPrice: '', minStockLevel: '0', unit: 'PCS', expiryDate: '', openingStock: '' }

export default function Products() {
  const { can } = useAuth()
  const toast = useToast()
  const owner = can('OWNER')
  const [data, setData] = useState(null)
  const [cats, setCats] = useState([])
  const [f, setF] = useState({ q: '', categoryId: '', active: 'true', lowStockOnly: false })
  const [page, setPage] = useState(0)
  const [edit, setEdit] = useState(null)
  const [imp, setImp] = useState(false)
  const [catOpen, setCatOpen] = useState(false)

  const loadCats = useCallback(() => api.get('/categories').then((r) => setCats(r.data)).catch(() => {}), [])
  const load = useCallback(() => {
    const params = { page, size: 15, q: f.q || undefined, categoryId: f.categoryId || undefined, lowStockOnly: f.lowStockOnly || undefined, active: f.active === 'all' ? undefined : f.active === 'true' }
    api.get('/products', { params }).then((r) => setData(r.data)).catch((e) => toast(errMsg(e), 'error'))
  }, [page, f]) // eslint-disable-line
  useEffect(() => { loadCats() }, [loadCats])
  useEffect(() => { const t = setTimeout(load, 200); return () => clearTimeout(t) }, [load])

  const toggle = async (p) => {
    if (p.active && !window.confirm(`Deactivate "${p.name}"? History is kept; it just disappears from the POS.`)) return
    try { await (p.active ? api.delete(`/products/${p.id}`) : api.patch(`/products/${p.id}/activate`)); toast(p.active ? 'Product deactivated' : 'Product activated'); load() } catch (e) { toast(errMsg(e), 'error') }
  }

  return (
    <div>
      <PageHeader title="Products" subtitle="Catalog, pricing and stock levels">
        {can('OWNER', 'MANAGER') && <Button variant="secondary" size="sm" onClick={() => setCatOpen(true)}><Layers className="h-4 w-4" />Categories</Button>}
        {owner && <Button variant="secondary" size="sm" onClick={() => setImp(true)}><FileSpreadsheet className="h-4 w-4" />Import Excel/CSV</Button>}
        {owner && <Button size="sm" onClick={() => setEdit({ ...blank })}><Plus className="h-4 w-4" />Add product</Button>}
      </PageHeader>

      <Card>
        <div className="grid gap-2 border-b border-slate-200 p-3 sm:grid-cols-2 lg:grid-cols-4">
          <div className="relative"><Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" /><input className="input pl-9" placeholder="Search name, SKU, barcode" value={f.q} onChange={(e) => { setPage(0); setF({ ...f, q: e.target.value }) }} /></div>
          <select className="input" value={f.categoryId} onChange={(e) => { setPage(0); setF({ ...f, categoryId: e.target.value }) }}><option value="">All categories</option>{cats.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select>
          {can('OWNER', 'MANAGER') && <select className="input" value={f.active} onChange={(e) => { setPage(0); setF({ ...f, active: e.target.value }) }}><option value="true">Active</option><option value="false">Inactive</option><option value="all">All</option></select>}
          <label className="flex items-center gap-2 text-sm text-slate-600"><input type="checkbox" checked={f.lowStockOnly} onChange={(e) => { setPage(0); setF({ ...f, lowStockOnly: e.target.checked }) }} />Low stock only</label>
        </div>
        {!data ? <Spinner /> : data.content.length === 0 ? <Empty text="No products found" /> : (
          <TableWrap>
            <thead><tr><th className="th">Product</th><th className="th">SKU</th><th className="th">Category</th>{owner && <th className="th text-right">Cost</th>}<th className="th text-right">Price</th><th className="th text-right">Stock</th><th className="th">Expiry</th><th className="th">Status</th>{owner && <th className="th" />}</tr></thead>
            <tbody className="divide-y divide-slate-100">
              {data.content.map((p) => (
                <tr key={p.id} className={!p.active ? 'bg-slate-50 text-slate-400' : ''}>
                  <td className="td"><p className="max-w-[220px] truncate font-medium">{p.name}</p><p className="text-xs text-slate-400">{p.brand}{p.barcode && ` · ${p.barcode}`}</p></td>
                  <td className="td">{p.sku}</td><td className="td">{p.categoryName || '—'}</td>
                  {owner && <td className="td text-right">{money(p.purchasePrice)}</td>}
                  <td className="td text-right font-medium">{money(p.sellingPrice)}</td>
                  <td className="td text-right">{p.lowStock && p.active && <AlertTriangle className="mr-1 inline h-3.5 w-3.5 text-rose-500" />}{num(p.currentStock)} {unitLabel(p.unit)}</td>
                  <td className="td">{fmtDate(p.expiryDate)}</td>
                  <td className="td">{p.active ? <Badge tone="emerald">Active</Badge> : <Badge>Inactive</Badge>}</td>
                  {owner && <td className="td"><div className="flex gap-1"><Button variant="ghost" size="sm" onClick={() => setEdit({ ...blank, ...p, categoryId: p.categoryId || '', barcode: p.barcode || '', brand: p.brand || '', expiryDate: p.expiryDate || '', openingStock: undefined })}><Pencil className="h-4 w-4" /></Button><Button variant="ghost" size="sm" onClick={() => toggle(p)}><Power className="h-4 w-4" /></Button></div></td>}
                </tr>
              ))}
            </tbody>
          </TableWrap>
        )}
        {data && <Pagination page={data.number} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />}
      </Card>

      {edit && <ProductForm initial={edit} cats={cats} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); load() }} />}
      {imp && <ImportModal onClose={() => setImp(false)} onDone={() => { load(); loadCats() }} />}
      {catOpen && <CategoryModal onClose={() => setCatOpen(false)} onChange={loadCats} />}
    </div>
  )
}

function ProductForm({ initial, cats, onClose, onSaved }) {
  const toast = useToast()
  const [v, setV] = useState(initial)
  const [busy, setBusy] = useState(false)
  const isNew = !initial.id
  const set = (k) => (e) => setV({ ...v, [k]: e.target.value })

  const save = async () => {
    setBusy(true)
    const body = {
      name: v.name, sku: v.sku, barcode: v.barcode || null, brand: v.brand || null, categoryId: v.categoryId || null, unit: v.unit,
      purchasePrice: Number(v.purchasePrice), sellingPrice: Number(v.sellingPrice), minStockLevel: Number(v.minStockLevel || 0),
      expiryDate: v.expiryDate || null, ...(isNew && v.openingStock ? { openingStock: Number(v.openingStock) } : {}),
    }
    try { await (isNew ? api.post('/products', body) : api.put(`/products/${initial.id}`, body)); toast('Product saved'); onSaved() } catch (e) { toast(errMsg(e), 'error') } finally { setBusy(false) }
  }

  return (
    <Modal open onClose={onClose} title={isNew ? 'Add product' : 'Edit product'} wide footer={<><Button variant="secondary" onClick={onClose}>Cancel</Button><Button onClick={save} loading={busy}>Save</Button></>}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Name *" className="sm:col-span-2"><input className="input" value={v.name} onChange={set('name')} /></Field>
        <Field label="SKU * (unique)"><input className="input" value={v.sku} onChange={set('sku')} /></Field>
        <Field label="Barcode (optional)"><input className="input" value={v.barcode} onChange={set('barcode')} /></Field>
        <Field label="Brand"><input className="input" value={v.brand} onChange={set('brand')} /></Field>
        <Field label="Category"><select className="input" value={v.categoryId} onChange={set('categoryId')}><option value="">— none —</option>{cats.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
        <Field label="Purchase price (₹) *"><input className="input" type="number" inputMode="decimal" min="0" step="0.01" value={v.purchasePrice} onChange={set('purchasePrice')} /></Field>
        <Field label="Selling price (₹) *"><input className="input" type="number" inputMode="decimal" min="0" step="0.01" value={v.sellingPrice} onChange={set('sellingPrice')} /></Field>
        <Field label="Unit *"><select className="input" value={v.unit} onChange={set('unit')}>{UNITS.map((u) => <option key={u} value={u}>{unitLabel(u)}</option>)}</select></Field>
        <Field label="Min stock level"><input className="input" type="number" inputMode="decimal" min="0" value={v.minStockLevel} onChange={set('minStockLevel')} /></Field>
        <Field label="Expiry date"><input className="input" type="date" value={v.expiryDate} onChange={set('expiryDate')} /></Field>
        {isNew ? <Field label="Opening stock" hint="Logged as a stock movement"><input className="input" type="number" inputMode="decimal" min="0" value={v.openingStock} onChange={set('openingStock')} /></Field>
          : <Field label="Current stock"><input className="input" disabled value={`${num(initial.currentStock)} ${unitLabel(initial.unit)}`} readOnly /><span className="mt-0.5 block text-xs text-slate-400">Change stock via Inventory → Adjust</span></Field>}
      </div>
    </Modal>
  )
}

function ImportModal({ onClose, onDone }) {
  const toast = useToast()
  const [file, setFile] = useState(null)
  const [preview, setPreview] = useState(null)
  const [result, setResult] = useState(null)
  const [busy, setBusy] = useState(false)
  const REQUIRED = ['sku', 'name', 'purchase_price', 'selling_price', 'unit']

  const template = async () => {
    try { const r = await api.get('/products/import/template', { responseType: 'blob' }); download(r.data, 'product-import-template.xlsx') } catch (e) { toast(errMsg(e), 'error') }
  }

  // SheetJS: parse locally first so obvious problems are caught before upload
  const pick = async (e) => {
    const fl = e.target.files?.[0]
    setFile(fl || null); setResult(null); setPreview(null)
    if (!fl) return
    try {
      const wb = XLSX.read(await fl.arrayBuffer(), { type: 'array' })
      const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { defval: '' })
      const heads = rows.length ? Object.keys(rows[0]).map((h) => h.trim().toLowerCase()) : []
      setPreview({ rows: rows.length, head: rows.slice(0, 5), missing: REQUIRED.filter((c) => !heads.includes(c)) })
    } catch { setPreview({ rows: 0, head: [], missing: [], error: 'Could not read this file' }) }
  }

  const upload = async () => {
    setBusy(true)
    const fd = new FormData(); fd.append('file', file)
    try {
      const { data } = await api.post('/products/import', fd)
      setResult(data); toast(`${data.imported} products imported`); onDone()
    } catch (e) {
      if (e.response?.status === 422) setResult(e.response.data); else toast(errMsg(e), 'error')
    } finally { setBusy(false) }
  }

  const errorReport = () => {
    const ws = XLSX.utils.json_to_sheet(result.errors.map((x) => ({ row: x.row, sku: x.sku, error: x.message })))
    const wb = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(wb, ws, 'Errors')
    XLSX.writeFile(wb, 'import-errors.xlsx')
  }

  return (
    <Modal open onClose={onClose} title="Bulk import products" wide footer={<><Button variant="secondary" onClick={onClose}>Close</Button><Button onClick={upload} loading={busy} disabled={!file || preview?.missing?.length > 0 || result?.success}><Upload className="h-4 w-4" />Import</Button></>}>
      <div className="space-y-4">
        <div className="flex flex-wrap items-center gap-3 rounded-xl bg-slate-50 p-3 text-sm">
          <Button variant="secondary" size="sm" onClick={template}><Download className="h-4 w-4" />Download template (.xlsx)</Button>
          <span className="text-slate-500">All rows are validated first. If any row fails, <b>nothing</b> is imported.</span>
        </div>
        <input type="file" accept=".xlsx,.csv" onChange={pick} className="block w-full text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-emerald-50 file:px-3 file:py-2 file:text-emerald-700" />
        {preview?.error && <p className="text-sm text-rose-600">{preview.error}</p>}
        {preview?.missing?.length > 0 && <p className="rounded-lg bg-rose-50 p-2 text-sm text-rose-700">Missing required columns: {preview.missing.join(', ')}</p>}
        {preview && !preview.error && <p className="text-sm text-slate-600">{preview.rows} data row(s) detected{preview.head.length > 0 && ' — preview of the first rows:'}</p>}
        {preview?.head?.length > 0 && <TableWrap className="max-h-48 rounded-lg border border-slate-200"><thead><tr>{Object.keys(preview.head[0]).map((h) => <th key={h} className="th">{h}</th>)}</tr></thead><tbody>{preview.head.map((r, i) => <tr key={i}>{Object.values(r).map((c, j) => <td key={j} className="td">{String(c)}</td>)}</tr>)}</tbody></TableWrap>}
        {result?.success && <p className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-700">Imported {result.imported} of {result.totalRows} rows successfully.</p>}
        {result && !result.success && (
          <div>
            <div className="mb-2 flex items-center justify-between"><p className="text-sm font-medium text-rose-700">{result.errors.length} problem(s) found — nothing was imported</p><Button variant="secondary" size="sm" onClick={errorReport}><Download className="h-4 w-4" />Error report</Button></div>
            <TableWrap className="max-h-56 rounded-lg border border-rose-200"><thead><tr><th className="th">Row</th><th className="th">SKU</th><th className="th">Problem</th></tr></thead><tbody>{result.errors.map((x, i) => <tr key={i}><td className="td">{x.row}</td><td className="td">{x.sku}</td><td className="td whitespace-normal text-rose-700">{x.message}</td></tr>)}</tbody></TableWrap>
          </div>
        )}
      </div>
    </Modal>
  )
}

function CategoryModal({ onClose, onChange }) {
  const toast = useToast()
  const [list, setList] = useState([])
  const [name, setName] = useState('')
  const load = () => api.get('/categories').then((r) => setList(r.data))
  useEffect(() => { load() }, [])
  const act = async (fn, msg) => { try { await fn(); toast(msg); load(); onChange() } catch (e) { toast(errMsg(e), 'error') } }
  return (
    <Modal open onClose={onClose} title="Categories">
      <div className="mb-3 flex gap-2"><input className="input" placeholder="New category name" value={name} onChange={(e) => setName(e.target.value)} /><Button onClick={() => act(async () => { await api.post('/categories', { name }); setName('') }, 'Category added')} disabled={!name.trim()}>Add</Button></div>
      <ul className="divide-y divide-slate-100">
        {list.map((c) => <li key={c.id} className="flex items-center justify-between py-2 text-sm"><span>{c.name}</span><div className="flex gap-1"><Button variant="ghost" size="sm" onClick={() => { const n = window.prompt('Rename category', c.name); if (n && n.trim()) act(() => api.put(`/categories/${c.id}`, { name: n, description: c.description }), 'Renamed') }}><Pencil className="h-4 w-4" /></Button><Button variant="ghost" size="sm" onClick={() => window.confirm(`Deactivate "${c.name}"?`) && act(() => api.delete(`/categories/${c.id}`), 'Category deactivated')}><Power className="h-4 w-4" /></Button></div></li>)}
        {list.length === 0 && <Empty text="No categories yet" />}
      </ul>
    </Modal>
  )
}
