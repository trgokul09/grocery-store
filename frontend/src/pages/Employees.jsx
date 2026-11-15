import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle, Banknote, Briefcase, Calendar, CalendarCheck, CalendarDays,
  Check, CheckCircle2, Clock, Coins, Eye, FileText, IndianRupee, KeyRound,
  LogIn, LogOut, Pencil, Plus, Power, RefreshCw, Search, Settings, ShieldAlert,
  TrendingUp, UserCheck, UserPlus, Users, Wallet, X, XCircle,
} from 'lucide-react'
import api from '../api'
import { useAuth } from '../auth.jsx'
import PayslipModal from '../components/PayslipModal.jsx'
import {
  Badge, Button, Card, Empty, Field, Modal, PageHeader, Spinner, StatCard,
  TableWrap, Tabs, cn, useToast,
} from '../components/ui.jsx'
import { errMsg, fmtDate, fmtDateTime, isoToday, money, num } from '../utils'

const SHIFTS = ['MORNING', 'EVENING', 'FULL_DAY', 'GENERAL']
const EMP_STATUSES = ['ACTIVE', 'ON_LEAVE', 'INACTIVE']
const ATT_STATUSES = ['PRESENT', 'ABSENT', 'HALF_DAY', 'LEAVE', 'WEEK_OFF']
const LEAVE_TYPES = ['PAID', 'SICK', 'CASUAL', 'UNPAID']
const PAY_METHODS = ['CASH', 'UPI', 'CARD']

const statusBadgeTone = (st) => ({
  PRESENT: 'emerald',
  ACTIVE: 'emerald',
  APPROVED: 'emerald',
  PAID: 'emerald',
  HALF_DAY: 'amber',
  ON_LEAVE: 'amber',
  LEAVE: 'indigo',
  PENDING: 'amber',
  PARTIALLY_PAID: 'amber',
  ABSENT: 'rose',
  INACTIVE: 'rose',
  REJECTED: 'rose',
  WEEK_OFF: 'slate',
  NOT_MARKED: 'slate',
}[st] || 'slate')

export default function Employees() {
  const { user, can } = useAuth()
  const isOwner = can('OWNER')
  const isMgmt = can('OWNER', 'MANAGER')
  const toast = useToast()

  const currentYm = useMemo(() => isoToday().slice(0, 7), [])
  const [tab, setTab] = useState(isMgmt ? 'DASHBOARD' : 'ATTENDANCE')
  const [month, setMonth] = useState(currentYm)
  const [selectedDate, setSelectedDate] = useState(isoToday())

  // Data states
  const [dash, setDash] = useState(null)
  const [employees, setEmployees] = useState([])
  const [myProfile, setMyProfile] = useState(null)
  const [dailyAtt, setDailyAtt] = useState([])
  const [calendarData, setCalendarData] = useState(null)
  const [leaves, setLeaves] = useState([])
  const [leaveBalances, setLeaveBalances] = useState([])
  const [payrollList, setPayrollList] = useState([])
  const [salaryHistory, setSalaryHistory] = useState([])
  const [advances, setAdvances] = useState([])
  const [settings, setSettings] = useState(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  // Filters
  const [empSearch, setEmpSearch] = useState('')
  const [empStatusFilter, setEmpStatusFilter] = useState('ALL')
  const [empShiftFilter, setEmpShiftFilter] = useState('ALL')
  const [leaveStatusFilter, setLeaveStatusFilter] = useState('ALL')
  const [historyUserFilter, setHistoryUserFilter] = useState('')

  // Modals
  const [empForm, setEmpForm] = useState(null)
  const [viewProfile, setViewProfile] = useState(null)
  const [markModal, setMarkModal] = useState(null)
  const [leaveModal, setLeaveModal] = useState(null)
  const [reviewModal, setReviewModal] = useState(null)
  const [rulesModal, setRulesModal] = useState(null)
  const [advanceModal, setAdvanceModal] = useState(null)
  const [adjustModal, setAdjustModal] = useState(null)
  const [payModal, setPayModal] = useState(null)
  const [activePayslip, setActivePayslip] = useState(null)

  const loadAll = useCallback(async () => {
    setLoading(true)
    try {
      const [meRes, empRes, dashRes, dailyRes, calRes, lvsRes, balRes, payRes, histRes] = await Promise.all([
        api.get('/hr/employees/me'),
        api.get('/hr/employees', {
          params: {
            search: empSearch || undefined,
            status: empStatusFilter !== 'ALL' ? empStatusFilter : undefined,
            shift: empShiftFilter !== 'ALL' ? empShiftFilter : undefined,
          },
        }),
        api.get('/hr/dashboard', { params: { month } }),
        api.get('/hr/attendance/daily', { params: { date: selectedDate } }),
        api.get('/hr/attendance/calendar', { params: { month } }),
        api.get('/hr/leaves', { params: { status: leaveStatusFilter !== 'ALL' ? leaveStatusFilter : undefined } }),
        api.get('/hr/leaves/balances'),
        api.get('/hr/payroll', { params: { month } }),
        api.get('/hr/payroll', { params: { userId: historyUserFilter || undefined } }),
      ])
      setMyProfile(meRes.data)
      setEmployees(empRes.data || [])
      setDash(dashRes.data)
      setDailyAtt(dailyRes.data || [])
      setCalendarData(calRes.data)
      setLeaves(lvsRes.data || [])
      setLeaveBalances(balRes.data || [])
      setPayrollList(payRes.data || [])
      setSalaryHistory(histRes.data || [])

      if (isOwner) {
        const [setRes, advRes] = await Promise.all([
          api.get('/hr/payroll/settings'),
          api.get('/hr/advances'),
        ])
        setSettings(setRes.data)
        setAdvances(advRes.data || [])
      }
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setLoading(false)
    }
  }, [month, selectedDate, empSearch, empStatusFilter, empShiftFilter, leaveStatusFilter, historyUserFilter, isOwner]) // eslint-disable-line

  useEffect(() => {
    loadAll()
  }, [loadAll])

  // Self Check-In / Check-Out handlers
  const handleSelfCheckIn = async () => {
    setBusy(true)
    try {
      await api.post('/hr/attendance/check-in', {})
      toast('Checked in successfully')
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  const handleSelfCheckOut = async () => {
    setBusy(true)
    try {
      await api.post('/hr/attendance/check-out', {})
      toast('Checked out successfully')
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Save Employee (Create or Update)
  const saveEmployee = async () => {
    setBusy(true)
    try {
      if (empForm.userId) {
        await api.put(`/hr/employees/${empForm.userId}`, empForm)
        toast('Employee profile updated')
      } else {
        await api.post('/hr/employees', empForm)
        toast('Employee created')
      }
      setEmpForm(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  const toggleEmployeeActive = async (emp) => {
    try {
      await api.patch(`/hr/employees/${emp.userId}/active`, null, { params: { value: !emp.active } })
      toast(emp.active ? 'Employee deactivated' : 'Employee activated')
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    }
  }

  // Save Manual Attendance
  const saveAttendanceMark = async () => {
    setBusy(true)
    try {
      await api.post('/hr/attendance/mark', markModal)
      toast('Attendance saved')
      setMarkModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Submit Leave Application
  const submitLeave = async () => {
    setBusy(true)
    try {
      await api.post('/hr/leaves', leaveModal)
      toast('Leave application submitted')
      setLeaveModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Approve / Reject Leave
  const submitLeaveReview = async () => {
    setBusy(true)
    try {
      await api.post(`/hr/leaves/${reviewModal.id}/review`, {
        status: reviewModal.decision,
        reviewNote: reviewModal.reviewNote,
      })
      toast(`Leave request ${reviewModal.decision.toLowerCase()}`)
      setReviewModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Save Owner Payroll Settings
  const savePayrollRules = async () => {
    setBusy(true)
    try {
      await api.put('/hr/payroll/settings', rulesModal)
      toast('Payroll rules updated')
      setRulesModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Issue Salary Advance
  const saveSalaryAdvance = async () => {
    setBusy(true)
    try {
      await api.post('/hr/advances', advanceModal)
      toast('Salary advance recorded')
      setAdvanceModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Generate Monthly Payroll
  const handleGeneratePayroll = async () => {
    setBusy(true)
    try {
      await api.post('/hr/payroll/generate', null, { params: { month } })
      toast(`Payroll calculated for ${month}`)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Save Payroll Adjustments
  const savePayrollAdjustments = async () => {
    setBusy(true)
    try {
      await api.put(`/hr/payroll/${adjustModal.id}/adjustments`, {
        bonus: adjustModal.bonus,
        incentives: adjustModal.incentives,
        leaveDeduction: adjustModal.leaveDeduction,
        advanceDeduction: adjustModal.advanceDeduction,
        otherDeductions: adjustModal.otherDeductions,
        notes: adjustModal.notes,
      })
      toast('Payroll adjustments saved')
      setAdjustModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  // Pay Salary (Full or Partial)
  const submitSalaryPayment = async () => {
    setBusy(true)
    try {
      await api.post(`/hr/payroll/${payModal.id}/pay`, {
        amount: payModal.amount,
        method: payModal.method,
        note: payModal.note,
      })
      toast('Salary payment disbursed & recorded in store expenses')
      setPayModal(null)
      loadAll()
    } catch (e) {
      toast(errMsg(e), 'error')
    } finally {
      setBusy(false)
    }
  }

  const tabs = isMgmt
    ? [
        { value: 'DASHBOARD', label: 'Employee Dashboard' },
        { value: 'DIRECTORY', label: 'Employee Directory' },
        { value: 'ATTENDANCE', label: 'Attendance Dashboard' },
        { value: 'CALENDAR', label: 'Attendance Calendar' },
        { value: 'LEAVES', label: `Leave Management${dash?.pendingLeaveRequests ? ` (${dash.pendingLeaveRequests})` : ''}` },
        { value: 'PAYROLL', label: isOwner ? 'Payroll Dashboard' : 'My Payslips' },
        { value: 'HISTORY', label: 'Salary History' },
      ]
    : [
        { value: 'ATTENDANCE', label: 'My Attendance & Clock-In' },
        { value: 'CALENDAR', label: 'Monthly Calendar' },
        { value: 'LEAVES', label: 'My Leave Requests' },
        { value: 'HISTORY', label: 'My Payslips' },
        { value: 'DIRECTORY', label: 'My Employee Profile' },
      ]

  return (
    <div className="space-y-4">
      <PageHeader
        title="Employee, Attendance & Payroll Management"
        subtitle={`Logged in as ${user.fullName} (${user.role}) · Active Period: ${month}`}
      >
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="month"
            className="input h-8 w-auto text-xs"
            value={month}
            onChange={(e) => setMonth(e.target.value)}
          />
          <Button variant="secondary" size="sm" onClick={loadAll}>
            <RefreshCw className="h-3.5 w-3.5" /> Refresh
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() =>
              setLeaveModal({
                userId: user.id,
                leaveType: 'PAID',
                paid: true,
                startDate: isoToday(),
                endDate: isoToday(),
                halfDay: false,
                reason: '',
              })
            }
          >
            <CalendarDays className="h-3.5 w-3.5 text-indigo-600" /> Apply Leave
          </Button>
          {isMgmt && (
            <Button
              size="sm"
              onClick={() =>
                setEmpForm({
                  username: '',
                  password: '',
                  fullName: '',
                  role: 'STAFF',
                  employeeCode: '',
                  phone: '',
                  email: '',
                  designation: 'Cashier / Sales Associate',
                  joiningDate: isoToday(),
                  employmentStatus: 'ACTIVE',
                  assignedShift: 'GENERAL',
                  shiftStartTime: '09:00',
                  shiftEndTime: '18:00',
                  address: '',
                  salaryType: 'MONTHLY',
                  basicSalary: '15000.00',
                  dailyWage: '576.92',
                  overtimeHourlyRate: '100.00',
                  paidLeaveQuota: 12,
                  bankAccountNo: '',
                  ifscCode: '',
                  upiId: '',
                })
              }
            >
              <UserPlus className="h-3.5 w-3.5" /> Add Employee
            </Button>
          )}
        </div>
      </PageHeader>

      {/* Personal Self Check-In / Check-Out Bar */}
      {myProfile && (
        <Card className="flex flex-wrap items-center justify-between gap-3 border-emerald-200 bg-emerald-50/40 p-3.5">
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-600 font-bold text-white">
              {myProfile.fullName.charAt(0)}
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-semibold text-slate-900">{myProfile.fullName}</span>
                <Badge tone="emerald">{myProfile.employeeCode}</Badge>
                <Badge tone="indigo">{myProfile.assignedShift} ({myProfile.shiftStartTime}–{myProfile.shiftEndTime})</Badge>
              </div>
              <p className="mt-0.5 text-xs text-slate-600">
                Today ({fmtDate(isoToday())}): <Badge tone={statusBadgeTone(myProfile.todayStatus)}>{myProfile.todayStatus}</Badge>
                {myProfile.todayCheckIn && ` · In: ${fmtDateTime(myProfile.todayCheckIn)}`}
                {myProfile.todayCheckOut && ` · Out: ${fmtDateTime(myProfile.todayCheckOut)}`}
                {myProfile.todayLate && <span className="ml-1 font-medium text-rose-600">(Late Arrival)</span>}
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            {!myProfile.todayCheckIn ? (
              <Button size="sm" onClick={handleSelfCheckIn} loading={busy}>
                <LogIn className="h-4 w-4" /> Check In Now
              </Button>
            ) : !myProfile.todayCheckOut ? (
              <Button variant="indigo" size="sm" onClick={handleSelfCheckOut} loading={busy}>
                <LogOut className="h-4 w-4" /> Check Out Now
              </Button>
            ) : (
              <Badge tone="emerald"><CheckCircle2 className="mr-1 h-3.5 w-3.5" /> Shift Attendance Completed</Badge>
            )}
          </div>
        </Card>
      )}

      <Tabs tabs={tabs} value={tab} onChange={setTab} />

      {loading && !dash ? (
        <Spinner />
      ) : (
        <>
          {/* ================================================================
              TAB 1: EMPLOYEE DASHBOARD
             ================================================================ */}
          {tab === 'DASHBOARD' && dash && (
            <div className="space-y-4">
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
                <StatCard label="Total Employees" value={dash.totalEmployees} sub={`${dash.activeEmployees} active`} icon={Users} />
                <StatCard label="Present Today" value={dash.presentToday} sub={`${dash.notMarkedToday} unmarked`} icon={UserCheck} tone="emerald" />
                <StatCard label="Absent Today" value={dash.absentToday} icon={XCircle} tone={dash.absentToday > 0 ? 'rose' : 'slate'} />
                <StatCard label="On Leave Today" value={dash.onLeaveToday} sub={`${dash.pendingLeaveRequests} pending req`} icon={CalendarDays} tone="indigo" />
                <StatCard label="Late Arrivals" value={dash.lateArrivalsToday} sub="today" icon={Clock} tone={dash.lateArrivalsToday > 0 ? 'amber' : 'emerald'} />
                <StatCard label="Attendance %" value={`${dash.overallAttendancePct}%`} sub={`Month ${dash.currentMonth}`} icon={CalendarCheck} tone="emerald" />
                <StatCard
                  label="Overtime (Month)"
                  value={`${num(dash.totalOvertimeHoursMonth, 1)} hrs`}
                  sub={dash.salaryConfidential ? 'Confidential pay' : `Pay: ${money(dash.totalOvertimePayMonth)}`}
                  icon={TrendingUp}
                  tone="indigo"
                />
                <StatCard
                  label="Monthly Payroll"
                  value={dash.salaryConfidential ? 'Confidential' : money(dash.totalMonthlySalary)}
                  sub={dash.salaryConfidential ? 'Owner only' : `Pending: ${money(dash.pendingSalaries)}`}
                  icon={IndianRupee}
                  tone="amber"
                />
              </div>

              {/* Employee-wise Sales & Attendance Performance */}
              <Card className="p-0">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
                  <div>
                    <h3 className="text-sm font-semibold text-slate-800">Employee-Wise Attendance & Sales Performance ({dash.currentMonth})</h3>
                    <p className="text-xs text-slate-500">Combined view of monthly POS billing performance, attendance percentage and overtime</p>
                  </div>
                </div>
                <TableWrap>
                  <thead>
                    <tr>
                      <th className="th">Employee</th>
                      <th className="th">Role / Designation</th>
                      <th className="th">Shift</th>
                      <th className="th text-right">Present Days</th>
                      <th className="th text-right">Absent</th>
                      <th className="th text-right">Late Days</th>
                      <th className="th text-right">Overtime</th>
                      <th className="th text-right">Attendance %</th>
                      <th className="th text-right">POS Bills</th>
                      <th className="th text-right">Sales Revenue</th>
                      <th className="th text-right">Profit Generated</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {dash.employeePerformance.map((ep) => (
                      <tr key={ep.userId} className="hover:bg-slate-50/70">
                        <td className="td">
                          <div className="font-medium text-slate-900">{ep.fullName}</div>
                          <div className="font-mono text-xs text-slate-400">{ep.employeeCode}</div>
                        </td>
                        <td className="td">
                          <Badge tone="indigo">{ep.role}</Badge>
                          <div className="mt-0.5 text-xs text-slate-500">{ep.designation}</div>
                        </td>
                        <td className="td text-xs text-slate-600">{ep.assignedShift}</td>
                        <td className="td text-right font-medium text-emerald-700">{num(ep.presentDays, 1)}</td>
                        <td className="td text-right text-rose-600">{num(ep.absentDays, 1)}</td>
                        <td className="td text-right text-amber-700">{ep.lateDays}</td>
                        <td className="td text-right">{num(ep.overtimeHours, 2)} hrs</td>
                        <td className="td text-right">
                          <Badge tone={Number(ep.attendancePercentage) >= 85 ? 'emerald' : Number(ep.attendancePercentage) >= 60 ? 'amber' : 'rose'}>
                            {ep.attendancePercentage}%
                          </Badge>
                        </td>
                        <td className="td text-right font-medium">{ep.billCount}</td>
                        <td className="td text-right font-semibold text-slate-900">{money(ep.revenue)}</td>
                        <td className="td text-right font-medium text-emerald-700">{money(ep.profit)}</td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              </Card>
            </div>
          )}

          {/* ================================================================
              TAB 2: EMPLOYEE DIRECTORY & EMPLOYEE PROFILE
             ================================================================ */}
          {tab === 'DIRECTORY' && (
            <div className="space-y-4">
              {isMgmt && (
                <Card className="p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="relative">
                        <Search className="pointer-events-none absolute left-2.5 top-2 h-3.5 w-3.5 text-slate-400" />
                        <input
                          type="text"
                          placeholder="Search name, EMP ID, phone..."
                          className="input h-8 w-56 pl-8 text-xs"
                          value={empSearch}
                          onChange={(e) => setEmpSearch(e.target.value)}
                        />
                      </div>
                      <select
                        className="input h-8 w-auto py-1 text-xs"
                        value={empStatusFilter}
                        onChange={(e) => setEmpStatusFilter(e.target.value)}
                      >
                        <option value="ALL">All Statuses</option>
                        {EMP_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                      </select>
                      <select
                        className="input h-8 w-auto py-1 text-xs"
                        value={empShiftFilter}
                        onChange={(e) => setEmpShiftFilter(e.target.value)}
                      >
                        <option value="ALL">All Shifts</option>
                        {SHIFTS.map((s) => <option key={s} value={s}>{s}</option>)}
                      </select>
                    </div>
                    <span className="text-xs text-slate-500">{employees.length} employee(s)</span>
                  </div>
                </Card>
              )}

              <Card className="p-0">
                <TableWrap>
                  <thead>
                    <tr>
                      <th className="th">Emp ID</th>
                      <th className="th">Full Name & Login</th>
                      <th className="th">Designation & Role</th>
                      <th className="th">Contact</th>
                      <th className="th">Assigned Shift</th>
                      <th className="th">Joining Date</th>
                      <th className="th">Salary Structure</th>
                      <th className="th">Status</th>
                      <th className="th text-right">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {employees.map((e) => (
                      <tr key={e.userId} className="hover:bg-slate-50/70">
                        <td className="td font-mono text-xs font-semibold text-emerald-700">{e.employeeCode}</td>
                        <td className="td">
                          <div className="font-medium text-slate-900">{e.fullName}</div>
                          <div className="text-xs text-slate-400">@{e.username}</div>
                        </td>
                        <td className="td">
                          <div className="font-medium text-slate-800">{e.designation}</div>
                          <Badge tone="indigo">{e.role}</Badge>
                        </td>
                        <td className="td text-xs text-slate-600">
                          <div>{e.phone || '—'}</div>
                          <div className="text-slate-400">{e.email || ''}</div>
                        </td>
                        <td className="td text-xs">
                          <div className="font-medium text-slate-800">{e.assignedShift}</div>
                          <div className="text-slate-400">{e.shiftStartTime} – {e.shiftEndTime}</div>
                        </td>
                        <td className="td text-xs text-slate-600">{fmtDate(e.joiningDate)}</td>
                        <td className="td text-xs">
                          {e.salaryConfidential ? (
                            <Badge tone="slate"><ShieldAlert className="mr-1 h-3 w-3" /> Confidential</Badge>
                          ) : (
                            <div>
                              <div className="font-semibold text-slate-900">{money(e.basicSalary)} <span className="font-normal text-slate-400">({e.salaryType})</span></div>
                              <div className="text-slate-400">Daily: {money(e.dailyWage)} · OT: {money(e.overtimeHourlyRate)}/hr</div>
                            </div>
                          )}
                        </td>
                        <td className="td">
                          <Badge tone={statusBadgeTone(e.employmentStatus)}>{e.employmentStatus}</Badge>
                        </td>
                        <td className="td text-right">
                          <div className="flex justify-end gap-1">
                            <Button variant="ghost" size="sm" onClick={() => setViewProfile(e)} title="View Profile">
                              <Eye className="h-4 w-4" />
                            </Button>
                            {isMgmt && (isOwner || e.role !== 'OWNER') && (
                              <>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  title="Edit Employee"
                                  onClick={() =>
                                    setEmpForm({
                                      userId: e.userId,
                                      fullName: e.fullName,
                                      role: e.role,
                                      phone: e.phone || '',
                                      email: e.email || '',
                                      designation: e.designation || '',
                                      joiningDate: e.joiningDate || isoToday(),
                                      employmentStatus: e.employmentStatus || 'ACTIVE',
                                      assignedShift: e.assignedShift || 'GENERAL',
                                      shiftStartTime: e.shiftStartTime || '09:00',
                                      shiftEndTime: e.shiftEndTime || '18:00',
                                      address: e.address || '',
                                      salaryType: e.salaryType || 'MONTHLY',
                                      basicSalary: e.basicSalary ?? '15000.00',
                                      dailyWage: e.dailyWage ?? '576.92',
                                      overtimeHourlyRate: e.overtimeHourlyRate ?? '100.00',
                                      paidLeaveQuota: e.paidLeaveQuota ?? 12,
                                      bankAccountNo: e.bankAccountNo || '',
                                      ifscCode: e.ifscCode || '',
                                      upiId: e.upiId || '',
                                    })
                                  }
                                >
                                  <Pencil className="h-4 w-4" />
                                </Button>
                                {e.userId !== user.id && (
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    title={e.active ? 'Deactivate' : 'Activate'}
                                    onClick={() => toggleEmployeeActive(e)}
                                  >
                                    <Power className={cn('h-4 w-4', e.active ? 'text-rose-600' : 'text-emerald-600')} />
                                  </Button>
                                )}
                              </>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              </Card>
            </div>
          )}

          {/* ================================================================
              TAB 3: ATTENDANCE DASHBOARD (DAILY SHEET & CHECK-IN LOGS)
             ================================================================ */}
          {tab === 'ATTENDANCE' && (
            <div className="space-y-4">
              <Card className="p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-medium text-slate-600">Attendance Date:</span>
                    <input
                      type="date"
                      className="input h-8 w-auto text-xs"
                      value={selectedDate}
                      onChange={(e) => setSelectedDate(e.target.value)}
                    />
                    <Button variant="ghost" size="sm" onClick={() => setSelectedDate(isoToday())}>Today</Button>
                  </div>
                  <div className="flex flex-wrap gap-2 text-xs">
                    <Badge tone="emerald">Present: {dailyAtt.filter((a) => a.status === 'PRESENT').length}</Badge>
                    <Badge tone="amber">Half-Day: {dailyAtt.filter((a) => a.status === 'HALF_DAY').length}</Badge>
                    <Badge tone="indigo">On Leave: {dailyAtt.filter((a) => a.status === 'LEAVE').length}</Badge>
                    <Badge tone="rose">Absent: {dailyAtt.filter((a) => a.status === 'ABSENT').length}</Badge>
                  </div>
                </div>
              </Card>

              <Card className="p-0">
                <TableWrap>
                  <thead>
                    <tr>
                      <th className="th">Employee</th>
                      <th className="th">Shift Timing</th>
                      <th className="th">Status</th>
                      <th className="th">Check-In</th>
                      <th className="th">Check-Out</th>
                      <th className="th">Late Arrival</th>
                      <th className="th text-right">Worked Hrs</th>
                      <th className="th text-right">Overtime</th>
                      <th className="th">Notes</th>
                      {isMgmt && <th className="th text-right">Action</th>}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {dailyAtt.map((a) => (
                      <tr key={a.userId} className="hover:bg-slate-50/70">
                        <td className="td">
                          <div className="font-medium text-slate-900">{a.fullName}</div>
                          <div className="text-xs text-slate-400">{a.employeeCode} · {a.designation}</div>
                        </td>
                        <td className="td text-xs text-slate-600">
                          <div>{a.assignedShift}</div>
                          <div className="text-slate-400">{a.shiftStartTime} – {a.shiftEndTime}</div>
                        </td>
                        <td className="td">
                          <Badge tone={statusBadgeTone(a.status)}>
                            {a.status}{a.leaveType ? ` (${a.leaveType})` : ''}
                          </Badge>
                        </td>
                        <td className="td text-xs">{a.checkIn ? fmtDateTime(a.checkIn) : '—'}</td>
                        <td className="td text-xs">{a.checkOut ? fmtDateTime(a.checkOut) : '—'}</td>
                        <td className="td text-xs">
                          {a.late ? <Badge tone="rose">Late ({a.lateMinutes} min)</Badge> : <span className="text-slate-400">On time</span>}
                        </td>
                        <td className="td text-right font-medium">{num(a.workedHours, 2)} hrs</td>
                        <td className="td text-right font-medium text-indigo-700">{num(a.overtimeHours, 2)} hrs</td>
                        <td className="td text-xs text-slate-500">{a.notes || '—'}</td>
                        {isMgmt && (
                          <td className="td text-right">
                            <Button
                              variant="secondary"
                              size="sm"
                              onClick={() =>
                                setMarkModal({
                                  userId: a.userId,
                                  fullName: a.fullName,
                                  attendanceDate: selectedDate,
                                  status: a.status === 'NOT_MARKED' ? 'PRESENT' : a.status,
                                  leaveType: a.leaveType || 'PAID',
                                  checkInTime: a.shiftStartTime || '09:00',
                                  checkOutTime: a.shiftEndTime || '18:00',
                                  workedHours: Number(a.workedHours) > 0 ? a.workedHours : 8,
                                  overtimeHours: a.overtimeHours || 0,
                                  notes: a.notes || '',
                                })
                              }
                            >
                              Mark / Edit
                            </Button>
                          </td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              </Card>
            </div>
          )}

          {/* ================================================================
              TAB 4: MONTHLY ATTENDANCE CALENDAR & REPORT
             ================================================================ */}
          {tab === 'CALENDAR' && calendarData && (
            <div className="space-y-4">
              <Card className="p-0">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
                  <div>
                    <h3 className="text-sm font-semibold text-slate-800">Monthly Attendance Calendar ({calendarData.month})</h3>
                    <p className="text-xs text-slate-500">P = Present · H = Half-Day · L = Leave · A = Absent · W = Week-Off</p>
                  </div>
                </div>
                <TableWrap>
                  <thead>
                    <tr>
                      <th className="th sticky left-0 bg-white">Employee</th>
                      <th className="th text-right">Present</th>
                      <th className="th text-right">Absent</th>
                      <th className="th text-right">Leaves</th>
                      <th className="th text-right">Late</th>
                      <th className="th text-right">Overtime</th>
                      <th className="th text-right">Att %</th>
                      {calendarData.dates.map((d) => (
                        <th key={d} className="th px-1.5 text-center text-[10px]">{d.slice(8)}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {calendarData.employees.map((emp) => (
                      <tr key={emp.userId} className="hover:bg-slate-50/70">
                        <td className="td sticky left-0 bg-white font-medium text-slate-900">
                          <div>{emp.fullName}</div>
                          <div className="text-[11px] text-slate-400">{emp.employeeCode}</div>
                        </td>
                        <td className="td text-right font-medium text-emerald-700">{num(emp.presentDays, 1)}</td>
                        <td className="td text-right text-rose-600">{num(emp.absentDays, 1)}</td>
                        <td className="td text-right text-indigo-700">{num(Number(emp.paidLeaveDays) + Number(emp.unpaidLeaveDays), 1)}</td>
                        <td className="td text-right text-amber-700">{emp.lateDays}</td>
                        <td className="td text-right">{num(emp.totalOvertimeHours, 1)}h</td>
                        <td className="td text-right font-semibold">{emp.attendancePercentage}%</td>
                        {calendarData.dates.map((d) => {
                          const st = emp.dailyStatusMap?.[d]
                          const short = st === 'PRESENT' ? 'P' : st === 'ABSENT' ? 'A' : st === 'HALF_DAY' ? 'H' : st === 'LEAVE' ? 'L' : st === 'WEEK_OFF' ? 'W' : '·'
                          const cls =
                            st === 'PRESENT' ? 'bg-emerald-100 text-emerald-800' :
                            st === 'ABSENT' ? 'bg-rose-100 text-rose-800' :
                            st === 'HALF_DAY' ? 'bg-amber-100 text-amber-800' :
                            st === 'LEAVE' ? 'bg-indigo-100 text-indigo-800' :
                            st === 'WEEK_OFF' ? 'bg-slate-200 text-slate-700' : 'text-slate-300'
                          return (
                            <td key={d} className="px-1 py-2 text-center text-xs">
                              <span className={cn('inline-flex h-5 w-5 items-center justify-center rounded font-bold text-[10px]', cls)} title={`${d}: ${st || 'Not marked'}`}>
                                {short}
                              </span>
                            </td>
                          )
                        })}
                      </tr>
                    ))}
                  </tbody>
                </TableWrap>
              </Card>
            </div>
          )}

          {/* ================================================================
              TAB 5: LEAVE MANAGEMENT & LEAVE BALANCES
             ================================================================ */}
          {tab === 'LEAVES' && (
            <div className="space-y-4">
              {/* Leave Balances Strip */}
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                {leaveBalances.map((lb) => (
                  <Card key={lb.userId} className="p-3.5">
                    <div className="flex items-center justify-between">
                      <span className="font-semibold text-slate-900">{lb.fullName}</span>
                      <span className="font-mono text-xs text-slate-400">{lb.employeeCode}</span>
                    </div>
                    <div className="mt-2 grid grid-cols-3 gap-2 text-center text-xs">
                      <div className="rounded-lg bg-slate-50 p-1.5">
                        <div className="text-slate-400">Quota</div>
                        <div className="font-bold text-slate-800">{lb.annualPaidQuota}d</div>
                      </div>
                      <div className="rounded-lg bg-emerald-50 p-1.5">
                        <div className="text-emerald-600">Remaining</div>
                        <div className="font-bold text-emerald-800">{num(lb.paidLeavesRemaining, 1)}d</div>
                      </div>
                      <div className="rounded-lg bg-rose-50 p-1.5">
                        <div className="text-rose-600">Unpaid Used</div>
                        <div className="font-bold text-rose-800">{num(lb.unpaidLeavesUsed, 1)}d</div>
                      </div>
                    </div>
                  </Card>
                ))}
              </div>

              <Card className="p-0">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 p-4">
                  <div>
                    <h3 className="text-sm font-semibold text-slate-800">Leave Applications & Approval History</h3>
                    <p className="text-xs text-slate-500">Approved leaves automatically update the attendance calendar</p>
                  </div>
                  <div className="flex items-center gap-2">
                    <select
                      className="input h-8 w-auto py-1 text-xs"
                      value={leaveStatusFilter}
                      onChange={(e) => setLeaveStatusFilter(e.target.value)}
                    >
                      <option value="ALL">All Statuses</option>
                      <option value="PENDING">Pending Approval</option>
                      <option value="APPROVED">Approved</option>
                      <option value="REJECTED">Rejected</option>
                    </select>
                  </div>
                </div>
                {leaves.length === 0 ? (
                  <Empty text="No leave requests found" />
                ) : (
                  <TableWrap>
                    <thead>
                      <tr>
                        <th className="th">Employee</th>
                        <th className="th">Leave Type</th>
                        <th className="th">Dates</th>
                        <th className="th text-right">Days</th>
                        <th className="th">Reason</th>
                        <th className="th">Status</th>
                        <th className="th">Reviewed By</th>
                        {isMgmt && <th className="th text-right">Action</th>}
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {leaves.map((l) => (
                        <tr key={l.id} className="hover:bg-slate-50/70">
                          <td className="td">
                            <div className="font-medium text-slate-900">{l.fullName}</div>
                            <div className="text-xs text-slate-400">{l.employeeCode} · {l.designation}</div>
                          </td>
                          <td className="td">
                            <Badge tone={l.paid ? 'indigo' : 'amber'}>
                              {l.leaveType} ({l.paid ? 'Paid' : 'Unpaid'})
                            </Badge>
                          </td>
                          <td className="td text-xs">
                            {fmtDate(l.startDate)} – {fmtDate(l.endDate)}
                          </td>
                          <td className="td text-right font-semibold">{num(l.totalDays, 1)}</td>
                          <td className="td text-xs text-slate-600">{l.reason}</td>
                          <td className="td"><Badge tone={statusBadgeTone(l.status)}>{l.status}</Badge></td>
                          <td className="td text-xs text-slate-500">
                            {l.reviewedByName ? `${l.reviewedByName}${l.reviewNote ? ` (${l.reviewNote})` : ''}` : '—'}
                          </td>
                          {isMgmt && (
                            <td className="td text-right">
                              {l.status === 'PENDING' && (isOwner || l.userId !== user.id) && (
                                <div className="flex justify-end gap-1">
                                  <Button
                                    size="sm"
                                    onClick={() => setReviewModal({ id: l.id, fullName: l.fullName, decision: 'APPROVED', reviewNote: '' })}
                                  >
                                    <Check className="h-3.5 w-3.5" /> Approve
                                  </Button>
                                  <Button
                                    variant="danger"
                                    size="sm"
                                    onClick={() => setReviewModal({ id: l.id, fullName: l.fullName, decision: 'REJECTED', reviewNote: '' })}
                                  >
                                    <X className="h-3.5 w-3.5" /> Reject
                                  </Button>
                                </div>
                              )}
                            </td>
                          )}
                        </tr>
                      ))}
                    </tbody>
                  </TableWrap>
                )}
              </Card>
            </div>
          )}

          {/* ================================================================
              TAB 6: PAYROLL DASHBOARD & SALARY ADVANCES
             ================================================================ */}
          {tab === 'PAYROLL' && (
            <div className="space-y-4">
              {isOwner && (
                <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
                  <div>
                    <h3 className="text-sm font-semibold text-slate-900">Payroll Control Center ({month})</h3>
                    <p className="text-xs text-slate-500">
                      Std Working Days: {settings?.standardWorkingDays || 26} · Auto-Deduct Absences: {settings?.deductAbsentAutomatically ? 'Yes' : 'No (Policy Protected)'} · Auto-Cover Absences with Paid Leave Quota: {settings?.autoConvertAbsentToPaidLeave ? 'Yes' : 'No'}
                    </p>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button variant="secondary" size="sm" onClick={() => setRulesModal({ ...settings })}>
                      <Settings className="h-3.5 w-3.5" /> Configure Payroll Rules
                    </Button>
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() =>
                        setAdvanceModal({
                          userId: employees[0]?.userId || user.id,
                          amount: '',
                          advanceDate: isoToday(),
                          method: 'CASH',
                          reason: '',
                        })
                      }
                    >
                      <Wallet className="h-3.5 w-3.5 text-amber-600" /> Issue Salary Advance
                    </Button>
                    <Button size="sm" onClick={handleGeneratePayroll} loading={busy}>
                      <Coins className="h-3.5 w-3.5" /> Generate / Recalculate {month} Payroll
                    </Button>
                  </div>
                </Card>
              )}

              {/* Monthly Payroll Table */}
              <Card className="p-0">
                <div className="border-b border-slate-100 p-4">
                  <h3 className="text-sm font-semibold text-slate-800">
                    {isOwner ? `Monthly Payroll Sheet (${month})` : `My Personal Payslips (${month})`}
                  </h3>
                </div>
                {payrollList.length === 0 ? (
                  <Empty text={isOwner ? `No payroll generated for ${month} yet. Click "Generate / Recalculate ${month} Payroll" above.` : `No payslip generated for ${month} yet.`} />
                ) : (
                  <TableWrap>
                    <thead>
                      <tr>
                        <th className="th">Payslip No</th>
                        <th className="th">Employee</th>
                        <th className="th text-right">Payable Days</th>
                        <th className="th text-right">Earned Basic</th>
                        <th className="th text-right">Overtime Pay</th>
                        <th className="th text-right">Bonus/Inc</th>
                        <th className="th text-right">Deductions</th>
                        <th className="th text-right">Net Salary</th>
                        <th className="th text-right">Paid</th>
                        <th className="th text-right">Pending</th>
                        <th className="th">Status</th>
                        <th className="th text-right">Actions</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {payrollList.map((pr) => (
                        <tr key={pr.id} className="hover:bg-slate-50/70">
                          <td className="td font-mono text-xs font-semibold text-slate-700">{pr.payslipNo}</td>
                          <td className="td">
                            <div className="font-medium text-slate-900">{pr.fullName}</div>
                            <div className="text-xs text-slate-400">{pr.employeeCode} · {pr.designation}</div>
                          </td>
                          <td className="td text-right">{num(pr.payableDays, 1)} / {pr.workingDays}</td>
                          <td className="td text-right">{money(pr.earnedBasic)}</td>
                          <td className="td text-right text-indigo-700">{money(pr.overtimePay)}</td>
                          <td className="td text-right text-emerald-700">{money(Number(pr.bonus) + Number(pr.incentives))}</td>
                          <td className="td text-right text-rose-600">
                            {money(Number(pr.leaveDeduction) + Number(pr.advanceDeduction) + Number(pr.otherDeductions))}
                          </td>
                          <td className="td text-right font-bold text-slate-900">{money(pr.netSalary)}</td>
                          <td className="td text-right font-medium text-emerald-700">{money(pr.paidAmount)}</td>
                          <td className="td text-right font-medium text-rose-600">{money(pr.pendingAmount)}</td>
                          <td className="td"><Badge tone={statusBadgeTone(pr.paymentStatus)}>{pr.paymentStatus.replace('_', ' ')}</Badge></td>
                          <td className="td text-right">
                            <div className="flex justify-end gap-1">
                              <Button variant="secondary" size="sm" onClick={() => setActivePayslip(pr)}>
                                <FileText className="h-3.5 w-3.5" /> Payslip
                              </Button>
                              {isOwner && pr.paymentStatus !== 'PAID' && (
                                <>
                                  <Button
                                    variant="ghost"
                                    size="sm"
                                    onClick={() =>
                                      setAdjustModal({
                                        id: pr.id,
                                        fullName: pr.fullName,
                                        bonus: pr.bonus,
                                        incentives: pr.incentives,
                                        leaveDeduction: pr.leaveDeduction,
                                        advanceDeduction: pr.advanceDeduction,
                                        otherDeductions: pr.otherDeductions,
                                        notes: pr.notes || '',
                                      })
                                    }
                                  >
                                    Adjust
                                  </Button>
                                  <Button
                                    size="sm"
                                    onClick={() =>
                                      setPayModal({
                                        id: pr.id,
                                        fullName: pr.fullName,
                                        payslipNo: pr.payslipNo,
                                        pendingAmount: pr.pendingAmount,
                                        amount: pr.pendingAmount,
                                        method: 'CASH',
                                        note: '',
                                      })
                                    }
                                  >
                                    Pay
                                  </Button>
                                </>
                              )}
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </TableWrap>
                )}
              </Card>

              {/* Salary Advances Table (Owner Only) */}
              {isOwner && (
                <Card className="p-0">
                  <div className="border-b border-slate-100 p-4">
                    <h3 className="text-sm font-semibold text-slate-800">Salary Advances Ledger</h3>
                    <p className="text-xs text-slate-500">Open advances are automatically recovered during monthly payroll generation</p>
                  </div>
                  {advances.length === 0 ? (
                    <Empty text="No salary advances recorded" />
                  ) : (
                    <TableWrap>
                      <thead>
                        <tr>
                          <th className="th">Employee</th>
                          <th className="th">Date</th>
                          <th className="th">Method</th>
                          <th className="th text-right">Advance Amount</th>
                          <th className="th text-right">Recovered</th>
                          <th className="th text-right">Remaining</th>
                          <th className="th">Reason</th>
                          <th className="th">Status</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {advances.map((adv) => (
                          <tr key={adv.id}>
                            <td className="td font-medium">{adv.fullName} <span className="text-xs text-slate-400">({adv.employeeCode})</span></td>
                            <td className="td text-xs">{fmtDate(adv.advanceDate)}</td>
                            <td className="td"><Badge tone="indigo">{adv.method}</Badge></td>
                            <td className="td text-right font-semibold">{money(adv.amount)}</td>
                            <td className="td text-right text-emerald-700">{money(adv.deductedAmount)}</td>
                            <td className="td text-right font-medium text-rose-600">{money(adv.remainingAmount)}</td>
                            <td className="td text-xs text-slate-500">{adv.reason || '—'}</td>
                            <td className="td"><Badge tone={adv.status === 'DEDUCTED' ? 'emerald' : 'amber'}>{adv.status}</Badge></td>
                          </tr>
                        ))}
                      </tbody>
                    </TableWrap>
                  )}
                </Card>
              )}
            </div>
          )}

          {/* ================================================================
              TAB 7: SALARY HISTORY & PAYSLIP ARCHIVE
             ================================================================ */}
          {tab === 'HISTORY' && (
            <div className="space-y-4">
              {isOwner && (
                <Card className="p-3">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-medium text-slate-600">Filter by Employee:</span>
                    <select
                      className="input h-8 w-auto py-1 text-xs"
                      value={historyUserFilter}
                      onChange={(e) => setHistoryUserFilter(e.target.value)}
                    >
                      <option value="">All Employees</option>
                      {employees.map((e) => (
                        <option key={e.userId} value={e.userId}>{e.fullName} ({e.employeeCode})</option>
                      ))}
                    </select>
                  </div>
                </Card>
              )}

              <Card className="p-0">
                <div className="border-b border-slate-100 p-4">
                  <h3 className="text-sm font-semibold text-slate-800">Historical Payroll & Payslip Archive</h3>
                </div>
                {salaryHistory.length === 0 ? (
                  <Empty text="No historical salary records found" />
                ) : (
                  <TableWrap>
                    <thead>
                      <tr>
                        <th className="th">Month</th>
                        <th className="th">Payslip No</th>
                        <th className="th">Employee</th>
                        <th className="th text-right">Gross Salary</th>
                        <th className="th text-right">Net Salary</th>
                        <th className="th text-right">Paid Amount</th>
                        <th className="th text-right">Pending</th>
                        <th className="th">Status</th>
                        <th className="th text-right">View Payslip</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {salaryHistory.map((pr) => (
                        <tr key={pr.id} className="hover:bg-slate-50/70">
                          <td className="td font-semibold text-slate-900">{pr.payrollMonth}</td>
                          <td className="td font-mono text-xs">{pr.payslipNo}</td>
                          <td className="td">{pr.fullName} <span className="text-xs text-slate-400">({pr.employeeCode})</span></td>
                          <td className="td text-right">{money(pr.grossSalary)}</td>
                          <td className="td text-right font-bold text-slate-900">{money(pr.netSalary)}</td>
                          <td className="td text-right text-emerald-700">{money(pr.paidAmount)}</td>
                          <td className="td text-right text-rose-600">{money(pr.pendingAmount)}</td>
                          <td className="td"><Badge tone={statusBadgeTone(pr.paymentStatus)}>{pr.paymentStatus.replace('_', ' ')}</Badge></td>
                          <td className="td text-right">
                            <Button variant="secondary" size="sm" onClick={() => setActivePayslip(pr)}>
                              <FileText className="h-3.5 w-3.5" /> View / PDF
                            </Button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </TableWrap>
                )}
              </Card>
            </div>
          )}
        </>
      )}

      {/* =====================================================================
          MODAL 1: CREATE / EDIT EMPLOYEE
         ===================================================================== */}
      <Modal
        open={!!empForm}
        onClose={() => setEmpForm(null)}
        title={empForm?.userId ? 'Edit Employee Profile' : 'Add New Employee'}
        wide
        footer={
          <>
            <Button variant="secondary" onClick={() => setEmpForm(null)}>Cancel</Button>
            <Button onClick={saveEmployee} loading={busy}>Save Employee</Button>
          </>
        }
      >
        {empForm && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Full Name *">
              <input className="input" value={empForm.fullName} onChange={(e) => setEmpForm({ ...empForm, fullName: e.target.value })} />
            </Field>
            {!empForm.userId && (
              <>
                <Field label="Login Username *" hint="Letters, digits, . _ -">
                  <input className="input" value={empForm.username} onChange={(e) => setEmpForm({ ...empForm, username: e.target.value })} />
                </Field>
                <Field label="Initial Password *" hint="At least 8 characters">
                  <input type="password" className="input" value={empForm.password} onChange={(e) => setEmpForm({ ...empForm, password: e.target.value })} />
                </Field>
                <Field label="Employee ID" hint="Leave blank to auto-generate (EMP-000X)">
                  <input className="input" placeholder="Auto-generated" value={empForm.employeeCode} onChange={(e) => setEmpForm({ ...empForm, employeeCode: e.target.value })} />
                </Field>
              </>
            )}
            <Field label="System Role">
              <select
                className="input"
                value={empForm.role}
                disabled={!isOwner}
                onChange={(e) => setEmpForm({ ...empForm, role: e.target.value })}
              >
                <option value="STAFF">STAFF</option>
                {isOwner && <option value="MANAGER">MANAGER</option>}
                {isOwner && <option value="OWNER">OWNER</option>}
              </select>
            </Field>
            <Field label="Job Designation">
              <input className="input" value={empForm.designation} onChange={(e) => setEmpForm({ ...empForm, designation: e.target.value })} />
            </Field>
            <Field label="Phone Number">
              <input className="input" value={empForm.phone} onChange={(e) => setEmpForm({ ...empForm, phone: e.target.value })} />
            </Field>
            <Field label="Email Address">
              <input type="email" className="input" value={empForm.email} onChange={(e) => setEmpForm({ ...empForm, email: e.target.value })} />
            </Field>
            <Field label="Joining Date">
              <input type="date" className="input" value={empForm.joiningDate} onChange={(e) => setEmpForm({ ...empForm, joiningDate: e.target.value })} />
            </Field>
            <Field label="Employment Status">
              <select className="input" value={empForm.employmentStatus} onChange={(e) => setEmpForm({ ...empForm, employmentStatus: e.target.value })}>
                {EMP_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </Field>
            <Field label="Assigned Shift">
              <select className="input" value={empForm.assignedShift} onChange={(e) => setEmpForm({ ...empForm, assignedShift: e.target.value })}>
                {SHIFTS.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="Shift Start">
                <input type="time" className="input" value={empForm.shiftStartTime} onChange={(e) => setEmpForm({ ...empForm, shiftStartTime: e.target.value })} />
              </Field>
              <Field label="Shift End">
                <input type="time" className="input" value={empForm.shiftEndTime} onChange={(e) => setEmpForm({ ...empForm, shiftEndTime: e.target.value })} />
              </Field>
            </div>
            <Field label="Annual Paid Leave Quota (Days)">
              <input type="number" className="input" value={empForm.paidLeaveQuota} onChange={(e) => setEmpForm({ ...empForm, paidLeaveQuota: Number(e.target.value) })} />
            </Field>
            <Field label="Residential Address" className="sm:col-span-2">
              <input className="input" value={empForm.address} onChange={(e) => setEmpForm({ ...empForm, address: e.target.value })} />
            </Field>

            {isOwner && (
              <>
                <div className="sm:col-span-2 mt-2 border-t border-slate-200 pt-2">
                  <p className="text-xs font-bold uppercase tracking-wider text-emerald-700">Confidential Compensation & Bank Details (Owner Only)</p>
                </div>
                <Field label="Salary Type">
                  <select className="input" value={empForm.salaryType} onChange={(e) => setEmpForm({ ...empForm, salaryType: e.target.value })}>
                    <option value="MONTHLY">MONTHLY</option>
                    <option value="DAILY">DAILY WAGE</option>
                  </select>
                </Field>
                <Field label="Monthly Basic Salary (₹)">
                  <input type="number" step="0.01" className="input" value={empForm.basicSalary} onChange={(e) => setEmpForm({ ...empForm, basicSalary: e.target.value })} />
                </Field>
                <Field label="Daily Wage Rate (₹)">
                  <input type="number" step="0.01" className="input" value={empForm.dailyWage} onChange={(e) => setEmpForm({ ...empForm, dailyWage: e.target.value })} />
                </Field>
                <Field label="Overtime Rate / Hour (₹)">
                  <input type="number" step="0.01" className="input" value={empForm.overtimeHourlyRate} onChange={(e) => setEmpForm({ ...empForm, overtimeHourlyRate: e.target.value })} />
                </Field>
                <Field label="Bank Account Number">
                  <input className="input" value={empForm.bankAccountNo} onChange={(e) => setEmpForm({ ...empForm, bankAccountNo: e.target.value })} />
                </Field>
                <Field label="IFSC Code / UPI ID">
                  <div className="grid grid-cols-2 gap-2">
                    <input className="input" placeholder="IFSC" value={empForm.ifscCode} onChange={(e) => setEmpForm({ ...empForm, ifscCode: e.target.value })} />
                    <input className="input" placeholder="UPI ID" value={empForm.upiId} onChange={(e) => setEmpForm({ ...empForm, upiId: e.target.value })} />
                  </div>
                </Field>
              </>
            )}
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 2: DEDICATED EMPLOYEE PROFILE VIEW
         ===================================================================== */}
      <Modal
        open={!!viewProfile}
        onClose={() => setViewProfile(null)}
        title={`Employee Profile · ${viewProfile?.fullName}`}
        wide
        footer={<Button variant="secondary" onClick={() => setViewProfile(null)}>Close</Button>}
      >
        {viewProfile && (
          <div className="space-y-4 text-sm">
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-slate-50 p-4">
              <div>
                <div className="flex items-center gap-2">
                  <h3 className="text-lg font-bold text-slate-900">{viewProfile.fullName}</h3>
                  <Badge tone="emerald">{viewProfile.employeeCode}</Badge>
                  <Badge tone="indigo">{viewProfile.role}</Badge>
                </div>
                <p className="text-xs text-slate-500">{viewProfile.designation} · Joined {fmtDate(viewProfile.joiningDate)}</p>
              </div>
              <Badge tone={statusBadgeTone(viewProfile.employmentStatus)}>{viewProfile.employmentStatus}</Badge>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <Card className="p-3.5 space-y-1.5">
                <p className="text-xs font-bold uppercase text-slate-400">Contact & Shift Details</p>
                <p><span className="text-slate-500">Username:</span> @{viewProfile.username}</p>
                <p><span className="text-slate-500">Phone:</span> {viewProfile.phone || '—'}</p>
                <p><span className="text-slate-500">Email:</span> {viewProfile.email || '—'}</p>
                <p><span className="text-slate-500">Address:</span> {viewProfile.address || '—'}</p>
                <p><span className="text-slate-500">Shift:</span> {viewProfile.assignedShift} ({viewProfile.shiftStartTime} – {viewProfile.shiftEndTime})</p>
              </Card>

              <Card className="p-3.5 space-y-1.5">
                <p className="text-xs font-bold uppercase text-slate-400">Leave & Compensation Summary</p>
                <p><span className="text-slate-500">Annual Paid Leave Quota:</span> {viewProfile.paidLeaveQuota} days (Remaining: {num(viewProfile.paidLeavesRemaining, 1)} days)</p>
                <p><span className="text-slate-500">Unpaid Leaves Used:</span> {num(viewProfile.unpaidLeavesUsed, 1)} days</p>
                {viewProfile.salaryConfidential ? (
                  <p className="text-xs text-amber-700">Salary details are confidential and visible only to the Store Owner and the employee.</p>
                ) : (
                  <>
                    <p><span className="text-slate-500">Salary Structure:</span> {money(viewProfile.basicSalary)} ({viewProfile.salaryType})</p>
                    <p><span className="text-slate-500">Daily Wage / OT Rate:</span> {money(viewProfile.dailyWage)}/day · {money(viewProfile.overtimeHourlyRate)}/hr</p>
                    <p><span className="text-slate-500">Bank / UPI:</span> {viewProfile.bankAccountNo || '—'} {viewProfile.upiId ? `· UPI: ${viewProfile.upiId}` : ''}</p>
                  </>
                )}
              </Card>
            </div>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 3: MARK / EDIT DAILY ATTENDANCE
         ===================================================================== */}
      <Modal
        open={!!markModal}
        onClose={() => setMarkModal(null)}
        title={`Mark Attendance · ${markModal?.fullName} (${markModal?.attendanceDate})`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setMarkModal(null)}>Cancel</Button>
            <Button onClick={saveAttendanceMark} loading={busy}>Save Attendance</Button>
          </>
        }
      >
        {markModal && (
          <div className="space-y-3">
            <Field label="Attendance Status">
              <select className="input" value={markModal.status} onChange={(e) => setMarkModal({ ...markModal, status: e.target.value })}>
                {ATT_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </Field>
            {markModal.status === 'LEAVE' && (
              <Field label="Leave Type">
                <select className="input" value={markModal.leaveType} onChange={(e) => setMarkModal({ ...markModal, leaveType: e.target.value })}>
                  <option value="PAID">PAID LEAVE</option>
                  <option value="UNPAID">UNPAID LEAVE</option>
                </select>
              </Field>
            )}
            {(markModal.status === 'PRESENT' || markModal.status === 'HALF_DAY') && (
              <>
                <div className="grid grid-cols-2 gap-2">
                  <Field label="Check-In Time">
                    <input type="time" className="input" value={markModal.checkInTime} onChange={(e) => setMarkModal({ ...markModal, checkInTime: e.target.value })} />
                  </Field>
                  <Field label="Check-Out Time">
                    <input type="time" className="input" value={markModal.checkOutTime} onChange={(e) => setMarkModal({ ...markModal, checkOutTime: e.target.value })} />
                  </Field>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <Field label="Worked Hours">
                    <input type="number" step="0.25" className="input" value={markModal.workedHours} onChange={(e) => setMarkModal({ ...markModal, workedHours: e.target.value })} />
                  </Field>
                  <Field label="Overtime Hours">
                    <input type="number" step="0.25" className="input" value={markModal.overtimeHours} onChange={(e) => setMarkModal({ ...markModal, overtimeHours: e.target.value })} />
                  </Field>
                </div>
              </>
            )}
            <Field label="Remarks / Notes">
              <input className="input" value={markModal.notes} onChange={(e) => setMarkModal({ ...markModal, notes: e.target.value })} />
            </Field>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 4: APPLY FOR LEAVE
         ===================================================================== */}
      <Modal
        open={!!leaveModal}
        onClose={() => setLeaveModal(null)}
        title="Apply for Leave"
        footer={
          <>
            <Button variant="secondary" onClick={() => setLeaveModal(null)}>Cancel</Button>
            <Button onClick={submitLeave} loading={busy}>Submit Leave Request</Button>
          </>
        }
      >
        {leaveModal && (
          <div className="space-y-3">
            {isMgmt && (
              <Field label="Employee">
                <select className="input" value={leaveModal.userId} onChange={(e) => setLeaveModal({ ...leaveModal, userId: Number(e.target.value) })}>
                  {employees.map((e) => <option key={e.userId} value={e.userId}>{e.fullName} ({e.employeeCode})</option>)}
                </select>
              </Field>
            )}
            <Field label="Leave Category">
              <select
                className="input"
                value={leaveModal.leaveType}
                onChange={(e) => setLeaveModal({ ...leaveModal, leaveType: e.target.value, paid: e.target.value !== 'UNPAID' })}
              >
                {LEAVE_TYPES.map((t) => <option key={t} value={t}>{t} LEAVE</option>)}
              </select>
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="Start Date">
                <input type="date" className="input" value={leaveModal.startDate} onChange={(e) => setLeaveModal({ ...leaveModal, startDate: e.target.value })} />
              </Field>
              <Field label="End Date">
                <input type="date" className="input" value={leaveModal.endDate} min={leaveModal.startDate} onChange={(e) => setLeaveModal({ ...leaveModal, endDate: e.target.value })} />
              </Field>
            </div>
            {leaveModal.startDate === leaveModal.endDate && (
              <label className="flex items-center gap-2 text-xs text-slate-700">
                <input type="checkbox" checked={leaveModal.halfDay} onChange={(e) => setLeaveModal({ ...leaveModal, halfDay: e.target.checked })} />
                Apply as Half-Day Leave (0.5 day)
              </label>
            )}
            <Field label="Reason for Leave *">
              <textarea rows={3} className="input" value={leaveModal.reason} onChange={(e) => setLeaveModal({ ...leaveModal, reason: e.target.value })} />
            </Field>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 5: APPROVE / REJECT LEAVE
         ===================================================================== */}
      <Modal
        open={!!reviewModal}
        onClose={() => setReviewModal(null)}
        title={`${reviewModal?.decision === 'APPROVED' ? 'Approve' : 'Reject'} Leave · ${reviewModal?.fullName}`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setReviewModal(null)}>Cancel</Button>
            <Button variant={reviewModal?.decision === 'REJECTED' ? 'danger' : 'primary'} onClick={submitLeaveReview} loading={busy}>
              Confirm {reviewModal?.decision}
            </Button>
          </>
        }
      >
        {reviewModal && (
          <Field label="Reviewer Note (Optional)">
            <input className="input" value={reviewModal.reviewNote} onChange={(e) => setReviewModal({ ...reviewModal, reviewNote: e.target.value })} />
          </Field>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 6: CONFIGURE PAYROLL RULES (OWNER ONLY)
         ===================================================================== */}
      <Modal
        open={!!rulesModal}
        onClose={() => setRulesModal(null)}
        title="Configure Store Payroll & Absence Policy"
        wide
        footer={
          <>
            <Button variant="secondary" onClick={() => setRulesModal(null)}>Cancel</Button>
            <Button onClick={savePayrollRules} loading={busy}>Save Policy</Button>
          </>
        }
      >
        {rulesModal && (
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Standard Working Days / Month">
              <input type="number" className="input" value={rulesModal.standardWorkingDays} onChange={(e) => setRulesModal({ ...rulesModal, standardWorkingDays: Number(e.target.value) })} />
            </Field>
            <Field label="Standard Daily Shift Hours">
              <input type="number" step="0.5" className="input" value={rulesModal.standardDailyHours} onChange={(e) => setRulesModal({ ...rulesModal, standardDailyHours: e.target.value })} />
            </Field>
            <Field label="Late Arrival Grace Period (Minutes)">
              <input type="number" className="input" value={rulesModal.graceLateMinutes} onChange={(e) => setRulesModal({ ...rulesModal, graceLateMinutes: Number(e.target.value) })} />
            </Field>
            <Field label="Overtime Pay Multiplier (e.g. 1.0 or 1.5)">
              <input type="number" step="0.1" className="input" value={rulesModal.overtimeMultiplier} onChange={(e) => setRulesModal({ ...rulesModal, overtimeMultiplier: e.target.value })} />
            </Field>
            <Field label="Default Annual Paid Leaves Quota">
              <input type="number" className="input" value={rulesModal.defaultAnnualPaidLeaves} onChange={(e) => setRulesModal({ ...rulesModal, defaultAnnualPaidLeaves: Number(e.target.value) })} />
            </Field>
            <Field label="Free / Grace Absent Days Allowed per Month">
              <input type="number" step="0.5" className="input" value={rulesModal.freeAbsentDaysAllowed} onChange={(e) => setRulesModal({ ...rulesModal, freeAbsentDaysAllowed: e.target.value })} />
            </Field>
            <Field label="Late Days Threshold (Before Penalty)">
              <input type="number" className="input" value={rulesModal.lateDaysThreshold} onChange={(e) => setRulesModal({ ...rulesModal, lateDaysThreshold: Number(e.target.value) })} />
            </Field>
            <Field label="Late Penalty per Extra Late Day (₹)">
              <input type="number" step="10" className="input" value={rulesModal.latePenaltyPerDay} onChange={(e) => setRulesModal({ ...rulesModal, latePenaltyPerDay: e.target.value })} />
            </Field>
            <div className="sm:col-span-2 space-y-2 rounded-xl bg-slate-50 p-3 text-xs">
              <label className="flex items-center gap-2 font-medium text-slate-800">
                <input
                  type="checkbox"
                  checked={rulesModal.autoConvertAbsentToPaidLeave}
                  onChange={(e) => setRulesModal({ ...rulesModal, autoConvertAbsentToPaidLeave: e.target.checked })}
                />
                Automatically cover unmarked absences using the employee's remaining Paid Leave balance before deducting salary
              </label>
              <label className="flex items-center gap-2 font-medium text-slate-800">
                <input
                  type="checkbox"
                  checked={rulesModal.deductAbsentAutomatically}
                  onChange={(e) => setRulesModal({ ...rulesModal, deductAbsentAutomatically: e.target.checked })}
                />
                Automatically deduct pro-rata daily salary for unexcused absences beyond grace & paid leave quota
              </label>
            </div>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 7: ISSUE SALARY ADVANCE
         ===================================================================== */}
      <Modal
        open={!!advanceModal}
        onClose={() => setAdvanceModal(null)}
        title="Issue Salary Advance"
        footer={
          <>
            <Button variant="secondary" onClick={() => setAdvanceModal(null)}>Cancel</Button>
            <Button onClick={saveSalaryAdvance} loading={busy}>Record Advance</Button>
          </>
        }
      >
        {advanceModal && (
          <div className="space-y-3">
            <Field label="Employee">
              <select className="input" value={advanceModal.userId} onChange={(e) => setAdvanceModal({ ...advanceModal, userId: Number(e.target.value) })}>
                {employees.map((e) => <option key={e.userId} value={e.userId}>{e.fullName} ({e.employeeCode})</option>)}
              </select>
            </Field>
            <Field label="Advance Amount (₹) *">
              <input type="number" step="0.01" className="input" value={advanceModal.amount} onChange={(e) => setAdvanceModal({ ...advanceModal, amount: e.target.value })} />
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="Advance Date">
                <input type="date" className="input" value={advanceModal.advanceDate} onChange={(e) => setAdvanceModal({ ...advanceModal, advanceDate: e.target.value })} />
              </Field>
              <Field label="Payment Method">
                <select className="input" value={advanceModal.method} onChange={(e) => setAdvanceModal({ ...advanceModal, method: e.target.value })}>
                  {PAY_METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
                </select>
              </Field>
            </div>
            <Field label="Reason / Notes">
              <input className="input" value={advanceModal.reason} onChange={(e) => setAdvanceModal({ ...advanceModal, reason: e.target.value })} />
            </Field>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 8: ADJUST PAYROLL BONUS / INCENTIVES / DEDUCTIONS
         ===================================================================== */}
      <Modal
        open={!!adjustModal}
        onClose={() => setAdjustModal(null)}
        title={`Adjust Payroll · ${adjustModal?.fullName}`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setAdjustModal(null)}>Cancel</Button>
            <Button onClick={savePayrollAdjustments} loading={busy}>Update Payroll</Button>
          </>
        }
      >
        {adjustModal && (
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-2">
              <Field label="Bonus (₹)">
                <input type="number" step="0.01" className="input" value={adjustModal.bonus} onChange={(e) => setAdjustModal({ ...adjustModal, bonus: e.target.value })} />
              </Field>
              <Field label="Performance Incentives (₹)">
                <input type="number" step="0.01" className="input" value={adjustModal.incentives} onChange={(e) => setAdjustModal({ ...adjustModal, incentives: e.target.value })} />
              </Field>
            </div>
            <div className="grid grid-cols-3 gap-2">
              <Field label="Leave Deduction (₹)">
                <input type="number" step="0.01" className="input" value={adjustModal.leaveDeduction} onChange={(e) => setAdjustModal({ ...adjustModal, leaveDeduction: e.target.value })} />
              </Field>
              <Field label="Advance Deduction (₹)">
                <input type="number" step="0.01" className="input" value={adjustModal.advanceDeduction} onChange={(e) => setAdjustModal({ ...adjustModal, advanceDeduction: e.target.value })} />
              </Field>
              <Field label="Other Deductions (₹)">
                <input type="number" step="0.01" className="input" value={adjustModal.otherDeductions} onChange={(e) => setAdjustModal({ ...adjustModal, otherDeductions: e.target.value })} />
              </Field>
            </div>
            <Field label="Payroll Notes">
              <input className="input" value={adjustModal.notes} onChange={(e) => setAdjustModal({ ...adjustModal, notes: e.target.value })} />
            </Field>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 9: DISBURSE FULL OR PARTIAL SALARY PAYMENT
         ===================================================================== */}
      <Modal
        open={!!payModal}
        onClose={() => setPayModal(null)}
        title={`Pay Salary · ${payModal?.fullName} (${payModal?.payslipNo})`}
        footer={
          <>
            <Button variant="secondary" onClick={() => setPayModal(null)}>Cancel</Button>
            <Button onClick={submitSalaryPayment} loading={busy}>Disburse Payment</Button>
          </>
        }
      >
        {payModal && (
          <div className="space-y-3">
            <div className="rounded-lg bg-emerald-50 p-3 text-xs text-emerald-900">
              Pending Salary Balance: <span className="font-bold">{money(payModal.pendingAmount)}</span> (Supports full or partial payment)
            </div>
            <Field label="Payment Amount (₹) *">
              <input
                type="number"
                step="0.01"
                max={payModal.pendingAmount}
                className="input"
                value={payModal.amount}
                onChange={(e) => setPayModal({ ...payModal, amount: e.target.value })}
              />
            </Field>
            <Field label="Payment Method">
              <select className="input" value={payModal.method} onChange={(e) => setPayModal({ ...payModal, method: e.target.value })}>
                {PAY_METHODS.map((m) => <option key={m} value={m}>{m}</option>)}
              </select>
            </Field>
            <Field label="Payment Reference / Note">
              <input className="input" placeholder="e.g. UPI Ref / Cash voucher" value={payModal.note} onChange={(e) => setPayModal({ ...payModal, note: e.target.value })} />
            </Field>
          </div>
        )}
      </Modal>

      {/* =====================================================================
          MODAL 10: PRINTABLE PDF PAYSLIP DETAILS
         ===================================================================== */}
      <PayslipModal payslip={activePayslip} onClose={() => setActivePayslip(null)} />
    </div>
  )
}
