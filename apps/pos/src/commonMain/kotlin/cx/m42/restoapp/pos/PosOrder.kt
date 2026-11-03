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
    /** The product has modifier groups: `pos.options` says what they are. */
    val hasOptions: Boolean = false,
    /** What was chosen of them — what the kitchen will read off this line. */
    val options: List<PosLineOption> = emptyList(),
)

/** One chosen modifier, as the line shows it. */
@Serializable
public data class PosLineOption(val id: String, val name: String = "", val amount: Int = 0)

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
    /** The basket is not empty and the guest has a phone: there is something to check. */
    val canCheck: Boolean = false,
    /** Checked and not placed yet. */
    val canPlace: Boolean = false,
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

/** What a step of the order answers: the whole order, and the server's word about the step. */
@Serializable
public data class PosStep(
    val order: PosOrder? = null,
    val message: PosMessage? = null,
    /** false — the server refused: the receipt stays and the message says why. */
    val ok: Boolean = true,
)

@Serializable
public data class PosMessage(val text: String = "", val tone: String = "info")

/** A product of the catalog, priced and counted for this order by the server. */
@Serializable
public data class PosProduct(
    val id: String,
    val name: String = "",
    val code: String? = null,
    val groupId: String? = null,
    val price: Double = 0.0,
    /** The price a promotion makes; null when none takes it. */
    val salePrice: Double? = null,
    /** -1 unlimited, 0 stopped at this point. */
    val balance: Double = -1.0,
    val weight: Double? = null,
    val imageUrl: String? = null,
    val hasOptions: Boolean = false,
    /** How many of it the receipt already has. */
    val inOrder: Int = 0,
)

@Serializable
public data class PosGroup(val id: String, val name: String = "", val imageUrl: String? = null)

/**
 * One option of a modifier group: a product of the menu sold with the dish. The numbers are the
 * rules the server read off the dish — the chooser draws them, the server checks them.
 */
@Serializable
public data class PosOption(
    val id: String,
    val name: String = "",
    /** What one of it costs on top of the dish. */
    val price: Double = 0.0,
    /** -1 unlimited, 0 stopped at this point. */
    val balance: Double = -1.0,
    /** How many are chosen now: the server's defaults, or what the line already has. */
    val amount: Int = 0,
    val minAmount: Int = 0,
    val maxAmount: Int? = null,
    /** How many of it are not charged for. */
    val freeAmount: Int = 0,
    val defaultAmount: Int = 0,
)

/** A group of modifiers of one dish, with how many of it may be taken. */
@Serializable
public data class PosOptionGroup(
    val id: String,
    val name: String = "",
    val required: Boolean = false,
    val minAmount: Int = 0,
    val maxAmount: Int? = null,
    /** At most one of the group: a choice, not a counter. */
    val single: Boolean = false,
    val options: List<PosOption> = emptyList(),
)

/**
 * `pos.options`: the dish and what may be chosen with it.
 *
 * No total: what the dish with these options costs is the server's arithmetic, and it is read off
 * the receipt once the line is written.
 */
@Serializable
public data class PosDishOptions(
    val dishId: String,
    val name: String = "",
    val price: Double = 0.0,
    /** The line being changed, when the chooser was opened from the receipt; null — a new line. */
    val lineId: Int? = null,
    val amount: Int = 1,
    val groups: List<PosOptionGroup> = emptyList(),
)

/** `pos.catalog`: where we are in the menu, what is one level down, and what can be added. */
@Serializable
public data class PosCatalog(
    val path: List<PosGroup> = emptyList(),
    val groups: List<PosGroup> = emptyList(),
    val items: List<PosProduct> = emptyList(),
    val nextCursor: String? = null,
    val total: Int = 0,
)

/** One way of handing the food over, as this point can do it. */
@Serializable
public data class PosServiceOption(val id: String, val available: Boolean = false, val reason: String? = null)

@Serializable
public data class PosServiceOptions(val options: List<PosServiceOption> = emptyList())

/** When the order can be had: epoch milliseconds, as the server counted the kitchens' hours. */
@Serializable
public data class PosTimeOptions(
    val asap: Boolean = false,
    val slots: List<List<Long>> = emptyList(),
    val minCookingMinutes: Int = 0,
)

@Serializable
public data class PosPaymentOption(
    val id: String,
    val title: String = "",
    val isPromise: Boolean = false,
    val available: Boolean = false,
)

@Serializable
public data class PosPayments(val items: List<PosPaymentOption> = emptyList())

@Serializable
public data class PosNamed(val id: String, val name: String = "")

@Serializable
public data class PosNamedList(val items: List<PosNamed> = emptyList())

/** A saved address of the customer. */
@Serializable
public data class PosSavedAddress(val id: String, val formatted: String = "")

@Serializable
public data class PosSavedAddresses(val items: List<PosSavedAddress> = emptyList())

/** A customer as the POS lists them — the same rows «Клиенты» shows. */
@Serializable
public data class PosClient(
    val id: String,
    val title: String = "",
    val phone: String? = null,
    val orderCount: Int = 0,
)

@Serializable
public data class PosClients(val items: List<PosClient> = emptyList(), val nextCursor: String? = null)
