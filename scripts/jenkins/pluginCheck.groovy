List plugins = []
List available = []
List expected = []

String input = "${PLUGIN_LIST}"
input.split(",").toList().stream()
        .filter { it.split(":")[0] != "prometheus" } // prometheus does not support dynamic loading and will be installed when restarting.
        .collect()
        .each { it -> expected.add(it.substring(0, it.indexOf(":"))) }

Jenkins.instance.pluginManager.failedPlugins.each {
    available.add(it.name)
}

Jenkins.instance.pluginManager.plugins.each {
    available.add(it.shortName)
}

available.each { p ->
    if (expected.find { (it == p) })
        plugins.add(p)
}

def commons = plugins.intersect(expected)
def difference = plugins.plus(expected)
difference.removeAll(commons)

return difference