package io.github.rafaeljjd.feedstudy.task

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object Handoff {
    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun firstInstalled(context: Context, packages: List<String>): String? =
        packages.firstOrNull { isInstalled(context, it) }

    /** Opens the link inside one named app. Returns false if that app cannot open it. */
    fun open(activity: Activity, url: String, packageName: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun launchApp(activity: Activity, packageName: String): Boolean {
        val intent = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        activity.startActivity(intent)
        return true
    }
}
