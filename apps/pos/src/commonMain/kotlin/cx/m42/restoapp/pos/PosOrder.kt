package cx.m42.restoapp.pos

import kotlinx.serialization.Serializable

/*
 * What the POS methods of the hook answer (`api/hooks/app-mobile-api/apps/pos.ts`): the order as the
 * server counted it, line by line and total by total. Nothing here is computed on the device — not a
 * line total, not the change from a note — because then the receipt and the order would be two
 * different things.
 */

/** One line of the receipt: a product, how many, and what the server says it costs. */
@Serializable
public data class PosLine(
    /** The line, not the product: core's `orderdish.id`. */
    val lineId: Int,
    val dishId: String = "",
    val name: String = "",
    val amount: Int = 0,
    val price: Double = 0.0,
    val total: Double = 0.0,
    /** The price before a promotion took the line, when one did: shown struck through. */
    val oldPrice: Double? = null,
    val comment: String? = null,
    /** The product has modifier groups; core fills the defaults until the chooser exists. */
    val hasOptions: Boolean = false,
)

/** The draft, or a placed order, as the receipt shows it. */
@Serializable
public data class PosOrder(
    val id: String,
    /** What staff call it: `shortId` until the RMS gives it a number. */
    val number: String = "",
    val state: String = "",
    val serviceType: String = DELIVERY,
    val placeId: String = "",
    /** Where it is cooked, once core has decided — a delivery may be another kitchen's. */
    val cookingPlaceId: String? = null,
    val cookingPlaceTitle: String? = null,
    val table: String? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val clientId: String? = null,
    val address: String? = null,
    val comment: String? = null,
    val personsCount: Int? = null,
    /** Epoch milliseconds, or null for «as soon as possible». */
    val date: Long? = null,
    val maxWaitMinutes: Int? = null,
    val paymentMethodId: String? = null,
    val paymentMethodTitle: String? = null,
    val promotionCode: String? = null,
    val lines: List<PosLine> = emptyList(),
    val dishesCount: Int = 0,
    val basketTotal: Double = 0.0,
    val discountTotal: Double = 0.0,
    val deliveryCost: Double = 0.0,
    val bonusesTotal: Double = 0.0,
    val total: Double = 0.0,
    /** What core said about the last recount: shown under the receipt, verbatim. */
    val message: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    public companion object {
        public const val DELIVERY: String = "delivery"
        public const val PICKUP: String = "pickup"
        public const val DINE_IN: String = "dine-in"
    }
}

/** `pos.order`: one draft when asked by id, otherwise the drafts of this person at this point. */
@Serializable
public data class PosOrderAnswer(
    val order: PosOrder? = null,
    val drafts: List<PosOrder> = emptyList(),
)
