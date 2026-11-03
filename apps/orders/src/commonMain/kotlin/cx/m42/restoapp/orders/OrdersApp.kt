package cx.m42.restoapp.orders

import cx.m42.superizer.app.AppConfigSpec
import cx.m42.superizer.app.AppIcon
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppManifest
import cx.m42.superizer.app.AppMetadata
import cx.m42.superizer.app.SuperizerApp
import cx.m42.superizer.app.localized
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.serialization.Serializable

/** Nothing to configure yet; the type exists so a field can be added without a new contract. */
@Serializable
public data class OrdersConfig(val schemaVersion: Int = 1)

/**
 * «Заказы»: the server's current orders and the one thing an operator does to them — move each to
 * its next state, as RestoApp core's kanban does.
 *
 * A demo for now (D7): the orders are generated on the first launch and kept in the app's own
 * storage. The host gives every server a storage of its own, so every server has its own orders
 * without this app knowing servers exist.
 */
public class OrdersApp : SuperizerApp<OrdersConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "0.1.0",
        metadata = AppMetadata(
            title = localized("ru" to "Заказы", "en" to "Orders"),
            description = localized(
                "ru" to "Текущие заказы и их статусы: принять, готовить, передать курьеру",
                "en" to "Current orders and their states: accept, cook, hand to the courier",
            ),
            // A receipt with a torn bottom edge and two lines on it.
            icon = AppIcon.Path(
                "M6 2h12a1 1 0 0 1 1 1v19l-3-2-2.5 2-2.5-2-2.5 2L6 20l-1 .7V3a1 1 0 0 1 1-1z" +
                    "M8 7v2h8V7H8zM8 11v2h8v-2H8zM8 15v2h5v-2H8z",
            ),
            category = "Ресторан",
        ),
    )

    override val configSpec: AppConfigSpec<OrdersConfig> =
        AppConfigSpec(OrdersConfig.serializer(), OrdersConfig())

    override fun launch(runtime: InstanceRuntime, config: OrdersConfig): AppInstance =
        OrdersInstance(runtime)

    public companion object {
        public const val ID: String = "orders"
    }
}
