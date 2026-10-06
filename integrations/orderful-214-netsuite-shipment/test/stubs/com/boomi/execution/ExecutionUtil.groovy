package com.boomi.execution

import java.util.logging.Logger

/** Minimal stand-in for Boomi's ExecutionUtil so the scripts run outside an Atom. */
class ExecutionUtil {
    static Map<String, String> dpps = [:]
    static Logger getBaseLogger() { Logger.getLogger('boomi') }
    static String getDynamicProcessProperty(String name) { dpps[name] }
    static void setDynamicProcessProperty(String name, String value, boolean persist) { dpps[name] = value }
}
