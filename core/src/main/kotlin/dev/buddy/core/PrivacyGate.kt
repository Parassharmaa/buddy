package dev.buddy.core

sealed interface FilteredObservation {
    data class Allowed(val observation: Observation) : FilteredObservation
    data class Suppressed(val reason: String) : FilteredObservation
}

class ObservationPrivacyGate(private val excludedPackages: Set<String>) {
    fun filter(observation: Observation): FilteredObservation {
        if (observation.protected || observation.credentialSurface ||
            observation.excludedOverlay
        ) {
            return FilteredObservation.Suppressed("Sensitive or protected page")
        }
        if (observation.app in excludedPackages ||
            observation.nodes.any { it.packageName in excludedPackages }
        ) {
            return FilteredObservation.Suppressed("Excluded app")
        }
        // Until pixel-region masking is qualified, suppress the whole mixed observation.
        if (observation.nodes.any {
                it.password
            }
        ) {
            return FilteredObservation.Suppressed("Private input")
        }
        if (observation.nodes.size > 2_000 ||
            observation.nodes.sumOf { it.label.length } > 32_768
        ) {
            return FilteredObservation.Suppressed("Screen exceeds the observation limit")
        }
        return FilteredObservation.Allowed(observation)
    }
}
