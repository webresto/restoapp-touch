package cx.m42.restoapp.stock

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
public data class StockConfig(val schemaVersion: Int = 1)

/**
 * «Склад»: what is left of each dish, and the three things an operator does about it — count it,
 * put it on stop, or switch it off. RestoApp core's stock manager, as an app.
 *
 * A demo for now (D7): the menu is generated on the first launch and kept in the app's own storage,
 * which the host keeps per server.
 */
public class StockApp : SuperizerApp<StockConfig>() {

    override val manifest: AppManifest = AppManifest(
        id = AppId(ID),
        version = "0.1.0",
        metadata = AppMetadata(
            title = localized("ru" to "Склад", "en" to "Stock"),
            description = localized(
                "ru" to "Остатки блюд: безлимит, сколько осталось, стоп-лист",
                "en" to "What is left of each dish: unlimited, counted, on stop",
            ),
            // A box, seen from the front: lid, body, and a label.
            icon = AppIcon.Path(
                "M3 4a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V9" +
                    "a1 1 0 0 1-1-1V4zM5 5v2h14V5H5zM6 9v10h12V9H6zM9 11h6v2H9v-2z",
            ),
            category = "Ресторан",
        ),
    )

    override val configSpec: AppConfigSpec<StockConfig> =
        AppConfigSpec(StockConfig.serializer(), StockConfig())

    override fun launch(runtime: InstanceRuntime, config: StockConfig): AppInstance =
        StockInstance(runtime)

    public companion object {
        public const val ID: String = "stock"
    }
}
