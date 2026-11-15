import { Printer } from 'lucide-react'
import { Badge, Button, Modal } from './ui.jsx'
import { fmtDate, fmtDateTime, money, num } from '../utils'

export default function PayslipModal({ payslip, onClose }) {
  if (!payslip) return null

  const statusTone =
    payslip.paymentStatus === 'PAID' ? 'emerald' :
    payslip.paymentStatus === 'PARTIALLY_PAID' ? 'amber' : 'rose'

  return (
    <Modal
      open={!!payslip}
      onClose={onClose}
      title={`Payslip · ${payslip.payslipNo}`}
      wide
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>Close</Button>
          <Button onClick={() => window.print()}>
            <Printer className="h-4 w-4" /> Print / Save PDF
          </Button>
        </>
      }
    >
      <div className="space-y-4 text-sm text-slate-700 print:p-2">
        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
          <div>
            <h2 className="text-lg font-bold text-slate-900">Smart Grocery Store</h2>
            <p className="text-xs text-slate-500">Official Monthly Salary Slip · Period: {payslip.payrollMonth}</p>
            <p className="mt-1 text-xs text-slate-400">Payslip No: <span className="font-mono font-semibold text-slate-700">{payslip.payslipNo}</span></p>
          </div>
          <div className="text-right">
            <Badge tone={statusTone}>{payslip.paymentStatus.replace('_', ' ')}</Badge>
            <p className="mt-1 text-xs text-slate-500">Generated: {fmtDate(payslip.createdAt)}</p>
            {payslip.lastPaidAt && <p className="text-xs text-emerald-700">Last Paid: {fmtDateTime(payslip.lastPaidAt)} ({payslip.lastPaymentMethod})</p>}
          </div>
        </div>

        {/* Employee & Bank Details */}
        <div className="grid gap-3 rounded-xl bg-slate-50 p-3.5 sm:grid-cols-2">
          <div className="space-y-1">
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Employee Information</p>
            <p className="font-semibold text-slate-900">{payslip.fullName} <span className="font-mono text-xs text-emerald-700">({payslip.employeeCode})</span></p>
            <p className="text-xs text-slate-600">Designation: {payslip.designation}</p>
            {payslip.phone && <p className="text-xs text-slate-600">Phone: {payslip.phone}</p>}
          </div>
          <div className="space-y-1">
            <p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Salary & Bank Details</p>
            <p className="text-xs text-slate-600">Wage Type: <span className="font-medium text-slate-800">{payslip.salaryType}</span> (Base: {money(payslip.basicSalary)} · Daily: {money(payslip.dailyWage)})</p>
            {payslip.bankAccountNo && <p className="text-xs text-slate-600">Bank A/C: {payslip.bankAccountNo} {payslip.ifscCode ? `(${payslip.ifscCode})` : ''}</p>}
            {payslip.upiId && <p className="text-xs text-slate-600">UPI ID: {payslip.upiId}</p>}
          </div>
        </div>

        {/* Attendance Breakdown */}
        <div className="grid grid-cols-3 gap-2 rounded-xl border border-slate-200 p-3 text-center sm:grid-cols-7">
          <div><p className="text-[11px] text-slate-400">Std Days</p><p className="font-semibold text-slate-800">{payslip.workingDays}</p></div>
          <div><p className="text-[11px] text-slate-400">Present</p><p className="font-semibold text-emerald-700">{num(payslip.presentDays, 1)}</p></div>
          <div><p className="text-[11px] text-slate-400">Half Days</p><p className="font-semibold text-amber-700">{payslip.halfDays}</p></div>
          <div><p className="text-[11px] text-slate-400">Paid Leave</p><p className="font-semibold text-indigo-700">{num(payslip.paidLeaveDays, 1)}</p></div>
          <div><p className="text-[11px] text-slate-400">Unpaid / Absent</p><p className="font-semibold text-rose-600">{num(Number(payslip.unpaidLeaveDays) + Number(payslip.absentDays), 1)}</p></div>
          <div><p className="text-[11px] text-slate-400">Payable Days</p><p className="font-bold text-slate-900">{num(payslip.payableDays, 1)}</p></div>
          <div><p className="text-[11px] text-slate-400">Overtime</p><p className="font-semibold text-indigo-700">{num(payslip.overtimeHours, 2)} hrs</p></div>
        </div>

        {/* Earnings & Deductions Columns */}
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="rounded-xl border border-slate-200 p-3.5">
            <h4 className="mb-2 border-b border-slate-100 pb-1.5 text-xs font-bold uppercase tracking-wider text-emerald-700">Earnings</h4>
            <div className="space-y-1.5">
              <div className="flex justify-between"><span>Earned Basic Salary</span><span className="font-medium">{money(payslip.earnedBasic)}</span></div>
              <div className="flex justify-between"><span>Overtime Pay ({num(payslip.overtimeHours, 2)} hrs)</span><span className="font-medium">{money(payslip.overtimePay)}</span></div>
              <div className="flex justify-between"><span>Bonus</span><span className="font-medium">{money(payslip.bonus)}</span></div>
              <div className="flex justify-between"><span>Incentives</span><span className="font-medium">{money(payslip.incentives)}</span></div>
              <div className="flex justify-between border-t border-slate-200 pt-2 font-bold text-slate-900">
                <span>Gross Salary</span><span>{money(payslip.grossSalary)}</span>
              </div>
            </div>
          </div>

          <div className="rounded-xl border border-slate-200 p-3.5">
            <h4 className="mb-2 border-b border-slate-100 pb-1.5 text-xs font-bold uppercase tracking-wider text-rose-700">Deductions</h4>
            <div className="space-y-1.5">
              <div className="flex justify-between"><span>Leave / Absence Deduction</span><span className="font-medium text-rose-600">{money(payslip.leaveDeduction)}</span></div>
              <div className="flex justify-between"><span>Salary Advance Recovery</span><span className="font-medium text-rose-600">{money(payslip.advanceDeduction)}</span></div>
              <div className="flex justify-between"><span>Late / Other Deductions</span><span className="font-medium text-rose-600">{money(payslip.otherDeductions)}</span></div>
              <div className="flex justify-between border-t border-slate-200 pt-2 font-bold text-rose-700">
                <span>Total Deductions</span>
                <span>{money(Number(payslip.leaveDeduction) + Number(payslip.advanceDeduction) + Number(payslip.otherDeductions))}</span>
              </div>
            </div>
          </div>
        </div>

        {/* Net Payable & Settlement Box */}
        <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl bg-emerald-50/80 p-4 border border-emerald-200">
          <div>
            <p className="text-xs font-semibold uppercase tracking-wider text-emerald-800">Net Payable Salary</p>
            <p className="text-2xl font-bold text-emerald-950">{money(payslip.netSalary)}</p>
            {payslip.notes && <p className="mt-1 text-xs text-slate-600">Note: {payslip.notes}</p>}
          </div>
          <div className="space-y-1 text-right text-xs">
            <p className="text-slate-600">Amount Paid: <span className="font-bold text-emerald-700">{money(payslip.paidAmount)}</span></p>
            <p className="text-slate-600">Pending Balance: <span className="font-bold text-rose-600">{money(payslip.pendingAmount)}</span></p>
          </div>
        </div>

        {/* Payment Installments History */}
        {payslip.payments && payslip.payments.length > 0 && (
          <div>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">Disbursement History</h4>
            <table className="min-w-full divide-y divide-slate-200 border border-slate-200 rounded-lg text-xs">
              <thead className="bg-slate-50">
                <tr>
                  <th className="px-3 py-2 text-left font-medium text-slate-600">Date & Time</th>
                  <th className="px-3 py-2 text-left font-medium text-slate-600">Method</th>
                  <th className="px-3 py-2 text-left font-medium text-slate-600">Paid By</th>
                  <th className="px-3 py-2 text-left font-medium text-slate-600">Note</th>
                  <th className="px-3 py-2 text-right font-medium text-slate-600">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {payslip.payments.map((pm) => (
                  <tr key={pm.id}>
                    <td className="px-3 py-2">{fmtDateTime(pm.paidAt)}</td>
                    <td className="px-3 py-2"><Badge tone="indigo">{pm.method}</Badge></td>
                    <td className="px-3 py-2">{pm.paidByName}</td>
                    <td className="px-3 py-2 text-slate-500">{pm.note || '—'}</td>
                    <td className="px-3 py-2 text-right font-semibold text-emerald-700">{money(pm.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Modal>
  )
}
