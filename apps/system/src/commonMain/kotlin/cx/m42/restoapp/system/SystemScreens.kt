package cx.m42.restoapp.system

import cx.m42.restoapp.render.NativeActions

/**
 * The screens of the client that belong to «Система»: one, the progress of a job.
 *
 * The host registers it at startup, next to its native apps (`RestoAppHost`). A build that does not is a
 * client without the buttons «Установить», «Обновить» and «Удалить», which is the contract.
 */
public object SystemScreens {

    /** The id the server names in `control: {type: "native", screen: …}`. */
    public const val MODULE_PROGRESS: String = "module-progress"

    public fun register() {
        NativeActions.register(MODULE_PROGRESS) { scope -> ModuleProgress(scope) }
    }
}
