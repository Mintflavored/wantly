package com.nervs.wantly.ui.common

/** Форматирует цену с символом валюты (₽/$/€/£/₸/₴). null → null (поле не показываем). */
fun formatPrice(price: Double?, currency: String?): String? {
    if (price == null) return null
    val sym = when (currency?.trim()?.uppercase()) {
        "RUB" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        "KZT" -> "₸"
        "UAH" -> "₴"
        else -> null
    }
    val num = if (price % 1.0 == 0.0) "%,.0f".format(price) else "%,.2f".format(price)
    return when {
        sym == null && currency.isNullOrBlank() -> num
        sym == null -> "$num $currency"
        sym == "$" || sym == "€" || sym == "£" -> "$sym$num"
        else -> "$num $sym"
    }
}
