package com.bayapps.android.robophish

/**
 * Allow for debug and release builds to implement there own custom logic,
 * like what to log, and what keys to use. Each build variant should override this
 * and provide it with Kodein.
 */
interface AppInitializer {
    operator fun invoke() { /* Default do nothing implementation */ }
}
