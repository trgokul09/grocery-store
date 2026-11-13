package com.grocery.shift;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import com.grocery.common.PaymentMethod;
import com.grocery.expense.ExpenseRepository;
import com.grocery.notification.NotificationService;
import com.grocery.sale.SalePaymentRepository;
import com.grocery.supplier.SupplierPaymentRepository;
import com.grocery.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

/** Expected Cash = Opening Cash + Cash Sales - Cash Expenses - Cash Withdrawals (cash paid to suppliers). */
@Service
public class ShiftService {
    public record OpenRequest(@NotNull(message = "Opening cash is required to start a shift") @DecimalMin(value = "0.00", message = "Opening cash cannot be negative") BigDecimal openingCash) {}
    public record CloseRequest(@NotNull @DecimalMin("0.00") BigDecimal countedCash, @Size(max = 500) String notes) {}
    public record ShiftDto(Long id, Long userId, String username, String fullName, Instant openedAt, Instant closedAt, BigDecimal openingCash,
                           BigDecimal cashSales, BigDecimal cashExpenses, BigDecimal supplierCashPayments, BigDecimal expectedCash,
                           BigDecimal countedCash, BigDecimal variance, ShiftStatus status, String notes) {}

    private final ShiftRepository shifts; private final SalePaymentRepository salePayments; private final ExpenseRepository expenses;
    private final SupplierPaymentRepository supplierPayments; private final CurrentUser cu; private final AuditService audit; private final NotificationService notifications;

    public ShiftService(ShiftRepository s, SalePaymentRepository sp, ExpenseRepository e, SupplierPaymentRepository pay, CurrentUser cu, AuditService a, NotificationService n) {
        shifts = s; salePayments = sp; expenses = e; supplierPayments = pay; this.cu = cu; audit = a; notifications = n;
    }

    @Transactional
    public ShiftDto open(OpenRequest r) {
        User u = cu.require();
        if (shifts.findByUserIdAndStatus(u.getId(), ShiftStatus.OPEN).isPresent()) throw new BusinessException("You already have an open shift");
        Shift s = new Shift();
        s.setUser(u); s.setOpeningCash(r.openingCash());
        s = shifts.save(s);
        audit.log("OPEN", "SHIFT", s.getId(), null, Map.of("openingCash", r.openingCash()));
        return dto(s);
    }

    @Transactional(readOnly = true)
    public ShiftDto current() {
        return shifts.findByUserIdAndStatus(cu.require().getId(), ShiftStatus.OPEN).map(this::dto).orElse(null);
    }

    @Transactional
    public ShiftDto close(CloseRequest r) {
        User u = cu.require();
        Shift s = shifts.findByUserIdAndStatus(u.getId(), ShiftStatus.OPEN).orElseThrow(() -> new BusinessException("No open shift to close"));
        live(s);
        s.setCountedCash(r.countedCash());
        s.setVariance(r.countedCash().subtract(s.getExpectedCash()).setScale(2, RoundingMode.HALF_UP));
        s.setClosedAt(Instant.now()); s.setStatus(ShiftStatus.CLOSED); s.setNotes(r.notes());
        audit.log("CLOSE", "SHIFT", s.getId(), null, Map.of("expected", s.getExpectedCash(), "counted", r.countedCash(), "variance", s.getVariance()));
        if (s.getVariance().signum() != 0) notifications.shiftVariance(s.getId(), u.getFullName(), s.getVariance());
        return dto(s);
    }

    @Transactional(readOnly = true)
    public Page<ShiftDto> list(Pageable pg) {
        Page<Shift> page = cu.isStaff() ? shifts.findByUserId(cu.require().getId(), pg) : shifts.findAll(pg);
        return page.map(this::dto);
    }

    @Transactional(readOnly = true)
    public ShiftDto get(Long id) {
        Shift s = shifts.findById(id).orElseThrow(() -> new NotFoundException("Shift not found"));
        if (cu.isStaff() && !s.getUser().getId().equals(cu.require().getId())) throw new NotFoundException("Shift not found");
        return dto(s);
    }

    /** For an OPEN shift, (re)computes the running totals; closed shifts keep their stored snapshot. */
    private void live(Shift s) {
        if (s.getStatus() != ShiftStatus.OPEN) return;
        s.setCashSales(salePayments.sumByShiftAndMethod(s.getId(), PaymentMethod.CASH));
        s.setCashExpenses(expenses.sumByShiftAndMethod(s.getId(), PaymentMethod.CASH));
        s.setSupplierCashPayments(supplierPayments.sumByShiftAndMethod(s.getId(), PaymentMethod.CASH));
        s.setExpectedCash(s.getOpeningCash().add(s.getCashSales()).subtract(s.getCashExpenses()).subtract(s.getSupplierCashPayments()));
    }

    private ShiftDto dto(Shift s) {
        BigDecimal cs = s.getCashSales(), ce = s.getCashExpenses(), sp = s.getSupplierCashPayments(), exp = s.getExpectedCash();
        if (s.getStatus() == ShiftStatus.OPEN) {   // compute without mutating the entity
            cs = salePayments.sumByShiftAndMethod(s.getId(), PaymentMethod.CASH);
            ce = expenses.sumByShiftAndMethod(s.getId(), PaymentMethod.CASH);
            sp = supplierPayments.sumByShiftAndMethod(s.getId(), PaymentMethod.CASH);
            exp = s.getOpeningCash().add(cs).subtract(ce).subtract(sp);
        }
        return new ShiftDto(s.getId(), s.getUser().getId(), s.getUser().getUsername(), s.getUser().getFullName(), s.getOpenedAt(), s.getClosedAt(),
            s.getOpeningCash(), cs, ce, sp, exp, s.getCountedCash(), s.getVariance(), s.getStatus(), s.getNotes());
    }
}
