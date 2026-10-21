package com.openphone.agent.llm

data class CatalogModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val sizeMB: Int,
    val ramHint: String,
    val description: String,
)

object ModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            id = "gemma-4-e2b",
            displayName = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it.litertlm",
            url = "$HF/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeMB = 2588,
            ramHint = "≥6 GB",
            description = "Recommended. Fast on GPU, multimodal, enough to drive most apps.",
        ),
        CatalogModel(
            id = "gemma-4-e4b",
            displayName = "Gemma 4 E4B",
            fileName = "gemma-4-E4B-it.litertlm",
            url = "$HF/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sizeMB = 3660,
            ramHint = "≥8 GB",
            description = "Better at multi-step goals, slower and heavier on RAM and battery.",
        ),
    )

    fun byFileName(name: String): CatalogModel? = models.firstOrNull { it.fileName == name }

    fun isModelFile(name: String): Boolean =
        name.endsWith(".litertlm", ignoreCase = true) || name.endsWith(".task", ignoreCase = true)
}
