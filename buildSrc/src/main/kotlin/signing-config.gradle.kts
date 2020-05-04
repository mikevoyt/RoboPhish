/**
 * Plugin to read keystore properties from the system or project properties and add them to the
 * project's extras.
 *
 * If values are not provided default values for the debug keystore are added.
 */

loadPropertyIntoExtra(
        extraKey = "keystorePassword",
        projectPropertyKey = "keystorePassword",
        systemPropertyKey = "KEYSTORE_PASSWORD",
        defaultValue = "android"
)

loadPropertyIntoExtra(
        extraKey = "aliasKeyPassword",
        projectPropertyKey = "aliasKeyPassword",
        systemPropertyKey = "KEY_PASSWORD",
        defaultValue = "android"
)

loadPropertyIntoExtra(
        extraKey = "storeKeyAlias",
        projectPropertyKey = "storeKeyAlias",
        systemPropertyKey = "KEY_ALIAS",
        defaultValue = "androiddebugkey"
)

loadPropertyIntoExtra(
        extraKey = "keystoreLocation",
        projectPropertyKey = "keystoreLocation",
        systemPropertyKey = "KEYSTORE_LOCATION",
        defaultValue = "$rootDir/keystore/debug.keystore"
)
