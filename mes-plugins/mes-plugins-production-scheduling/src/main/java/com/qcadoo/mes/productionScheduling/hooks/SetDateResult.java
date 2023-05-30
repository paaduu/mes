package com.qcadoo.mes.productionScheduling.hooks;

public enum SetDateResult {
    NONE("00none"), ERROR("01error"), START_DATE_NEAREST("02startDateNearest"), FINISH_DATE_NEAREST("03finishDateNearest"), BOTH("04both");

    private final String result;

    private SetDateResult(final String result) {
        this.result = result;
    }

    public String getStringValue() {
        return result;
    }

}
