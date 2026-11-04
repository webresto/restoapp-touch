package cx.m42.restoapp.marketing

import cx.m42.restoapp.render.NativeActions

/**
 * The screens of the client that belong to «Маркетинг» — one, for now: the editor of a promotion.
 *
 * The host registers them at startup, next to its native apps (`RestoAppHost`). A build that does not is a
 * client without the editor, and the server's action is then simply not drawn — which is the contract.
 */
public object MarketingScreens {

    /** The id the server names in `control: {type: "native", screen: …}`. */
    public const val PROMOTION_EDITOR: String = "promotion-editor"

    public fun register() {
        NativeActions.register(PROMOTION_EDITOR) { scope -> PromotionEditor(scope) }
    }
}
