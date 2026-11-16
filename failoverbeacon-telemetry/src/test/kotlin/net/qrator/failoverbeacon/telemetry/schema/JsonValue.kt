package net.qrator.failoverbeacon.telemetry.schema

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** Parsed JSON as maps, lists and exact numbers, so that key order and 1 versus 1.0 do not matter. */
fun jsonValue(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { jsonValue(value.get(it)) }
    is JSONArray -> List(value.length()) { jsonValue(value.get(it)) }
    is Number -> BigDecimal(value.toString()).stripTrailingZeros()
    else -> value
}
