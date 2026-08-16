package org.plos_clan.cpos.drivers.acpi.aml

internal sealed class AmlFlow {
    data object Next : AmlFlow()
    data class Returned(val value: AmlObject) : AmlFlow()
    data object Break : AmlFlow()
    data object Continue : AmlFlow()
    data object Failed : AmlFlow()
}

internal class AmlBudget(var remaining: Int = MAX_METHOD_OPERATIONS) {
    fun consume(): Boolean = --remaining >= 0
}
