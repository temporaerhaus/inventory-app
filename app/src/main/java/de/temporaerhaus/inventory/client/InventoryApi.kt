package de.temporaerhaus.inventory.client

import android.util.Log
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Tag
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.POST
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class InventoryApi(val baseUrl: String) {
    private val dokuwikiApi: DokuwikiApi by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl("${baseUrl}/")
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(DokuwikiApi::class.java)
    }

    suspend fun getInventoryData(
        inventoryNumber: String,
    ): InventoryItem {
        try {
            val response = dokuwikiApi.getPageContent(DokuwikiApi.PageRequest("inventar/${inventoryNumber}"))
            Log.d(TAG, "Response: $response")
            val name = extractHeadingFromMarkdown(response.result)
            val yamlBlock = extractYamlBlockFromMarkdown(response.result)
            Log.d(TAG, "Extracted Name: $name")
            Log.d(TAG, "Extracted Data: $yamlBlock")
            if (yamlBlock != null) {
                return InventoryItem(
                    number = inventoryNumber,
                    name = name,
                    data = yamlBlock
                )
            } else {
                throw IOException("No YAML block found. Is this a existing inventory item?")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            throw IOException("Network error: ${e.message}")
        }
    }

    suspend fun writeAsSeen(
        item: InventoryItem,
        lastContainerItem: InventoryItem? = null
    ): InventoryItem? {
        try {
            val response = dokuwikiApi.getPageContent(DokuwikiApi.PageRequest("inventar/${item.number}"))
            val yamlBlock = extractYamlBlockFromMarkdown(response.result)
            if (yamlBlock != null) {
                val updatedYamlBlock = yamlBlock.toMutableMap()

                val nowIso = LocalDateTime
                    .now()
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

                updatedYamlBlock["lastSeenAt"] = nowIso

                if (updatedYamlBlock["temporary"] != null && (item.data!!["temporary"] as Map<*, *>).isEmpty()) {
                    updatedYamlBlock["temporary"] = mapOf<String, Any>()
                }

                if (updatedYamlBlock["nominal"] != null && (item.data!!["nominal"] as Map<*, *>).isEmpty()) {
                    updatedYamlBlock["nominal"] = mapOf<String, Any>()
                }

                if (lastContainerItem != null && lastContainerItem.number != item.number) {
                    updatedYamlBlock["temporary"] = mapOf(
                        "description" to "",
                        "location" to lastContainerItem.number,
                        "timestamp" to nowIso
                    )
                }

                val updatedContent = replaceYamlBlockInMarkdown(response.result, updatedYamlBlock)

                dokuwikiApi.writePageContent(DokuwikiApi.SavePageRequest("inventar/${item.number}", updatedContent))

                Log.d(TAG, "Updated content: $updatedContent")
                return item.copy(data = updatedYamlBlock)
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Network error: ${e.message}")
            throw IOException("Network error: ${e.message}")
        }
    }


    private fun extractHeadingFromMarkdown(markdown: String): String? {
        val lines = markdown.split("\n")
        for (line in lines) {
            if (line.startsWith("#")) {
                return line.substringAfter("#").trim()
            }
        }
        return null
    }

    class NoTimestampSafeConstructor : SafeConstructor {
        constructor(loaderOptions: LoaderOptions) : super(loaderOptions) {
            this.yamlConstructors[Tag.TIMESTAMP] = ConstructYamlStr()
        }
    }

    private fun extractYamlBlockFromMarkdown(markdown: String): Map<String, Any>? {
        val yaml = Yaml(NoTimestampSafeConstructor(LoaderOptions()))
        val codeBlocks = markdown.split("```yaml").drop(1)
        for (block in codeBlocks) {
            val code = block.split("```").firstOrNull() ?: continue
            if (code.isEmpty()) {
                continue
            }
            try {
                val data: Map<String, Any> = yaml.load(code) ?: continue
                if (data.containsKey("inventory")) {
                    return data
                }
            } catch (e: Exception) {
                Log.e(TAG, "YAML parsing error: ${e.message}")
                continue
            }
        }
        return null
    }

    private fun replaceYamlBlockInMarkdown(markdown: String, data: Map<String, Any>): String {
        val dumperOptions = DumperOptions().apply {
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
            isPrettyFlow = true
            defaultFlowStyle = DumperOptions.FlowStyle.FLOW
        }
        val yaml = Yaml(dumperOptions)
        var code = yaml.dump(data)

        // a bit cleaner yaml: empty {\n  } objects collapse to a single line {}
        code = code.replace(Regex("\\{\\s*\\}"), "{}")

        return markdown.replace(Regex("```yaml.*?```", RegexOption.DOT_MATCHES_ALL), "```yaml\n$code\n```")
    }

    interface DokuwikiApi {
        data class PageRequest(val page: String)
        data class RpcResponse(val result: String, val error: Any?)

        @POST("/lib/exe/jsonrpc.php/core.getPage")
        @retrofit2.http.Headers("Content-Type: application/json")
        suspend fun getPageContent(@retrofit2.http.Body request: PageRequest): RpcResponse

        data class SavePageRequest(
            val page: String,
            val text: String,
            val summary: String = "Updated via inventory app",
            val isminor: Boolean = false
        )

        @POST("/lib/exe/jsonrpc.php/core.savePage")
        @retrofit2.http.Headers("Content-Type: application/json")
        suspend fun writePageContent(
            @retrofit2.http.Body request: SavePageRequest,
        ): RpcResponse
    }
}