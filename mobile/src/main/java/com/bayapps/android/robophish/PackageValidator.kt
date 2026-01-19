package com.bayapps.android.robophish

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Build
import android.os.Process
import android.util.Base64
import org.xmlpull.v1.XmlPullParserException
import timber.log.Timber
import java.io.IOException

/**
 * Validates that the calling package is authorized to browse a MediaBrowserService.
 */
class PackageValidator(ctx: Context) {
    private val validCertificates: Map<String, List<CallerInfo>> =
        readValidCertificates(ctx.resources.getXml(R.xml.allowed_media_browser_callers))

    private fun readValidCertificates(parser: XmlResourceParser): Map<String, List<CallerInfo>> {
        val validCertificates = HashMap<String, MutableList<CallerInfo>>()
        try {
            var eventType = parser.next()
            while (eventType != XmlResourceParser.END_DOCUMENT) {
                if (eventType == XmlResourceParser.START_TAG &&
                    parser.name == "signing_certificate"
                ) {
                    val name = parser.getAttributeValue(null, "name")
                    val packageName = parser.getAttributeValue(null, "package")
                    val isRelease = parser.getAttributeBooleanValue(null, "release", false)
                    val certificate = parser.nextText().replace("\\s|\\n".toRegex(), "")

                    val info = CallerInfo(name, packageName, isRelease)
                    val infos = validCertificates.getOrPut(certificate) { mutableListOf() }
                    Timber.v(
                        "Adding allowed caller: %s, package=%s release=%s certificate=%s",
                        info.name,
                        info.packageName,
                        info.release,
                        certificate
                    )
                    infos.add(info)
                }
                eventType = parser.next()
            }
        } catch (e: XmlPullParserException) {
            Timber.e(e, "Could not read allowed callers from XML.")
        } catch (e: IOException) {
            Timber.e(e, "Could not read allowed callers from XML.")
        }
        return validCertificates
    }

    /**
     * @return false if the caller is not authorized to get data from this MediaBrowserService
     */
    fun isCallerAllowed(context: Context, callingPackage: String, callingUid: Int): Boolean {
        if (Process.SYSTEM_UID == callingUid || Process.myUid() == callingUid) {
            return true
        }
        val packageManager = context.packageManager
        val packageInfo: PackageInfo = try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            packageManager.getPackageInfo(callingPackage, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.w(e, "Package manager can't find package: %s", callingPackage)
            return false
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        if (signatures == null || signatures.size != 1) {
            Timber.w("Caller has more than one signature certificate!")
            return false
        }
        val signature = Base64.encodeToString(
            signatures[0].toByteArray(),
            Base64.NO_WRAP
        )

        val validCallers = validCertificates[signature]
        if (validCallers.isNullOrEmpty()) {
            Timber.v("Signature for caller %s is not valid: \n %s", callingPackage, signature)
            if (validCertificates.isEmpty()) {
                Timber.w(
                    "The list of valid certificates is empty. Either your file " +
                        "res/xml/allowed_media_browser_callers.xml is empty or there was an error " +
                        "while reading it. Check previous log messages."
                )
            }
            return false
        }

        val expectedPackages = StringBuilder()
        for (info in validCallers) {
            if (callingPackage == info.packageName) {
                Timber.v(
                    "Valid caller: %s package=%s release=%s",
                    info.name,
                    info.packageName,
                    info.release
                )
                return true
            }
            expectedPackages.append(info.packageName).append(' ')
        }

        Timber.i(
            "Caller has a valid certificate, but its package doesn't match any expected " +
                "package for the given certificate. Caller's package is %s . Expected " +
                "packages as defined in res/xml/allowed_media_browser_callers.xml are (%s). " +
                "This caller's certificate is: \n%s",
            callingPackage,
            expectedPackages,
            signature
        )

        return false
    }

    private data class CallerInfo(
        val name: String?,
        val packageName: String?,
        val release: Boolean
    )
}
