import { useEffect, useRef, useState } from 'react'
import { Search } from 'lucide-react'
import api from '../api'
import { num, unitLabel } from '../utils'

/** Type-ahead product search. onPick(product) is called with the full ProductDto. */
export default function ProductPicker({ onPick, placeholder = 'Search product by name, SKU or barcode…', activeOnly = true, autoFocus }) {
  const [q, setQ] = useState('')
  const [rows, setRows] = useState([])
  const [open, setOpen] = useState(false)
  const box = useRef(null)

  useEffect(() => {
    if (!q.trim()) { setRows([]); return }
    const t = setTimeout(() => {
      api.get('/products', { params: { q, size: 8, ...(activeOnly ? { active: true } : {}) } }).then((r) => { setRows(r.data.content); setOpen(true) }).catch(() => {})
    }, 200)
    return () => clearTimeout(t)
  }, [q, activeOnly])

  useEffect(() => {
    const h = (e) => box.current && !box.current.contains(e.target) && setOpen(false)
    document.addEventListener('mousedown', h)
    return () => document.removeEventListener('mousedown', h)
  }, [])

  return (
    <div className="relative" ref={box}>
      <Search className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" />
      <input className="input pl-9" value={q} onChange={(e) => setQ(e.target.value)} onFocus={() => rows.length && setOpen(true)} placeholder={placeholder} autoFocus={autoFocus} />
      {open && rows.length > 0 && (
        <ul className="absolute z-20 mt-1 max-h-64 w-full overflow-auto rounded-lg border border-slate-200 bg-white shadow-lg">
          {rows.map((p) => (
            <li key={p.id}>
              <button type="button" className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-emerald-50"
                onClick={() => { onPick(p); setQ(''); setRows([]); setOpen(false) }}>
                <span className="min-w-0"><span className="block truncate font-medium">{p.name}</span><span className="text-xs text-slate-500">{p.sku}</span></span>
                <span className="shrink-0 text-xs text-slate-500">{num(p.currentStock)} {unitLabel(p.unit)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
