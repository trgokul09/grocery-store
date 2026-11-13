package com.grocery.expense;

import com.grocery.audit.AuditService;
import com.grocery.common.CurrentUser;
import com.grocery.common.Exceptions.*;
import com.grocery.common.PaymentMethod;
import com.grocery.common.StoreClock;
import com.grocery.shift.ShiftRepository;
import com.grocery.shift.ShiftStatus;
import com.grocery.user.User;
import com.grocery.user.UserRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {
    /** Categories a shift worker is authorised to record on the spot. */
    private static final Set<ExpenseCategory> STAFF_ALLOWED = EnumSet.of(ExpenseCategory.TRANSPORTATION, ExpenseCategory.MAINTENANCE, ExpenseCategory.MISCELLANEOUS);

    public record ExpenseRequest(@NotNull ExpenseCategory category, @NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull PaymentMethod method,
                                 @NotBlank @Size(max = 255) String description, LocalDate expenseDate) {}
    public record ExpenseDto(Long id, ExpenseCategory category, BigDecimal amount, PaymentMethod method, String description, LocalDate expenseDate,
                             String recordedBy, Instant createdAt) {}

    private final ExpenseRepository repo; private final CurrentUser cu; private final ShiftRepository shifts; private final AuditService audit;
    private final StoreClock clock; private final UserRepository users;
    public ExpenseController(ExpenseRepository r, CurrentUser cu, ShiftRepository s, AuditService a, StoreClock c, UserRepository u) { repo = r; this.cu = cu; shifts = s; audit = a; clock = c; users = u; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional
    public ExpenseDto create(@Valid @RequestBody ExpenseRequest r) {
        User u = cu.require();
        Expense e = new Expense();
        e.setCategory(r.category()); e.setAmount(r.amount()); e.setMethod(r.method()); e.setDescription(r.description().trim());
        e.setCreatedBy(u.getId());
        var shift = shifts.findByUserIdAndStatus(u.getId(), ShiftStatus.OPEN);
        if (cu.isStaff()) {
            if (!STAFF_ALLOWED.contains(r.category())) throw new BusinessException("Staff can only record transportation, maintenance or miscellaneous expenses");
            if (shift.isEmpty()) throw new BusinessException("Open your shift before recording expenses");
            e.setExpenseDate(clock.today());
        } else e.setExpenseDate(r.expenseDate() == null ? clock.today() : r.expenseDate());
        Long shiftId = shift.map(s -> s.getId()).orElse(null);
        e.setShiftId(shiftId);
        Expense saved = repo.save(e);
        audit.log("CREATE", "EXPENSE", saved.getId(), null, Map.of("category", r.category().name(), "amount", r.amount(), "method", r.method().name()));
        return dto(saved, u.getFullName());
    }

    @GetMapping @Transactional(readOnly = true)
    public Page<ExpenseDto> list(@RequestParam(required = false) ExpenseCategory category,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        boolean staff = cu.isStaff(); Long uid = cu.require().getId();
        LocalDate effFrom = staff ? clock.today() : from;     // staff: only their own expenses today
        Specification<Expense> spec = (root, q, cb) -> {
            var p = new ArrayList<Predicate>();
            if (staff) p.add(cb.equal(root.get("createdBy"), uid));
            if (category != null) p.add(cb.equal(root.get("category"), category));
            if (effFrom != null) p.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("expenseDate"), effFrom));
            if (to != null) p.add(cb.lessThanOrEqualTo(root.<LocalDate>get("expenseDate"), to));
            return cb.and(p.toArray(new Predicate[0]));
        };
        Map<Long, String> names = new HashMap<>();
        return repo.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "expenseDate", "id")))
            .map(e -> dto(e, names.computeIfAbsent(e.getCreatedBy(), id -> users.findById(id).map(User::getFullName).orElse("?"))));
    }

    private static ExpenseDto dto(Expense e, String by) {
        return new ExpenseDto(e.getId(), e.getCategory(), e.getAmount(), e.getMethod(), e.getDescription(), e.getExpenseDate(), by, e.getCreatedAt());
    }
}
