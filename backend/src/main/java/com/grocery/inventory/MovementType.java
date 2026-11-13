package com.grocery.inventory;

public enum MovementType {
    SALE(false), PURCHASE_RECEIPT(true), MANUAL_ADD(true), MANUAL_DEDUCT(false), DAMAGE(false), RETURN(true);
    private final boolean increase;
    MovementType(boolean increase) { this.increase = increase; }
    public boolean isIncrease() { return increase; }
}
