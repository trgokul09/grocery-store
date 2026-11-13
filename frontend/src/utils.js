const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 })
export const money = (v) => inr.format(Number(v ?? 0))
export const num = (v, d = 3) => (v === null || v === undefined || v === '' ? '' : Number(Number(v).toFixed(d)).toString())
export const fmtDate = (v) => (v ? new Date(v).toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) : '—')
export const fmtDateTime = (v) => (v ? new Date(v).toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—')
export const isoToday = () => { const d = new Date(); d.setMinutes(d.getMinutes() - d.getTimezoneOffset()); return d.toISOString().slice(0, 10) }

export function errMsg(e) {
  const d = e?.response?.data
  if (d?.details && typeof d.details === 'object') return Object.entries(d.details).map(([k, v]) => `${k}: ${v}`).join('; ')
  return d?.message || e?.message || 'Something went wrong'
}

export const UNITS = ['KG', 'G', 'L', 'ML', 'PCS', 'PKTS', 'BOXES']
export const unitLabel = (u) => ({ KG: 'kg', G: 'g', L: 'L', ML: 'ml', PCS: 'pcs', PKTS: 'pkts', BOXES: 'boxes' }[u] || u)
export const isCountable = (u) => ['PCS', 'PKTS', 'BOXES'].includes(u)

/**
 * Integer (paise / milli-unit) arithmetic that mirrors the backend's BigDecimal HALF_UP rounding,
 * so the bill total shown at the counter always equals what the server computes.
 */
const divRound = (a, b) => Math.floor((2 * a + b) / (2 * b))
export function calcLine(price, qty, discount, taxRate) {
  const pricePaise = Math.round(Number(price) * 100)
  const qtyMilli = Math.round(Number(qty || 0) * 1000)
  const gross = divRound(pricePaise * qtyMilli, 1000)
  const disc = Math.min(Math.round(Number(discount || 0) * 100), gross)
  const net = gross - disc
  const tax = divRound(net * Math.round(Number(taxRate || 0) * 100), 10000)
  return { gross: gross / 100, discount: disc / 100, net: net / 100, tax: tax / 100, total: (net + tax) / 100 }
}

export function download(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = filename; a.click()
  URL.revokeObjectURL(url)
}

export const debounce = (fn, ms = 250) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms) } }
