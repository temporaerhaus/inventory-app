package de.temporaerhaus.inventory.client.model

data class InventoryItem(
    val number: String,
    val name: String?,
    val data: Map<String, Any>?
)
