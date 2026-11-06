package com.ammargais.accountapp.core

/** Currency definitions belong to a book. The legacy constants only decode pre-0.3 backups. */
data class Currency(val name: String, val title: String, val decimals: Int = 2) {
    fun validate() {
        require(name.matches(Regex("[A-Z][A-Z0-9_]{0,11}")) && title.isNotBlank() && title.length <= 60 && decimals in 0..8) {
            "اكتب رمزًا لاتينيًا من 1 إلى 12 حرفًا واسمًا للعملة، ودقة من 0 إلى 8"
        }
    }
    override fun toString() = name
    companion object {
        val AED = Currency("AED", "درهم إماراتي")
        val SDG = Currency("SDG", "جنيه سوداني")
        val SAR = Currency("SAR", "ريال سعودي")
        val EGP = Currency("EGP", "جنيه مصري")
        val USD = Currency("USD", "دولار أمريكي")
        val USDT = Currency("USDT", "تيثر", 6)
        val entries = listOf(AED, SDG, SAR, EGP, USD, USDT)
        fun valueOf(code: String) = entries.find { it.name == code } ?: throw IllegalArgumentException("عملة قديمة غير مدعومة: $code")
    }
}
