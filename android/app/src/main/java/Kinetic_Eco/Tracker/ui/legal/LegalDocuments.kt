package Kinetic_Eco.Tracker.ui.legal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

private const val ASSET_TERMS_PDF = "legal/user_terms_and_conditions.pdf"
private const val CACHE_TERMS_NAME = "user_terms_share.pdf"

/**
 * Copies the bundled PDF from assets to cache and opens it with the system viewer.
 * @return true if an activity was started or likely will handle the intent
 */
fun openBundledTermsPdf(context: Context): Boolean {
    return try {
        context.assets.open(ASSET_TERMS_PDF).use { input ->
            val outFile = File(context.cacheDir, CACHE_TERMS_NAME)
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        val outFile = File(context.cacheDir, CACHE_TERMS_NAME)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        android.util.Log.e("LegalDocuments", "openBundledTermsPdf", e)
        false
    }
}
