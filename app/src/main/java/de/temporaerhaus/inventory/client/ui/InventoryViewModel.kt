package de.temporaerhaus.inventory.client.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.temporaerhaus.inventory.client.InventoryApi
import de.temporaerhaus.inventory.client.model.InventoryItem
import de.temporaerhaus.inventory.client.model.LocationMode
import de.temporaerhaus.inventory.client.util.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDateTime

class InventoryViewModel(private val inventoryApi: InventoryApi) : ViewModel() {
    var inventoryNumber by mutableStateOf("")
    var item by mutableStateOf<InventoryItem?>(null)
    var errorMessage by mutableStateOf<String?>(null)
    var needsSaving by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var saved by mutableStateOf(false)
    var autoSave by mutableStateOf(true)
    var lastContainerItem by mutableStateOf<InventoryItem?>(null)
    var locationMode by mutableStateOf(LocationMode.Temporary)
    var lastItemWasScanned by mutableStateOf(false)
    var now = mutableStateOf(LocalDateTime.now())

    fun updateNow() {
        now.value = LocalDateTime.now()
    }

    fun search() {
        errorMessage = null
        item = null
        isSaving = false
        needsSaving = false
        saved = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetchedItem = inventoryApi.getInventoryData(inventoryNumber)
                item = fetchedItem
                if (fetchedItem.data?.getOrDefault("container", false) == true) {
                    lastContainerItem = fetchedItem
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error getting data: ${e.message}")
                errorMessage = e.message
            } catch (e: Exception) {
                Log.e(TAG, "Error getting data: ${e.message}")
                errorMessage = "Error getting data: ${e.message}"
            }
        }
    }

    fun onBarcodeScanned(barcode: String) {
        if (barcode.isNotEmpty() && barcode != inventoryNumber) {
            inventoryNumber = barcode
            lastItemWasScanned = true
            search()
        }
    }

    fun markAsSeen(onFinished: () -> Unit = {}) {
        if (!isSaving && !saved && item != null) {
            isSaving = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val newItem = inventoryApi.writeAsSeen(item!!, lastContainerItem, locationMode)
                    if (newItem != null) {
                        item = newItem
                        saved = true
                        needsSaving = false
                        onFinished()
                    }
                } catch (e: IOException) {
                    errorMessage = e.message
                } finally {
                    isSaving = false
                }
            }
        }
    }

    fun toggleAutoSave() {
        autoSave = !autoSave
    }

    fun forgetLastContainerItem() {
        lastContainerItem = null
    }

    fun openItemFromDescription(number: String) {
        inventoryNumber = number
        lastItemWasScanned = false
        search()
    }

    fun removeLocation(key: String) {
        item?.let { currentItem ->
            Log.d(TAG, "remove location: $key")
            val newItemData = currentItem.data?.toMutableMap() ?: mutableMapOf()
            when (key) {
                "temporary.location" -> newItemData["temporary"] = mapOf<String, Any>()
                "nominal.location" -> newItemData["nominal"] = mapOf<String, Any>()
                else -> Log.e(TAG, "key $key unknown")
            }
            item = currentItem.copy(data = newItemData)
            needsSaving = true
        }
    }

    companion object {
        fun provideFactory(inventoryApi: InventoryApi): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InventoryViewModel(inventoryApi)
            }
        }
    }
}
