package dev.komichi.agent.security

object AppAccessPolicy {
    fun allows(packageName: String, ownPackage: String, fullAccess: Boolean, selected: Set<String>): Boolean =
        packageName != ownPackage && (fullAccess || packageName in selected)
}
