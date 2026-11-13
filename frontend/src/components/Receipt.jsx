import { money, num, fmtDateTime } from '../utils'

export default function Receipt({ sale }) {
  if (!sale) return null
  return (
    <div id="print-area" className="mx-auto w-full max-w-xs rounded-lg border border-dashed border-slate-300 bg-white p-4 font-mono text-xs text-slate-800">
      <p className="text-center text-sm font-bold">SMART GROCERY</p>
      <p className="text-center">{sale.invoiceNo}</p>
      <p className="text-center">{fmtDateTime(sale.createdAt)} · {sale.cashier}</p>
      {sale.customerName && <p className="text-center">Customer: {sale.customerName}</p>}
      <hr className="my-2 border-dashed" />
      {sale.items.map((i, k) => (
        <div key={k} className="mb-1">
          <div className="flex justify-between gap-2"><span className="truncate">{i.productName}</span><span>{money(i.lineTotal)}</span></div>
          <div className="text-slate-500">{num(i.quantity)} × {money(i.unitPrice)}{Number(i.discount) > 0 && ` − ${money(i.discount)}`}</div>
        </div>
      ))}
      <hr className="my-2 border-dashed" />
      <div className="flex justify-between"><span>Subtotal</span><span>{money(sale.subtotal)}</span></div>
      {Number(sale.discountTotal) > 0 && <div className="flex justify-between"><span>Discount</span><span>−{money(sale.discountTotal)}</span></div>}
      <div className="flex justify-between text-sm font-bold"><span>TOTAL</span><span>{money(sale.total)}</span></div>
      <hr className="my-2 border-dashed" />
      {sale.payments.map((p, k) => <div key={k} className="flex justify-between"><span>{p.method}</span><span>{money(p.amount)}</span></div>)}
      <p className="mt-3 text-center">Thank you! Visit again.</p>
    </div>
  )
}
