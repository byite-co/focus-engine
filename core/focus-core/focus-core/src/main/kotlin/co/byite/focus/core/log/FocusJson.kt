package co.byite.focus.core.log

import kotlinx.serialization.json.Json

/** Shared JSON configurations. Unknown keys are ignored so newer logs still replay. */
object FocusJson {
    val compact: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    val pretty: Json = Json(compact) {
        prettyPrint = true
    }
}
