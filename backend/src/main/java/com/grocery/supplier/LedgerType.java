package com.grocery.supplier;

/** PURCHASE and DEBIT_ADJUSTMENT increase what we owe; PAYMENT and CREDIT_ADJUSTMENT reduce it. */
public enum LedgerType { PURCHASE, PAYMENT, DEBIT_ADJUSTMENT, CREDIT_ADJUSTMENT }
