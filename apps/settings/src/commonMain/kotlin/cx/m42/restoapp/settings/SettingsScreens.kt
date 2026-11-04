package cx.m42.restoapp.settings

import cx.m42.restoapp.render.NativeActions

/**
 * The screens of the client that belong to «Настройки»: the editor of one value, and the transfer of
 * them all.
 *
 * The host registers them at startup, next to its native apps (`RestoAppHost`). A build that does not
 * is a client without the editor, and the server's action is then simply not drawn — which is the
 * contract of `control: {type: "native"}`.
 */
public object SettingsScreens {

    /** The ids the server names in `control: {type: "native", screen: …}`. */
    public const val SETTING_EDITOR: String = "setting-editor"
    public const val SETTING_TRANSFER: String = "setting-transfer"

    public fun register() {
        NativeActions.register(SETTING_EDITOR) { scope -> SettingEditor(scope) }
        NativeActions.register(SETTING_TRANSFER) { scope -> SettingTransfer(scope) }
    }
}
