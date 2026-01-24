/**
 * Loads the Phish.net api key from gradle/system properties
 *
 * `robophish.phishnetApiKey=apiKey` for gradle properties.
 * `ROBOPHISH_PHISHNET_API_KEY=apiKey` for system property.
 *
 * Can then be used from the project's extras with `phishnetApiKey`
 */
loadPropertyIntoExtra(
        extraKey = "phishnetApiKey",
        projectPropertyKey = "phishnetApiKey",
        systemPropertyKey = "PHISHNET_API_KEY",
        defaultValue = ""
)
